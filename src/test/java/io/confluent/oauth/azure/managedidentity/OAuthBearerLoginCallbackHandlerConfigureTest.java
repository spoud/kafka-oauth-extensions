package io.confluent.oauth.azure.managedidentity;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.security.oauthbearer.JwtRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.login.AppConfigurationEntry;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OAuthBearerLoginCallbackHandlerConfigureTest {
    private static final String ALLOWED_URLS_PROP = "org.apache.kafka.sasl.oauthbearer.allowed.urls";
    private String oldAllowedUrls;

    @BeforeEach
    void setUpAllowedUrls() {
        oldAllowedUrls = System.getProperty(ALLOWED_URLS_PROP);
        System.setProperty(ALLOWED_URLS_PROP, "https://example.com/token");
    }

    @AfterEach
    void restoreAllowedUrls() {
        if (oldAllowedUrls != null) {
            System.setProperty(ALLOWED_URLS_PROP, oldAllowedUrls);
        } else {
            System.clearProperty(ALLOWED_URLS_PROP);
        }
    }

    @Test
    void testConfigureInitializesHandler() {
        OAuthBearerLoginCallbackHandler handler = new OAuthBearerLoginCallbackHandler();
        Map<String, Object> configs = new HashMap<>();
        configs.put("sasl.oauthbearer.token.endpoint.url", "https://example.com/token");
        configs.put("sasl.oauthbearer.scope.claim.name", "scope");
        configs.put("sasl.oauthbearer.sub.claim.name", "sub");
        Map<String, Object> jaasOptions = new HashMap<>();
        jaasOptions.put("clientId", "id");
        jaasOptions.put("clientSecret", "secret");
        jaasOptions.put("scope", "myscope");
        List<AppConfigurationEntry> jaasConfigEntries = Collections.singletonList(
                new AppConfigurationEntry("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule", AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, jaasOptions)
        );
        handler.configure(configs, "OAUTHBEARER", jaasConfigEntries);
        assertNotNull(handler.getJwtRetriever());
    }

    @Test
    void testCreateJwtRetrieverReturnsConfiguredRetriever() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("sasl.oauthbearer.token.endpoint.url", "https://example.com/token");
        configs.put("scope", "myscope");
        configs.put("sasl.oauthbearer.scope.claim.name", "myscope");
        configs.put("sasl.oauthbearer.scope", "myscope");
        Map<String, Object> jaasConfig = new HashMap<>();
        jaasConfig.put("clientId", "id");
        jaasConfig.put("clientSecret", "secret");
        jaasConfig.put("scope", "myscope");
        JwtRetriever retriever = OAuthBearerLoginCallbackHandler.createJwtRetriever(configs, "OAUTHBEARER", jaasConfig);
        assertNotNull(retriever);
    }

    @Test
    void testCreateJwtRetrieverThrowsOnMissingConfig() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("sasl.oauthbearer.token.endpoint.url", "https://example.com/token");
        configs.put("scope", "myscope");
        Map<String, Object> jaasConfig = new HashMap<>();
        // missing clientId and clientSecret
        assertThrows(ConfigException.class, () ->
                OAuthBearerLoginCallbackHandler.createJwtRetriever(configs, "OAUTHBEARER", jaasConfig));
    }

    @Test
    void testCreateJwtRetrieverEnablesWorkloadIdentityFromJaas() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("sasl.oauthbearer.token.endpoint.url", "https://example.com/token");

        Map<String, Object> jaasConfig = new HashMap<>();
        jaasConfig.put("clientId", "id");
        jaasConfig.put("clientSecret", "secret");
        jaasConfig.put("scope", "myscope");
        jaasConfig.put("useWorkloadIdentity", "true");

        JwtRetriever retriever = OAuthBearerLoginCallbackHandler.createJwtRetriever(configs, "OAUTHBEARER", jaasConfig);
        assertNotNull(retriever);
    }

    @Test
    void testCreateJwtRetrieverEnablesUserIdentityFromJaas() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("sasl.oauthbearer.token.endpoint.url", "https://example.com/token");

        Map<String, Object> jaasConfig = new HashMap<>();
        jaasConfig.put("clientId", "id");
        jaasConfig.put("clientSecret", "secret");
        jaasConfig.put("scope", "myscope");
        jaasConfig.put("useUserIdentity", "true");

        JwtRetriever retriever = OAuthBearerLoginCallbackHandler.createJwtRetriever(configs, "OAUTHBEARER", jaasConfig);
        assertNotNull(retriever);
    }
}
