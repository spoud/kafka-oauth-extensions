package io.spoud.oauth;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig;
import io.confluent.kafka.schemaregistry.client.security.bearerauth.BearerAuthCredentialProvider;
import io.confluent.kafka.schemaregistry.client.security.bearerauth.oauth.CachedOauthTokenRetriever;
import io.confluent.kafka.schemaregistry.client.security.bearerauth.oauth.OauthTokenCache;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.oauthbearer.ClientJwtValidator;

import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * {@link BearerAuthCredentialProvider} that authenticates to a Confluent-compatible Schema
 * Registry using an <em>RFC 7523 JWT Bearer client assertion</em> — the same mechanism used by
 * {@link KeycloakFederatedLoginCallbackHandler} for Kafka broker authentication.
 *
 * <p>See {@link KeycloakFederatedRegistryBearerAuthCredentialProvider} for a full explanation of
 * why built-in credential sources do not cover the JWT assertion use case.
 *
 * <h3>Relationship to {@link KeycloakFederatedRegistryBearerAuthCredentialProvider}</h3>
 *
 * <p>Both providers perform the same RFC 7523 JWT Bearer client assertion token exchange against
 * Keycloak, but differ in several operational dimensions:
 *
 * <ul>
 *   <li><strong>Config namespace:</strong>
 *       {@link KeycloakFederatedRegistryBearerAuthCredentialProvider} ({@code KEYCLOAK_FEDERATED})
 *       uses {@code oauth.federated.*} keys shared with {@link KeycloakFederatedLoginCallbackHandler}.
 *       This provider ({@code JWT_ASSERTION}) uses the standard {@code bearer.auth.*} namespace
 *       defined in {@link SchemaRegistryClientConfig}.</li>
 *   <li><strong>Caching:</strong> {@code KEYCLOAK_FEDERATED} maintains its own token cache with a
 *       fixed 30-second expiry buffer. This provider delegates to the SR client's own
 *       {@link CachedOauthTokenRetriever}, which respects the configurable
 *       {@link SchemaRegistryClientConfig#BEARER_AUTH_CACHE_EXPIRY_BUFFER_SECONDS} key
 *       (default: 300 s).</li>
 *   <li><strong>Token validation:</strong> {@code KEYCLOAK_FEDERATED} only parses the {@code exp}
 *       claim; it does not validate token structure or claims. This provider passes the token
 *       through {@code ClientJwtValidator} inside {@link CachedOauthTokenRetriever}, which
 *       validates claims before caching.</li>
 *   <li><strong>Failure behavior:</strong> {@code KEYCLOAK_FEDERATED} returns a stale cached
 *       token (or empty string) on refresh failure. This provider propagates the exception from
 *       {@link CachedOauthTokenRetriever} to the caller.</li>
 * </ul>
 *
 * <p>Use {@code JWT_ASSERTION} when configuring Schema Registry auth in isolation, when the
 * Schema Registry and Kafka use different Keycloak realms, or when you prefer the standard SR
 * config vocabulary and stricter caching semantics.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * kafka-avro-console-consumer \
 *   --formatter-property schema.registry.url=https://apicurio.example.com/apis/ccompat/v7 \
 *   --formatter-property bearer.auth.credentials.source=JWT_ASSERTION \
 *   --formatter-property bearer.auth.issuer.endpoint.url=https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token \
 *   --formatter-property bearer.auth.client.assertion.location=/var/run/secrets/tokens/kafka
 * }</pre>
 *
 * <h3>Configuration properties</h3>
 * <table>
 *   <tr><th>Property</th><th>Required</th><th>Description</th></tr>
 *   <tr><td>{@value SchemaRegistryClientConfig#BEARER_AUTH_ISSUER_ENDPOINT_URL}</td>
 *       <td>Yes</td><td>OIDC token endpoint URL</td></tr>
 *   <tr><td>{@value CLIENT_ASSERTION_LOCATION_CONFIG}</td>
 *       <td>Yes</td><td>Path to the JWT file used as the {@code client_assertion}</td></tr>
 *   <tr><td>{@value SchemaRegistryClientConfig#BEARER_AUTH_CLIENT_ID}</td>
 *       <td>No</td><td>Optional {@code client_id} to include in the token request</td></tr>
 *   <tr><td>{@value SchemaRegistryClientConfig#BEARER_AUTH_CACHE_EXPIRY_BUFFER_SECONDS}</td>
 *       <td>No</td><td>Seconds before expiry to refresh (default: 300)</td></tr>
 * </table>
 */
public class JwtAssertionBearerAuthCredentialProvider implements BearerAuthCredentialProvider {

    /**
     * Value for {@code bearer.auth.credentials.source} that selects this provider via
     * {@link java.util.ServiceLoader}.
     */
    public static final String ALIAS = "JWT_ASSERTION";

    /**
     * Path to the file containing the JWT used as the {@code client_assertion} in the
     * token request. The file is re-read on every token refresh so credential rotation
     * (e.g. Kubernetes projected ServiceAccount token rotation) is handled transparently.
     */
    public static final String CLIENT_ASSERTION_LOCATION_CONFIG =
            "bearer.auth.client.assertion.location";

    private CachedOauthTokenRetriever tokenRetriever;

    @Override
    public String alias() {
        return ALIAS;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        String tokenEndpoint = requireString(configs,
                SchemaRegistryClientConfig.BEARER_AUTH_ISSUER_ENDPOINT_URL);
        String assertionLocation = requireString(configs, CLIENT_ASSERTION_LOCATION_CONFIG);
        String clientId = optionalString(configs, SchemaRegistryClientConfig.BEARER_AUTH_CLIENT_ID);

        KeycloakFederatedTokenRetriever retriever = new KeycloakFederatedTokenRetriever(
                tokenEndpoint,
                assertionLocation,
                clientId,
                null,
                SaslConfigs.DEFAULT_SASL_LOGIN_RETRY_BACKOFF_MS,
                SaslConfigs.DEFAULT_SASL_LOGIN_RETRY_BACKOFF_MAX_MS,
                null,
                null);

        // ClientJwtValidator.configure() requires scope/sub claim names to be present in the
        // config map. Inject SR-level defaults so callers don't have to set SASL-level keys.
        Map<String, Object> validatorConfigs = new java.util.HashMap<>(configs);
        validatorConfigs.putIfAbsent(SaslConfigs.SASL_OAUTHBEARER_SCOPE_CLAIM_NAME,
                SchemaRegistryClientConfig.getBearerAuthScopeClaimName(configs));
        validatorConfigs.putIfAbsent(SaslConfigs.SASL_OAUTHBEARER_SUB_CLAIM_NAME,
                SchemaRegistryClientConfig.getBearerAuthSubClaimName(configs));
        ClientJwtValidator validator = new ClientJwtValidator();
        validator.configure(validatorConfigs, "OAUTHBEARER", List.of());

        short cacheBuffer = SchemaRegistryClientConfig.getBearerAuthCacheExpiryBufferSeconds(configs);
        tokenRetriever = new CachedOauthTokenRetriever();
        tokenRetriever.configure(retriever, validator, new OauthTokenCache(cacheBuffer));
    }

    @Override
    public String getBearerToken(URL url) {
        return tokenRetriever.getToken();
    }

    /** Package-visible: injects a retriever directly, for unit testing. */
    void initForTesting(CachedOauthTokenRetriever retriever) {
        this.tokenRetriever = retriever;
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
}
