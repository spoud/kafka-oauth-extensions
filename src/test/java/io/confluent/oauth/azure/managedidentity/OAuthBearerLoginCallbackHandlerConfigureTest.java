package io.confluent.oauth.azure.managedidentity;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.security.oauthbearer.internals.secured.AccessTokenRetriever;
import org.junit.jupiter.api.Test;

import javax.security.auth.login.AppConfigurationEntry;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OAuthBearerLoginCallbackHandlerConfigureTest {
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
        // Should not throw
        handler.configure(configs, "OAUTHBEARER", jaasConfigEntries);
        assertNotNull(handler.getAccessTokenRetriever());
    }

    @Test
    void testCreateAccessTokenRetrieverReturnsConfiguredRetriever() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("sasl.oauthbearer.token.endpoint.url", "https://example.com/token");
        configs.put("scope", "myscope");
        configs.put("sasl.oauthbearer.scope.claim.name", "myscope");
        configs.put("sasl.oauthbearer.scope", "myscope");
        Map<String, Object> jaasConfig = new HashMap<>();
        jaasConfig.put("clientId", "id");
        jaasConfig.put("clientSecret", "secret");
        jaasConfig.put("scope", "myscope");
        // Should not throw
        AccessTokenRetriever retriever = OAuthBearerLoginCallbackHandler.createAccessTokenRetriever(configs, "OAUTHBEARER", jaasConfig);
        assertNotNull(retriever);
    }

    @Test
    void testCreateAccessTokenRetrieverThrowsOnMissingConfig() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("sasl.oauthbearer.token.endpoint.url", "https://example.com/token");
        configs.put("scope", "myscope");
        Map<String, Object> jaasConfig = new HashMap<>();
        // missing clientId and clientSecret
        assertThrows(ConfigException.class, () ->
                OAuthBearerLoginCallbackHandler.createAccessTokenRetriever(configs, "OAUTHBEARER", jaasConfig));
    }
}
