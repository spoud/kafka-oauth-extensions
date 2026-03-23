package io.spoud.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.security.oauthbearer.JwtRetriever;
import org.apache.kafka.common.security.oauthbearer.JwtRetrieverException;
import org.apache.kafka.common.security.oauthbearer.internals.secured.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * {@link JwtRetriever} that authenticates a Kafka client to Keycloak using
 * <em>federated client authentication</em> (RFC 7523 JWT Bearer client credentials).
 *
 * <p>On every {@link #retrieve()} call it:
 * <ol>
 *   <li>Re-reads the projected Kubernetes ServiceAccount token from the configured file
 *       (handles K8s token rotation transparently).</li>
 *   <li>POSTs to the Keycloak token endpoint with
 *       {@code grant_type=client_credentials} and
 *       {@code client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer}.</li>
 *   <li>Extracts and returns the {@code access_token} from the JSON response.</li>
 *   <li>Logs a warning when the returned token expires within
 *       {@value #NEAR_EXPIRY_THRESHOLD_SECONDS} seconds.</li>
 * </ol>
 *
 * <p>No static client secret is needed; the Kubernetes ServiceAccount token is the
 * client credential.
 */
public class KeycloakFederatedTokenRetriever implements JwtRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeycloakFederatedTokenRetriever.class);

    private static final String CLIENT_ASSERTION_TYPE =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    static final long NEAR_EXPIRY_THRESHOLD_SECONDS = 10;

    private final String tokenEndpointUrl;
    private final String k8sTokenFilePath;
    private final String clientId;
    private final SSLSocketFactory sslSocketFactory;
    private final long loginRetryBackoffMs;
    private final long loginRetryBackoffMaxMs;
    private final Integer connectTimeoutMs;
    private final Integer readTimeoutMs;

    public KeycloakFederatedTokenRetriever(
            String tokenEndpointUrl,
            String k8sTokenFilePath,
            String clientId,
            SSLSocketFactory sslSocketFactory,
            long loginRetryBackoffMs,
            long loginRetryBackoffMaxMs,
            Integer connectTimeoutMs,
            Integer readTimeoutMs) {
        this.tokenEndpointUrl = tokenEndpointUrl;
        this.k8sTokenFilePath = k8sTokenFilePath;
        this.clientId = clientId;
        this.sslSocketFactory = sslSocketFactory;
        this.loginRetryBackoffMs = loginRetryBackoffMs;
        this.loginRetryBackoffMaxMs = loginRetryBackoffMaxMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public String retrieve() throws JwtRetrieverException {
        try {
            return retrieveInternal();
        } catch (JwtRetrieverException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtRetrieverException(e);
        }
    }

    private String retrieveInternal() throws IOException {
        // Re-read the SA token on every call so K8s rotation is handled automatically.
        String k8sToken = readK8sToken();
        String requestBody = buildRequestBody(k8sToken);
        Map<String, String> headers = Map.of("Content-Type", "application/x-www-form-urlencoded");

        Retry<String> retry = new Retry<>(loginRetryBackoffMs, loginRetryBackoffMaxMs);
        String responseBody;
        try {
            responseBody = retry.execute(() -> {
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) new URL(tokenEndpointUrl).openConnection();
                    if (sslSocketFactory != null && con instanceof HttpsURLConnection)
                        ((HttpsURLConnection) con).setSSLSocketFactory(sslSocketFactory);
                    con.setRequestMethod("POST");
                    return HttpAccessTokenRetriever.fetch(
                            con, headers, requestBody, connectTimeoutMs, readTimeoutMs);
                } catch (IOException e) {
                    throw new ExecutionException(e);
                } finally {
                    if (con != null) con.disconnect();
                }
            });
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException)
                throw (IOException) e.getCause();
            else
                throw new KafkaException(e.getCause());
        }

        String accessToken = HttpAccessTokenRetriever.parseAccessToken(responseBody);
        warnIfNearExpiry(accessToken);
        return accessToken;
    }

    private String readK8sToken() throws IOException {
        Path path = Path.of(k8sTokenFilePath);
        if (!Files.exists(path))
            throw new IOException(
                    "Kubernetes ServiceAccount token file not found: " + k8sTokenFilePath);

        String token = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (token.isEmpty())
            throw new IOException(
                    "Kubernetes ServiceAccount token file is empty: " + k8sTokenFilePath);

        log.debug("Read Kubernetes ServiceAccount token from {}", k8sTokenFilePath);
        return token;
    }

    private String buildRequestBody(String k8sToken) throws UnsupportedEncodingException {
        String enc = StandardCharsets.UTF_8.name();
        return "grant_type=client_credentials"
                + "&client_assertion_type=" + URLEncoder.encode(CLIENT_ASSERTION_TYPE, enc)
                + "&client_assertion=" + URLEncoder.encode(k8sToken, enc)
                + (clientId != null && !clientId.isBlank()
                        ? "&client_id=" + URLEncoder.encode(clientId, enc)
                        : "");
    }

    /**
     * Logs a warning when the JWT {@code exp} claim is within
     * {@value #NEAR_EXPIRY_THRESHOLD_SECONDS} seconds of now.
     * The token is still returned; Kafka's login module will trigger a refresh.
     */
    private void warnIfNearExpiry(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) return;

            int padNeeded = (4 - parts[1].length() % 4) % 4;
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1] + "=".repeat(padNeeded));
            JsonNode payload = new ObjectMapper().readTree(payloadBytes);
            JsonNode expNode = payload.get("exp");
            if (expNode == null || !expNode.isNumber()) return;

            long secondsRemaining = expNode.longValue() - System.currentTimeMillis() / 1000;
            if (secondsRemaining < NEAR_EXPIRY_THRESHOLD_SECONDS) {
                log.warn(
                        "Received Keycloak access token that expires in {}s "
                        + "(threshold: {}s). Consider increasing the token TTL on the "
                        + "Keycloak client or lowering sasl.login.refresh.buffer.seconds.",
                        secondsRemaining, NEAR_EXPIRY_THRESHOLD_SECONDS);
            }
        } catch (Exception e) {
            log.debug("Could not decode token expiry claim: {}", e.getMessage());
        }
    }
}
