package io.spoud.oauth;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig;
import io.confluent.kafka.schemaregistry.client.security.bearerauth.BearerAuthCredentialProvider;
import io.confluent.kafka.schemaregistry.client.security.bearerauth.oauth.CachedOauthTokenRetriever;
import io.confluent.kafka.schemaregistry.client.security.bearerauth.oauth.exceptions.SchemaRegistryOauthTokenRetrieverException;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAssertionBearerAuthCredentialProviderTest {

    private static final String TOKEN_ENDPOINT =
            "https://keycloak.example.com/realms/test/protocol/openid-connect/token";

    @TempDir
    Path tempDir;

    private Path assertionFile;

    @Mock
    private CachedOauthTokenRetriever mockTokenRetriever;

    private JwtAssertionBearerAuthCredentialProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        assertionFile = tempDir.resolve("assertion-token");
        Files.writeString(assertionFile, "sa-token-value");
        provider = new JwtAssertionBearerAuthCredentialProvider();
        provider.initForTesting(mockTokenRetriever);
    }

    // -------------------------------------------------------------------------
    // Alias
    // -------------------------------------------------------------------------

    @Test
    void alias_returnsJwtAssertion() {
        assertEquals("JWT_ASSERTION", provider.alias());
    }

    // -------------------------------------------------------------------------
    // configure() — validation
    // -------------------------------------------------------------------------

    @Test
    void configure_missingIssuerEndpointUrl_throwsConfigException() {
        Map<String, Object> configs = minimalConfigs();
        configs.remove(SchemaRegistryClientConfig.BEARER_AUTH_ISSUER_ENDPOINT_URL);

        ConfigException ex = assertThrows(ConfigException.class,
                () -> new JwtAssertionBearerAuthCredentialProvider().configure(configs));
        assertTrue(ex.getMessage().contains(
                SchemaRegistryClientConfig.BEARER_AUTH_ISSUER_ENDPOINT_URL));
    }

    @Test
    void configure_missingAssertionLocation_throwsConfigException() {
        Map<String, Object> configs = minimalConfigs();
        configs.remove(JwtAssertionBearerAuthCredentialProvider.CLIENT_ASSERTION_LOCATION_CONFIG);

        ConfigException ex = assertThrows(ConfigException.class,
                () -> new JwtAssertionBearerAuthCredentialProvider().configure(configs));
        assertTrue(ex.getMessage().contains(
                JwtAssertionBearerAuthCredentialProvider.CLIENT_ASSERTION_LOCATION_CONFIG));
    }

    @Test
    void configure_blankIssuerEndpointUrl_throwsConfigException() {
        Map<String, Object> configs = minimalConfigs();
        configs.put(SchemaRegistryClientConfig.BEARER_AUTH_ISSUER_ENDPOINT_URL, "   ");

        assertThrows(ConfigException.class,
                () -> new JwtAssertionBearerAuthCredentialProvider().configure(configs));
    }

    @Test
    void configure_clientIdIsOptional() {
        assertDoesNotThrow(() ->
                new JwtAssertionBearerAuthCredentialProvider().configure(minimalConfigs()));
    }

    // -------------------------------------------------------------------------
    // getBearerToken()
    // -------------------------------------------------------------------------

    @Test
    void getBearerToken_returnsTokenFromCache() throws Exception {
        when(mockTokenRetriever.getToken()).thenReturn("access-token-xyz");

        String result = provider.getBearerToken(new URL("https://sr.example.com"));

        assertEquals("access-token-xyz", result);
        verify(mockTokenRetriever).getToken();
    }

    @Test
    void getBearerToken_delegatesEachCallToCache() throws Exception {
        when(mockTokenRetriever.getToken()).thenReturn("token-a", "token-b");

        String first  = provider.getBearerToken(new URL("https://sr.example.com"));
        String second = provider.getBearerToken(new URL("https://sr.example.com"));

        // CachedOauthTokenRetriever handles its own caching internally; our provider
        // calls getToken() on every getBearerToken() call and relies on the cache's
        // own isTokenExpired() logic to decide whether to fetch a new token.
        assertEquals("token-a", first);
        assertEquals("token-b", second);
        verify(mockTokenRetriever, times(2)).getToken();
    }

    @Test
    void getBearerToken_propagatesExceptionOnFailure() throws Exception {
        when(mockTokenRetriever.getToken())
                .thenThrow(new SchemaRegistryOauthTokenRetrieverException(
                        "Keycloak unreachable", new RuntimeException("connection refused")));

        assertThrows(SchemaRegistryOauthTokenRetrieverException.class,
                () -> provider.getBearerToken(new URL("https://sr.example.com")));
    }

    @Test
    void getBearerToken_urlIsNotUsedForRoutingDecisions() throws Exception {
        when(mockTokenRetriever.getToken()).thenReturn("token");

        String r1 = provider.getBearerToken(new URL("https://sr1.example.com"));
        String r2 = provider.getBearerToken(new URL("https://sr2.example.com/different/path"));

        assertEquals("token", r1);
        assertEquals("token", r2);
    }

    // -------------------------------------------------------------------------
    // Config constants
    // -------------------------------------------------------------------------

    @Test
    void clientAssertionLocationConfig_hasExpectedValue() {
        assertEquals("bearer.auth.client.assertion.location",
                JwtAssertionBearerAuthCredentialProvider.CLIENT_ASSERTION_LOCATION_CONFIG);
    }

    @Test
    void alias_constant_matchesAliasMethod() {
        assertEquals(JwtAssertionBearerAuthCredentialProvider.ALIAS, provider.alias());
    }

    // -------------------------------------------------------------------------
    // ServiceLoader registration
    // -------------------------------------------------------------------------

    @Test
    void serviceLoader_findsJwtAssertionProviderByAlias() {
        boolean found = false;
        for (BearerAuthCredentialProvider p
                : ServiceLoader.load(BearerAuthCredentialProvider.class)) {
            if ("JWT_ASSERTION".equals(p.alias())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "JWT_ASSERTION provider should be discoverable via ServiceLoader");
    }

    @Test
    void serviceLoader_stillFindsKeycloakFederatedProvider() {
        boolean found = false;
        for (BearerAuthCredentialProvider p
                : ServiceLoader.load(BearerAuthCredentialProvider.class)) {
            if ("KEYCLOAK_FEDERATED".equals(p.alias())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "KEYCLOAK_FEDERATED provider should still be discoverable after adding JWT_ASSERTION");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> minimalConfigs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(SchemaRegistryClientConfig.BEARER_AUTH_ISSUER_ENDPOINT_URL, TOKEN_ENDPOINT);
        configs.put(JwtAssertionBearerAuthCredentialProvider.CLIENT_ASSERTION_LOCATION_CONFIG,
                assertionFile.toString());
        return configs;
    }
}
