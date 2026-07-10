package io.spoud.oauth;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.security.oauthbearer.JwtRetrieverException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeycloakFederatedRegistryBearerAuthCredentialProviderTest {

    private static final String TOKEN_ENDPOINT =
            "https://keycloak.example.com/realms/test/protocol/openid-connect/token";

    @TempDir
    Path tempDir;

    private Path k8sTokenFile;

    @Mock
    private KeycloakFederatedTokenRetriever mockRetriever;

    private KeycloakFederatedRegistryBearerAuthCredentialProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        k8sTokenFile = tempDir.resolve("k8s-token");
        Files.writeString(k8sTokenFile, "sa-token-value");
        provider = new KeycloakFederatedRegistryBearerAuthCredentialProvider();
        provider.initForTesting(mockRetriever);
    }

    // -------------------------------------------------------------------------
    // Alias
    // -------------------------------------------------------------------------

    @Test
    void alias_returnsKeycloakFederated() {
        assertEquals("KEYCLOAK_FEDERATED", provider.alias());
    }

    // -------------------------------------------------------------------------
    // configure() — validation
    // -------------------------------------------------------------------------

    @Test
    void configure_missingTokenEndpoint_throwsConfigException() {
        Map<String, Object> configs = minimalConfigs();
        configs.remove(KeycloakFederatedLoginCallbackHandler.TOKEN_ENDPOINT_URL_CONFIG);

        ConfigException ex = assertThrows(ConfigException.class,
                () -> new KeycloakFederatedRegistryBearerAuthCredentialProvider().configure(configs));
        assertTrue(ex.getMessage().contains(
                KeycloakFederatedLoginCallbackHandler.TOKEN_ENDPOINT_URL_CONFIG));
    }

    @Test
    void configure_missingK8sTokenFile_throwsConfigException() {
        Map<String, Object> configs = minimalConfigs();
        configs.remove(KeycloakFederatedLoginCallbackHandler.K8S_TOKEN_FILE_CONFIG);

        ConfigException ex = assertThrows(ConfigException.class,
                () -> new KeycloakFederatedRegistryBearerAuthCredentialProvider().configure(configs));
        assertTrue(ex.getMessage().contains(
                KeycloakFederatedLoginCallbackHandler.K8S_TOKEN_FILE_CONFIG));
    }

    @Test
    void configure_blankTokenEndpoint_throwsConfigException() {
        Map<String, Object> configs = minimalConfigs();
        configs.put(KeycloakFederatedLoginCallbackHandler.TOKEN_ENDPOINT_URL_CONFIG, "   ");

        assertThrows(ConfigException.class,
                () -> new KeycloakFederatedRegistryBearerAuthCredentialProvider().configure(configs));
    }

    @Test
    void configure_clientIdIsOptional() {
        // Does not throw even without clientId
        assertDoesNotThrow(() ->
                new KeycloakFederatedRegistryBearerAuthCredentialProvider().configure(minimalConfigs()));
    }

    // -------------------------------------------------------------------------
    // getBearerToken() — happy path
    // -------------------------------------------------------------------------

    @Test
    void getBearerToken_returnsTokenFromRetriever() throws Exception {
        String token = jwtWithExp(farFuture());
        when(mockRetriever.retrieve()).thenReturn(token);

        String result = provider.getBearerToken(new URL("https://sr.example.com"));

        assertEquals(token, result);
    }

    // -------------------------------------------------------------------------
    // Caching — token is not re-fetched while still valid
    // -------------------------------------------------------------------------

    @Test
    void getBearerToken_cachedTokenIsReusedWithinTtl() throws Exception {
        String token = jwtWithExp(farFuture());
        when(mockRetriever.retrieve()).thenReturn(token);

        provider.getBearerToken(new URL("https://sr.example.com"));
        provider.getBearerToken(new URL("https://sr.example.com"));
        provider.getBearerToken(new URL("https://sr.example.com"));

        // Retriever should only have been called once despite three getBearerToken() calls
        verify(mockRetriever, times(1)).retrieve();
    }

    @Test
    void getBearerToken_refreshesWhenTokenIsExpired() throws Exception {
        String expiredToken = jwtWithExp(pastExpiry());
        String freshToken   = jwtWithExp(farFuture());
        when(mockRetriever.retrieve()).thenReturn(expiredToken, freshToken);

        // First call fetches and caches expired token
        String first = provider.getBearerToken(new URL("https://sr.example.com"));
        assertEquals(expiredToken, first);

        // Second call sees expired cache and refreshes
        String second = provider.getBearerToken(new URL("https://sr.example.com"));
        assertEquals(freshToken, second);

        verify(mockRetriever, times(2)).retrieve();
    }

    @Test
    void getBearerToken_refreshesWhenTokenExpiresWithinBuffer() throws Exception {
        // Token expires in 20 s — within the 30 s buffer, so it should be refreshed on next call
        String soonExpiringToken = jwtWithExp(System.currentTimeMillis() / 1000 + 20);
        String freshToken        = jwtWithExp(farFuture());
        when(mockRetriever.retrieve()).thenReturn(soonExpiringToken, freshToken);

        provider.getBearerToken(new URL("https://sr.example.com"));
        String second = provider.getBearerToken(new URL("https://sr.example.com"));

        assertEquals(freshToken, second);
        verify(mockRetriever, times(2)).retrieve();
    }

    // -------------------------------------------------------------------------
    // Retriever failures
    // -------------------------------------------------------------------------

    @Test
    void getBearerToken_retrieverFailsOnFirstCall_returnsEmptyString() throws Exception {
        when(mockRetriever.retrieve())
                .thenThrow(new JwtRetrieverException(new RuntimeException("Keycloak unreachable")));

        String result = provider.getBearerToken(new URL("https://sr.example.com"));

        assertEquals("", result,
                "Should return empty string when no cached token exists and retrieval fails");
    }

    @Test
    void getBearerToken_retrieverFailsAfterCachedTokenExpires_returnsStaleCachedToken()
            throws Exception {
        String expiredToken = jwtWithExp(pastExpiry());
        when(mockRetriever.retrieve())
                .thenReturn(expiredToken)
                .thenThrow(new JwtRetrieverException(new RuntimeException("transient failure")));

        // Prime the cache with an expired token
        provider.getBearerToken(new URL("https://sr.example.com"));

        // Second call: cache expired, refresh fails → should return stale token, not empty string
        String result = provider.getBearerToken(new URL("https://sr.example.com"));

        assertEquals(expiredToken, result,
                "Should return stale cached token as fallback when refresh fails");
    }

    // -------------------------------------------------------------------------
    // parseExpiry()
    // -------------------------------------------------------------------------

    @Test
    void parseExpiry_extractsExpClaimCorrectly() {
        long expectedExp = 9_999_999_999L;
        String jwt = jwtWithExp(expectedExp);
        assertEquals(expectedExp, KeycloakFederatedRegistryBearerAuthCredentialProvider.parseExpiry(jwt));
    }

    @Test
    void parseExpiry_returnsZeroForMalformedJwt() {
        assertEquals(0, KeycloakFederatedRegistryBearerAuthCredentialProvider.parseExpiry("not.a.jwt"));
    }

    @Test
    void parseExpiry_returnsZeroWhenExpClaimAbsent() {
        String header  = b64("{\"alg\":\"RS256\"}");
        String payload = b64("{\"sub\":\"test\"}"); // no exp
        String jwt = header + "." + payload + ".sig";
        assertEquals(0, KeycloakFederatedRegistryBearerAuthCredentialProvider.parseExpiry(jwt));
    }

    @Test
    void parseExpiry_returnsZeroForSingleSegmentString() {
        assertEquals(0, KeycloakFederatedRegistryBearerAuthCredentialProvider.parseExpiry("onlyone"));
    }

    // -------------------------------------------------------------------------
    // ServiceLoader registration
    // -------------------------------------------------------------------------

    @Test
    void serviceLoader_findsProviderByAlias() {
        boolean found = false;
        for (io.confluent.kafka.schemaregistry.client.security.bearerauth.BearerAuthCredentialProvider p
                : java.util.ServiceLoader.load(
                    io.confluent.kafka.schemaregistry.client.security.bearerauth.BearerAuthCredentialProvider.class)) {
            if ("KEYCLOAK_FEDERATED".equals(p.alias())) {
                found = true;
                break;
            }
        }
        assertTrue(found,
                "KEYCLOAK_FEDERATED provider should be discoverable via ServiceLoader");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns a minimal valid config map pointing at the temp K8s token file. */
    private Map<String, Object> minimalConfigs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(KeycloakFederatedLoginCallbackHandler.TOKEN_ENDPOINT_URL_CONFIG, TOKEN_ENDPOINT);
        configs.put(KeycloakFederatedLoginCallbackHandler.K8S_TOKEN_FILE_CONFIG,
                k8sTokenFile.toString());
        return configs;
    }

    /** Creates a minimal JWT with the given {@code exp} epoch-second value. */
    static String jwtWithExp(long expEpochSeconds) {
        String header  = b64("{\"alg\":\"RS256\"}");
        String payload = b64("{\"exp\":" + expEpochSeconds + ",\"sub\":\"test\"}");
        return header + "." + payload + ".fake-sig";
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** An expiry well in the future (1 year from now). */
    private static long farFuture() {
        return System.currentTimeMillis() / 1000 + 365 * 24 * 3600;
    }

    /** An expiry in the past — simulates an already-expired token in the cache. */
    private static long pastExpiry() {
        return System.currentTimeMillis() / 1000 - 60;
    }
}
