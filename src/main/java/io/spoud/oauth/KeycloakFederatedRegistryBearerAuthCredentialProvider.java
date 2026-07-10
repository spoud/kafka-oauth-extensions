package io.spoud.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.security.bearerauth.BearerAuthCredentialProvider;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.oauthbearer.JwtRetrieverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Base64;
import java.util.Map;

/**
 * {@link BearerAuthCredentialProvider} that authenticates to the Confluent-compatible Schema
 * Registry using <em>Keycloak federated client authentication</em> (RFC 7523 JWT Bearer client
 * credentials) — the same credential exchange used by {@link KeycloakFederatedLoginCallbackHandler}
 * for Kafka broker authentication.
 *
 * <h3>Why built-in bearer credential sources do not cover this use case</h3>
 *
 * <p>Confluent Schema Registry client 7/8 ships four built-in {@code bearer.auth.credentials.source}
 * values for OAuth bearer auth: {@code STATIC_TOKEN}, {@code OAUTHBEARER},
 * {@code SASL_OAUTHBEARER_INHERIT}, and {@code CUSTOM}. None supports RFC 7523 JWT Bearer
 * client assertions:
 *
 * <ul>
 *   <li>{@code STATIC_TOKEN} — requires a long-lived token baked into the configuration;
 *       incompatible with short-lived Kubernetes projected ServiceAccount tokens.</li>
 *   <li>{@code OAUTHBEARER} — fetches a token from the IdP but only via
 *       {@code client_id} + {@code client_secret} (HTTP Basic). It cannot use a JWT as the
 *       credential to exchange.</li>
 *   <li>{@code SASL_OAUTHBEARER_INHERIT} — reads the JAAS config and performs its own
 *       {@code client_id} + {@code client_secret} token exchange. Same limitation as
 *       {@code OAUTHBEARER}: no JWT assertion flow supported.</li>
 *   <li>{@code CUSTOM} — loads any {@link BearerAuthCredentialProvider} by class name.
 *       This provider is usable via {@code CUSTOM} as an alternative to the
 *       {@code KEYCLOAK_FEDERATED} ServiceLoader alias (see below).</li>
 * </ul>
 *
 * <p>Additionally, on JDK 24 and later (JEP 486), {@code Subject.getSubject(AccessControlContext)}
 * always throws {@code UnsupportedOperationException} because the Security Manager is no longer
 * functional. Any code path that relied on inheriting a SASL {@code Subject} across threads
 * (as {@code SASL_INHERIT}-style approaches did) fails at runtime on modern JDKs.
 *
 * <h3>How this provider works</h3>
 *
 * <p>This provider directly reuses {@link KeycloakFederatedTokenRetriever} — the same component
 * that {@link KeycloakFederatedLoginCallbackHandler} uses for the Kafka broker. It runs its own
 * independent token exchange on the calling thread, caches the result until
 * {@value #EXPIRY_BUFFER_SECONDS} seconds before expiry, and refreshes transparently. There is no
 * thread-context dependency and no Security Manager involvement.
 *
 * <p>The configuration keys are identical to those of {@link KeycloakFederatedLoginCallbackHandler},
 * so a pod that already has {@code oauth.federated.token.endpoint.url} and
 * {@code oauth.federated.k8s.token.file} in its properties can reuse the same values for both
 * Kafka and Schema Registry authentication.
 *
 * <h3>Usage — {@code kafka-avro-console-consumer}</h3>
 * <pre>{@code
 * kafka-avro-console-consumer \
 *   --bootstrap-server broker:9094 \
 *   --topic my-topic \
 *   --command-config /tmp/client.properties \
 *   --formatter-property schema.registry.url=https://apicurio.example.com/apis/ccompat/v7 \
 *   --formatter-property bearer.auth.credentials.source=KEYCLOAK_FEDERATED \
 *   --formatter-property oauth.federated.token.endpoint.url=https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token \
 *   --formatter-property oauth.federated.k8s.token.file=/var/run/secrets/tokens/kafka
 * }</pre>
 *
 * <p>The alias {@code KEYCLOAK_FEDERATED} is registered via the Java {@link java.util.ServiceLoader}
 * mechanism so it is resolved automatically when this jar is on the classpath. Alternatively,
 * use {@code bearer.auth.credentials.source=CUSTOM} with
 * {@code bearer.auth.custom.provider.class=io.spoud.oauth.KeycloakFederatedRegistryBearerAuthCredentialProvider}.
 *
 * <h3>Configuration properties</h3>
 * <table>
 *   <tr><th>Property</th><th>Required</th><th>Description</th></tr>
 *   <tr><td>{@value KeycloakFederatedLoginCallbackHandler#TOKEN_ENDPOINT_URL_CONFIG}</td>
 *       <td>Yes</td><td>Keycloak token endpoint URL</td></tr>
 *   <tr><td>{@value KeycloakFederatedLoginCallbackHandler#K8S_TOKEN_FILE_CONFIG}</td>
 *       <td>Yes</td><td>Path to the projected Kubernetes ServiceAccount token file</td></tr>
 *   <tr><td>{@value KeycloakFederatedLoginCallbackHandler#CLIENT_ID_OPTION}</td>
 *       <td>No</td><td>Optional {@code client_id} to include in the token request</td></tr>
 * </table>
 */
public class KeycloakFederatedRegistryBearerAuthCredentialProvider
        implements BearerAuthCredentialProvider {

    private static final Logger log =
            LoggerFactory.getLogger(KeycloakFederatedRegistryBearerAuthCredentialProvider.class);

    /**
     * Value for {@code bearer.auth.credentials.source} that selects this provider via
     * {@link java.util.ServiceLoader}.
     */
    public static final String ALIAS = "KEYCLOAK_FEDERATED";

    /**
     * Seconds before a cached token's {@code exp} claim at which a refresh is triggered.
     * Keeps the token valid during the SR HTTP round-trip and any clock skew.
     */
    static final long EXPIRY_BUFFER_SECONDS = 30;

    private KeycloakFederatedTokenRetriever tokenRetriever;

    private volatile String cachedToken;
    private volatile long cachedExpiryEpochSec = 0;

    @Override
    public String alias() {
        return ALIAS;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        String tokenEndpoint = requireString(configs,
                KeycloakFederatedLoginCallbackHandler.TOKEN_ENDPOINT_URL_CONFIG);
        String k8sTokenFile = requireString(configs,
                KeycloakFederatedLoginCallbackHandler.K8S_TOKEN_FILE_CONFIG);
        String clientId = optionalString(configs,
                KeycloakFederatedLoginCallbackHandler.CLIENT_ID_OPTION);

        tokenRetriever = new KeycloakFederatedTokenRetriever(
                tokenEndpoint,
                k8sTokenFile,
                clientId,
                null,
                SaslConfigs.DEFAULT_SASL_LOGIN_RETRY_BACKOFF_MS,
                SaslConfigs.DEFAULT_SASL_LOGIN_RETRY_BACKOFF_MAX_MS,
                null,
                null);

        log.debug("Configured {}: endpoint={}, k8sTokenFile={}",
                getClass().getSimpleName(), tokenEndpoint, k8sTokenFile);
    }

    /**
     * Returns the cached Keycloak access token, refreshing it when within
     * {@value #EXPIRY_BUFFER_SECONDS} seconds of expiry.
     *
     * <p><strong>Best-effort semantics:</strong> this provider does not validate the access token
     * beyond parsing its {@code exp} claim. If {@code exp} cannot be parsed, the token is cached
     * with {@code expiryEpochSec = 0} and treated as immediately expired on the next call.
     * On refresh failure, a {@code WARN} is logged and — if a previously fetched token exists in
     * cache — that stale token is returned as a fallback regardless of whether it is still valid.
     * If no cached token exists, an empty string is returned, which will produce a 401 from the
     * registry rather than a hard client-side exception.
     *
     * <p>For validated token caching (structure and claims — {@code exp}, {@code sub}, scope —
     * checked via {@code ClientJwtValidator}), use {@link JwtAssertionBearerAuthCredentialProvider}
     * instead.
     */
    @Override
    public String getBearerToken(URL url) {
        long now = System.currentTimeMillis() / 1000;
        if (cachedToken != null && now < cachedExpiryEpochSec - EXPIRY_BUFFER_SECONDS) {
            return cachedToken;
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        // Double-check after acquiring the lock: another thread may have already refreshed.
        long now = System.currentTimeMillis() / 1000;
        if (cachedToken != null && now < cachedExpiryEpochSec - EXPIRY_BUFFER_SECONDS) {
            return cachedToken;
        }

        String token;
        try {
            token = tokenRetriever.retrieve();
        } catch (JwtRetrieverException e) {
            log.warn("Failed to retrieve Keycloak access token for Schema Registry — {}",
                    e.getMessage(), e);
            // Return stale token as best-effort if one exists; empty string otherwise.
            return cachedToken != null ? cachedToken : "";
        }

        cachedToken = token;
        cachedExpiryEpochSec = parseExpiry(token);
        return token;
    }

    /**
     * Extracts the {@code exp} claim (epoch seconds) from a JWT payload.
     * Returns {@code 0} on any parse error so the next call will always refresh.
     */
    static long parseExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0;
            int pad = (4 - parts[1].length() % 4) % 4;
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1] + "=".repeat(pad));
            JsonNode expNode = new ObjectMapper().readTree(payloadBytes).get("exp");
            return expNode != null && expNode.isNumber() ? expNode.longValue() : 0;
        } catch (Exception e) {
            log.debug("Could not parse exp claim from access token: {}", e.getMessage());
            return 0;
        }
    }

    private static String requireString(Map<String, ?> configs, String key) {
        Object value = configs.get(key);
        if (value instanceof String s && !s.isBlank()) return s;
        throw new ConfigException(key + " is required and must not be blank");
    }

    private static String optionalString(Map<String, ?> configs, String key) {
        Object value = configs.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** Package-visible: injects a retriever directly, for unit testing. */
    void initForTesting(KeycloakFederatedTokenRetriever retriever) {
        this.tokenRetriever = retriever;
        this.cachedToken = null;
        this.cachedExpiryEpochSec = 0;
    }
}
