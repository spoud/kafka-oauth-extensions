package io.confluent.oauth;

import java.net.HttpURLConnection;
import java.util.Map;
import java.io.IOException;
import org.apache.kafka.common.security.oauthbearer.internals.secured.UnretryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * @deprecated Use {@link io.spoud.oauth.HttpAccessTokenRetriever} instead.
 *             This class will be removed in the 1.6-SNAPSHOT release.
 */
@Deprecated
public class HttpAccessTokenRetriever extends io.spoud.oauth.HttpAccessTokenRetriever {

    private static final Logger log = LoggerFactory.getLogger(HttpAccessTokenRetriever.class);

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
        super(clientId, clientSecret, scope, sslSocketFactory, tokenEndpointUrl,
              loginRetryBackoffMs, loginRetryBackoffMaxMs, loginConnectTimeoutMs,
              loginReadTimeoutMs, requestMethod, useWorkloadIdentity, useUserIdentity);
        log.warn("io.confluent.oauth.HttpAccessTokenRetriever is deprecated; migrate to io.spoud.oauth.HttpAccessTokenRetriever");
    }

    @Deprecated
    public static String fetch(HttpURLConnection con,
        Map<String, String> headers,
        String requestBody,
        Integer connectTimeoutMs,
        Integer readTimeoutMs)
        throws IOException, UnretryableException {
        return io.spoud.oauth.HttpAccessTokenRetriever.fetch(con, headers, requestBody, connectTimeoutMs, readTimeoutMs);
    }

    @Deprecated
    public static String parseAccessToken(String responseBody) throws IOException {
        return io.spoud.oauth.HttpAccessTokenRetriever.parseAccessToken(responseBody);
    }
}
