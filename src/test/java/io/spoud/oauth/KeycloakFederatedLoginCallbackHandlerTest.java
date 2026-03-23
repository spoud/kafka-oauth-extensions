package io.spoud.oauth;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.security.oauthbearer.JwtRetriever;
import org.apache.kafka.common.security.oauthbearer.JwtValidator;
import org.apache.kafka.common.security.oauthbearer.JwtValidatorException;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;
import org.apache.kafka.common.security.auth.SaslExtensionsCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeycloakFederatedLoginCallbackHandlerTest {

    private static final String ALLOWED_URLS_PROP = "org.apache.kafka.sasl.oauthbearer.allowed.urls";
    private static final String TOKEN_ENDPOINT = "https://keycloak.example.com/realms/test/protocol/openid-connect/token";

    @TempDir
    Path tempDir;

    @Mock
    private JwtRetriever mockRetriever;

    @Mock
    private JwtValidator mockValidator;

    @Mock
    private OAuthBearerToken mockToken;

    private String oldAllowedUrls;

    @BeforeEach
    void setUp() throws IOException {
        oldAllowedUrls = System.getProperty(ALLOWED_URLS_PROP);
        System.setProperty(ALLOWED_URLS_PROP, TOKEN_ENDPOINT);
        // Create a dummy SA token file for configure() tests that need a valid path
        Files.writeString(tempDir.resolve("k8s-token"), "dummy-k8s-token");
    }

    @AfterEach
    void restoreAllowedUrls() {
        if (oldAllowedUrls != null) {
            System.setProperty(ALLOWED_URLS_PROP, oldAllowedUrls);
        } else {
            System.clearProperty(ALLOWED_URLS_PROP);
        }
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    @Test
    void testConfigure_validConfig() {
        KeycloakFederatedLoginCallbackHandler handler = new KeycloakFederatedLoginCallbackHandler();
        handler.configure(validConfigs(), "OAUTHBEARER", emptyJaas());
        assertNotNull(handler.getJwtRetriever());
    }

    @Test
    void testConfigure_missingEndpointUrl() {
        KeycloakFederatedLoginCallbackHandler handler = new KeycloakFederatedLoginCallbackHandler();
        Map<String, Object> configs = new HashMap<>(validConfigs());
        configs.remove(KeycloakFederatedLoginCallbackHandler.TOKEN_ENDPOINT_URL_CONFIG);

        assertThrows(ConfigException.class,
                () -> handler.configure(configs, "OAUTHBEARER", emptyJaas()));
    }

    @Test
    void testConfigure_missingK8sTokenFile() {
        KeycloakFederatedLoginCallbackHandler handler = new KeycloakFederatedLoginCallbackHandler();
        Map<String, Object> configs = new HashMap<>(validConfigs());
        configs.remove(KeycloakFederatedLoginCallbackHandler.K8S_TOKEN_FILE_CONFIG);

        assertThrows(ConfigException.class,
                () -> handler.configure(configs, "OAUTHBEARER", emptyJaas()));
    }

    // -------------------------------------------------------------------------
    // Callback handling
    // -------------------------------------------------------------------------

    @Test
    void testHandle_oauthBearerTokenCallback() throws Exception {
        String fakeToken = KeycloakFederatedTokenRetrieverTest.jwtWithExp(
                System.currentTimeMillis() / 1000 + 3600);
        when(mockRetriever.retrieve()).thenReturn(fakeToken);
        when(mockValidator.validate(fakeToken)).thenReturn(mockToken);

        KeycloakFederatedLoginCallbackHandler handler = handlerWithMocks(emptyJaas());
        OAuthBearerTokenCallback cb = new OAuthBearerTokenCallback();
        handler.handle(new Callback[]{cb});

        assertSame(mockToken, cb.token());
    }

    @Test
    void testHandle_saslExtensionsCallback() throws Exception {
        Map<String, Object> jaasOptions = new HashMap<>();
        jaasOptions.put("extension_logicalCluster", "lkc-test");
        jaasOptions.put("extension_identityPoolId", "pool-test");

        KeycloakFederatedLoginCallbackHandler handler = handlerWithMocks(jaasEntries(jaasOptions));
        SaslExtensionsCallback cb = new SaslExtensionsCallback();
        handler.handle(new Callback[]{cb});

        assertEquals("lkc-test", cb.extensions().map().get("logicalCluster"));
        assertEquals("pool-test", cb.extensions().map().get("identityPoolId"));
    }

    @Test
    void testHandle_unsupportedCallback() {
        KeycloakFederatedLoginCallbackHandler handler = handlerWithMocks(emptyJaas());
        Callback unknown = mock(Callback.class);

        assertThrows(UnsupportedCallbackException.class,
                () -> handler.handle(new Callback[]{unknown}));
    }

    @Test
    void testHandle_beforeConfigure_throwsIllegalState() {
        KeycloakFederatedLoginCallbackHandler handler = new KeycloakFederatedLoginCallbackHandler();
        assertThrows(IllegalStateException.class,
                () -> handler.handle(new Callback[]{new OAuthBearerTokenCallback()}));
    }

    // -------------------------------------------------------------------------
    // close()
    // -------------------------------------------------------------------------

    @Test
    void testClose_propagatesToRetriever() throws IOException {
        KeycloakFederatedLoginCallbackHandler handler = handlerWithMocks(emptyJaas());
        handler.close();
        verify(mockRetriever).close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> validConfigs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(KeycloakFederatedLoginCallbackHandler.TOKEN_ENDPOINT_URL_CONFIG, TOKEN_ENDPOINT);
        configs.put(KeycloakFederatedLoginCallbackHandler.K8S_TOKEN_FILE_CONFIG,
                tempDir.resolve("k8s-token").toString());
        configs.put("sasl.oauthbearer.scope.claim.name", "scope");
        configs.put("sasl.oauthbearer.sub.claim.name", "sub");
        return configs;
    }

    private static List<AppConfigurationEntry> emptyJaas() {
        return jaasEntries(Collections.emptyMap());
    }

    private static List<AppConfigurationEntry> jaasEntries(Map<String, Object> options) {
        return Collections.singletonList(new AppConfigurationEntry(
                "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule",
                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                options));
    }

    /**
     * Creates a handler that has gone through configure() (so moduleOptions is populated)
     * and then has its retriever/validator replaced with mocks via init().
     */
    private KeycloakFederatedLoginCallbackHandler handlerWithMocks(List<AppConfigurationEntry> jaas) {
        KeycloakFederatedLoginCallbackHandler handler = new KeycloakFederatedLoginCallbackHandler();
        handler.configure(validConfigs(), "OAUTHBEARER", jaas);
        handler.init(mockRetriever, mockValidator); // replace real retriever/validator with mocks
        return handler;
    }
}
