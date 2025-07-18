/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file is derived from code copied from Apache Kafka:
 *
 * clients/src/main/java/org/apache/kafka/common/security/oauthbearer/secured/HttpAccessTokenRetriever.java
 */

package io.confluent.oauth;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.AzureCliCredential;
import com.azure.identity.AzureCliCredentialBuilder;
import com.azure.identity.CredentialUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import io.confluent.oauth.azure.managedidentity.utils.WorkloadIdentityUtils;
import org.apache.kafka.common.KafkaException;


import org.apache.kafka.common.security.oauthbearer.internals.secured.AccessTokenRetriever;
import org.apache.kafka.common.security.oauthbearer.internals.secured.Retry;
import org.apache.kafka.common.security.oauthbearer.internals.secured.UnretryableException;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <code>HttpAccessTokenRetriever</code> is an {@link AccessTokenRetriever} copied and derived from Apacha Kafka
 * {@link org.apache.kafka.common.security.oauthbearer.internals.secured.HttpAccessTokenRetriever}.
 *
 * This version is designed to work with Azure Managed Identities via the link-local Instance Metadata Service (IMDS).
 * This service does not require authentication, and while an id/secret config is required, it is ignored.  Other
 * changes are:
 * - Uses HTTP GET instead of POST and does not send a POST body.
 * - Sets a static HTTP Header - Metadata: true .
 *
 * @see AccessTokenRetriever
 */

public class HttpAccessTokenRetriever implements AccessTokenRetriever {

    private static final Logger log = LoggerFactory.getLogger(HttpAccessTokenRetriever.class);

    private static final Set<Integer> UNRETRYABLE_HTTP_CODES;

    private static final int MAX_RESPONSE_BODY_LENGTH = 1000;

    public static final String AUTHORIZATION_HEADER = "Authorization";

    static {
        // This does not have to be an exhaustive list. There are other HTTP codes that
        // are defined in different RFCs (e.g. https://datatracker.ietf.org/doc/html/rfc6585)
        // that we won't worry about yet. The worst case if a status code is missing from
        // this set is that the request will be retried.
        UNRETRYABLE_HTTP_CODES = new HashSet<>();
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_BAD_REQUEST);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_UNAUTHORIZED);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_PAYMENT_REQUIRED);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_FORBIDDEN);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_NOT_FOUND);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_BAD_METHOD);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_NOT_ACCEPTABLE);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_PROXY_AUTH);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_CONFLICT);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_GONE);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_LENGTH_REQUIRED);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_PRECON_FAILED);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_ENTITY_TOO_LARGE);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_REQ_TOO_LONG);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_UNSUPPORTED_TYPE);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_NOT_IMPLEMENTED);
        UNRETRYABLE_HTTP_CODES.add(HttpURLConnection.HTTP_VERSION);
    }

    private final String clientId;

    private final String clientSecret;

    private final String scope;

    private final SSLSocketFactory sslSocketFactory;

    private final String tokenEndpointUrl;

    private final long loginRetryBackoffMs;

    private final long loginRetryBackoffMaxMs;

    private final Integer loginConnectTimeoutMs;

    private final Integer loginReadTimeoutMs;

    private final String requestMethod;

    private final Map<String, String> headers = new HashMap<>();

    private final boolean useWorkloadIdentity;
    private boolean useUserIdentity;

    public HttpAccessTokenRetriever(String clientId,
        String clientSecret,
        String scope,
        SSLSocketFactory sslSocketFactory,
        String tokenEndpointUrl,
        long loginRetryBackoffMs,
        long loginRetryBackoffMaxMs,
        Integer loginConnectTimeoutMs,
        Integer loginReadTimeoutMs,
        String requestMethod,
        boolean useWorkloadIdentity,
        boolean useUserIdentity) {
        this.clientId = Objects.requireNonNull(clientId);
        this.clientSecret = Objects.requireNonNull(clientSecret);
        this.useWorkloadIdentity = useWorkloadIdentity;
        this.useUserIdentity = useUserIdentity;
        this.scope = scope;
        this.sslSocketFactory = sslSocketFactory;
        this.tokenEndpointUrl = Objects.requireNonNull(tokenEndpointUrl);
        this.loginRetryBackoffMs = loginRetryBackoffMs;
        this.loginRetryBackoffMaxMs = loginRetryBackoffMaxMs;
        this.loginConnectTimeoutMs = loginConnectTimeoutMs;
        this.loginReadTimeoutMs = loginReadTimeoutMs;
        this.requestMethod = requestMethod;
    }

    /**
     * Retrieves a JWT access token in its serialized three-part form. The implementation
     * is free to determine how it should be retrieved but should not perform validation
     * on the result.
     *
     * <b>Note</b>: This is a blocking function and callers should be aware that the
     * implementation communicates over a network. The facility in the
     * {@link javax.security.auth.spi.LoginModule} from which this is ultimately called
     * does not provide an asynchronous approach.
     *
     * @return Non-<code>null</code> JWT access token string
     *
     * @throws IOException Thrown on errors related to IO during retrieval
     */

    @Override
    public String retrieve() throws IOException {
        String responseBody;

        try {
            // TODO: should we use the AZURE_FEDERATED_TOKEN_FILE variable to enable workload identity? And AZURE_AUTHORITY_HOST to use for the endpoint url?
            if (this.useWorkloadIdentity){
                log.debug("using workload identity to get token");
                // AccessToken https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/core/azure-core/src/main/java/com/azure/core/credential/AccessToken.java
                TokenCredential workloadIdentityCredential = WorkloadIdentityUtils.createWorkloadIdentityCredentialFromEnvironment();
                TokenRequestContext tokenRequestContext = WorkloadIdentityUtils.createTokenRequestContextFromEnvironment(scope);
                AccessToken azureIdentityAccessToken = workloadIdentityCredential.getTokenSync(tokenRequestContext);
                log.trace("useWorkloadIdentity token, got token from AzureAD: '{}'", azureIdentityAccessToken.getToken());
                return azureIdentityAccessToken.getToken();
            }

            if (this.useUserIdentity) {
                log.debug("using user identity to get token");
                AzureCliCredential cliCredential = new AzureCliCredentialBuilder().build();
                TokenRequestContext tokenRequestContext = new TokenRequestContext().addScopes(scope);
                AccessToken azureIdentityAccessToken = cliCredential.getTokenSync(tokenRequestContext);
                log.trace("useUserIdentity token, got token from AzureAD: '{}'", azureIdentityAccessToken.getToken());
                return azureIdentityAccessToken.getToken();
            }

            String authorizationHeader = formatAuthorizationHeader(clientId, clientSecret);
            String requestBody = requestMethod == "GET" ? null : formatRequestBody(scope);
            Retry<String> retry = new Retry<>(loginRetryBackoffMs, loginRetryBackoffMaxMs);

            final Map<String, String> requestHeaders = new HashMap<>(headers);
            requestHeaders.put(AUTHORIZATION_HEADER, authorizationHeader);


            responseBody = retry.execute(() -> {
                HttpURLConnection con = null;

                try {
                    con = (HttpURLConnection) new URL(tokenEndpointUrl).openConnection();

                    if (sslSocketFactory != null && con instanceof HttpsURLConnection)
                        ((HttpsURLConnection) con).setSSLSocketFactory(sslSocketFactory);

                    con.setRequestMethod(requestMethod);
                    return fetch(con, requestHeaders, requestBody, loginConnectTimeoutMs, loginReadTimeoutMs);
                } catch (IOException e) {
                    throw new ExecutionException(e);
                } finally {
                    if (con != null)
                        con.disconnect();
                }
            });
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException)
                throw (IOException) e.getCause();
            else
                throw new KafkaException(e.getCause());
        } catch (CredentialUnavailableException ex){
            log.error("Error getting token from AzureAD: {}", ex.getMessage());
            throw new KafkaException(ex);
        }

        return parseAccessToken(responseBody);
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public static String fetch(HttpURLConnection con,
        Map<String, String> headers,
        String requestBody,
        Integer connectTimeoutMs,
        Integer readTimeoutMs)
        throws IOException, UnretryableException {
        handleInput(con, headers, requestBody, connectTimeoutMs, readTimeoutMs);
        return handleOutput(con);
    }

    private static void handleInput(HttpURLConnection con,
        Map<String, String> headers,
        String requestBody,
        Integer connectTimeoutMs,
        Integer readTimeoutMs)
        throws IOException, UnretryableException {
        log.debug("handleInput - starting fetch for {}", con.getURL());
        con.setRequestProperty("Accept", "application/json");

        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet())
                con.setRequestProperty(header.getKey(), header.getValue());
        }

        con.setRequestProperty("Cache-Control", "no-cache");

        if (requestBody != null) {
            con.setRequestProperty("Content-Length", String.valueOf(requestBody.length()));
            con.setDoOutput(true);
        }

        con.setUseCaches(false);

        if (connectTimeoutMs != null)
            con.setConnectTimeout(connectTimeoutMs);

        if (readTimeoutMs != null)
            con.setReadTimeout(readTimeoutMs);

        log.debug("handleInput - preparing to connect to {}", con.getURL());
        con.connect();

        if (requestBody != null) {
            try (OutputStream os = con.getOutputStream()) {
                ByteArrayInputStream is = new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8));
                log.debug("handleInput - preparing to write request body to {}", con.getURL());
                copy(is, os);
            }
        }
    }

    static String handleOutput(final HttpURLConnection con) throws IOException {
        int responseCode = con.getResponseCode();
        log.debug("handleOutput - responseCode: {}", responseCode);

        String responseBody = null;

        try (InputStream is = con.getInputStream()) {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            log.debug("handleOutput - preparing to read response body from {}", con.getURL());
            copy(is, os);
            responseBody = os.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            log.warn("handleOutput - error retrieving data", e);
        }

        if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
            log.debug("handleOutput - responseCode: {}, response: {}", responseCode, responseBody);

            if (responseBody == null || responseBody.isEmpty())
                throw new IOException(String.format("The token endpoint response was unexpectedly empty despite response code %s from %s", responseCode, con.getURL()));

            return responseBody;
        } else {
            log.warn("handleOutput - error response code: {}, error response body: {}", responseCode, responseBody);

            if (UNRETRYABLE_HTTP_CODES.contains(responseCode)) {
                // We know that this is a non-transient error, so let's not keep retrying the
                // request unnecessarily.
                throw new UnretryableException(new IOException(String.format("The response code %s was encountered reading the token endpoint response; will not attempt further retries", responseCode)));
            } else {
                // We don't know if this is a transient (retryable) error or not, so let's assume
                // it is.
                throw new IOException(String.format("The unexpected response code %s was encountered reading the token endpoint response", responseCode));
            }
        }
    }

    static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[4096];
        int b;

        while ((b = is.read(buf)) != -1)
            os.write(buf, 0, b);
    }

    static String parseAccessToken(String responseBody) throws IOException {
        log.debug("parseAccessToken - responseBody: {}", responseBody);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(responseBody);
        JsonNode accessTokenNode = rootNode.at("/access_token");

        if (accessTokenNode == null) {
            // Only grab the first N characters so that if the response body is huge, we don't
            // blow up.
            String snippet = responseBody;

            if (snippet.length() > MAX_RESPONSE_BODY_LENGTH) {
                int actualLength = responseBody.length();
                String s = responseBody.substring(0, MAX_RESPONSE_BODY_LENGTH);
                snippet = String.format("%s (trimmed to first %s characters out of %s total)", s, MAX_RESPONSE_BODY_LENGTH, actualLength);
            }

            throw new IOException(String.format("The token endpoint response did not contain an access_token value. Response: (%s)", snippet));
        }

        return sanitizeString("the token endpoint response's access_token JSON attribute", accessTokenNode.textValue());
    }

    static String formatAuthorizationHeader(String clientId, String clientSecret) {
        clientId = sanitizeString("the token endpoint request client ID parameter", clientId);
        clientSecret = sanitizeString("the token endpoint request client secret parameter", clientSecret);

        String s = String.format("%s:%s", clientId, clientSecret);
        String encoded = Base64.getUrlEncoder().encodeToString(Utils.utf8(s));
        return String.format("Basic %s", encoded);
    }

    static String formatRequestBody(String scope) throws IOException {
        try {
            StringBuilder requestParameters = new StringBuilder();
            requestParameters.append("grant_type=client_credentials");

            if (scope != null && !scope.trim().isEmpty()) {
                scope = scope.trim();
                String encodedScope = URLEncoder.encode(scope, StandardCharsets.UTF_8.name());
                requestParameters.append("&scope=").append(encodedScope);
            }

            return requestParameters.toString();
        } catch (UnsupportedEncodingException e) {
            // The world has gone crazy!
            throw new IOException(String.format("Encoding %s not supported", StandardCharsets.UTF_8.name()));
        }
    }

    private static String sanitizeString(String name, String value) {
        if (value == null)
            throw new IllegalArgumentException(String.format("The value for %s must be non-null", name));

        if (value.isEmpty())
            throw new IllegalArgumentException(String.format("The value for %s must be non-empty", name));

        value = value.trim();

        if (value.isEmpty())
            throw new IllegalArgumentException(String.format("The value for %s must not contain only whitespace", name));

        return value;
    }

}
