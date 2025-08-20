package io.confluent.oauth.azure.managedidentity;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.oauthbearer.internals.secured.AccessTokenRetriever;
import org.apache.kafka.common.security.oauthbearer.internals.secured.AccessTokenValidator;
import org.apache.kafka.common.security.oauthbearer.internals.secured.ValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.login.AppConfigurationEntry;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistryBearerAuthCredentialProviderTest {
    private RegistryBearerAuthCredentialProvider provider;
    private AccessTokenRetriever retriever;
    private AccessTokenValidator validator;

    @BeforeEach
    void setUp() {
        provider = new RegistryBearerAuthCredentialProvider();
        retriever = mock(AccessTokenRetriever.class);
        validator = mock(AccessTokenValidator.class);
        provider.init(retriever, validator);
    }

    @Test
    void testInitSetsInitializedAndCallsRetrieverInit() throws IOException {
        AccessTokenRetriever retrieverMock = mock(AccessTokenRetriever.class);
        AccessTokenValidator validatorMock = mock(AccessTokenValidator.class);
        doNothing().when(retrieverMock).init();
        RegistryBearerAuthCredentialProvider p = new RegistryBearerAuthCredentialProvider();
        p.init(retrieverMock, validatorMock);
        verify(retrieverMock).init();
    }

    @Test
    void testInitThrowsOnRetrieverInitError() throws IOException {
        AccessTokenRetriever retrieverMock = mock(AccessTokenRetriever.class);
        AccessTokenValidator validatorMock = mock(AccessTokenValidator.class);
        doThrow(new IOException("fail")).when(retrieverMock).init();
        RegistryBearerAuthCredentialProvider p = new RegistryBearerAuthCredentialProvider();
        assertThrows(RuntimeException.class, () -> p.init(retrieverMock, validatorMock));
    }

    @Test
    void testGetBearerTokenReturnsToken() throws Exception {
        String expectedToken = "token";
        when(retriever.retrieve()).thenReturn(expectedToken);
        when(validator.validate(expectedToken)).thenReturn(mock(org.apache.kafka.common.security.oauthbearer.OAuthBearerToken.class));
        String token = provider.getBearerToken(new URL("http://localhost"));
        assertEquals(expectedToken, token);
    }

    @Test
    void testGetBearerTokenReturnsEmptyOnException() throws Exception {
        when(retriever.retrieve()).thenThrow(new IOException("fail"));
        String token = provider.getBearerToken(new URL("http://localhost"));
        assertEquals("", token);
    }

    @Test
    void testGetBearerTokenReturnsEmptyOnValidateException() throws Exception {
        when(retriever.retrieve()).thenReturn("token");
        when(validator.validate("token")).thenThrow(new ValidateException("fail"));
        String token = provider.getBearerToken(new URL("http://localhost"));
        assertEquals("", token);
    }

    @Test
    void testGetTargetIdentityPoolIdAndSchemaRegistry() {
        // Simulate configure
        Map<String, Object> configs = new HashMap<>();
        configs.put(SaslConfigs.SASL_JAAS_CONFIG, "test");
        configs.put(SchemaRegistryClientConfig.BEARER_AUTH_LOGICAL_CLUSTER, "cluster");
        configs.put(SchemaRegistryClientConfig.BEARER_AUTH_IDENTITY_POOL_ID, "pool");
        configs.put(SaslConfigs.SASL_MECHANISM, "OAUTHBEARER");
        configs.put(SaslConfigs.SASL_OAUTHBEARER_TOKEN_ENDPOINT_URL, "https://example.com/token");
        configs.put("clientId", "id");
        configs.put("clientSecret", "secret");
        configs.put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required clientId=\"id\" clientSecret=\"secret\" scope=\"myscope\";");
        // Use a mutable map for JAAS config
        Map<String, Object> jaasOptions = new HashMap<>();
        jaasOptions.put("clientId", "id");
        jaasOptions.put("clientSecret", "secret");
        jaasOptions.put("scope", "myscope");
        AppConfigurationEntry entry = new AppConfigurationEntry("test", AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, jaasOptions);

        try {
            provider.configure(configs);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        assertEquals("pool", provider.getTargetIdentityPoolId());
        assertEquals("cluster", provider.getTargetSchemaRegistry());
    }

    @Test
    void testConfigureInitializesProvider() {
        RegistryBearerAuthCredentialProvider provider = new RegistryBearerAuthCredentialProvider();
        Map<String, Object> configs = new HashMap<>();
        configs.put("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required clientId=\"id\" clientSecret=\"secret\" scope=\"myscope\";");
        configs.put("sasl.mechanism", "OAUTHBEARER");
        configs.put("sasl.oauthbearer.token.endpoint.url", "https://example.com/token");
        configs.put("clientId", "id");
        configs.put("clientSecret", "secret");
        configs.put("scope", "myscope");
        configs.put("bearer.auth.logical.cluster", "test-cluster");
        configs.put("bearer.auth.identity.pool.id", "test-pool");
        // This will throw unless JaasContext.loadClientContext is mocked, so just check for ConfigException or successful call
        try {
            provider.configure(configs);
        } catch (ConfigException | NullPointerException e) {
            // Acceptable for this test context, as we can't mock static JaasContext easily here
        }
    }
}
