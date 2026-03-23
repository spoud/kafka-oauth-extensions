package io.spoud.oauth;

import static org.apache.kafka.common.config.SaslConfigs.*;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.security.auth.SaslExtensions;
import org.apache.kafka.common.security.auth.SaslExtensionsCallback;
import org.apache.kafka.common.security.oauthbearer.ClientJwtValidator;
import org.apache.kafka.common.security.oauthbearer.JwtRetriever;
import org.apache.kafka.common.security.oauthbearer.JwtValidator;
import org.apache.kafka.common.security.oauthbearer.JwtValidatorException;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;
import org.apache.kafka.common.security.oauthbearer.internals.OAuthBearerClientInitialResponse;
import org.apache.kafka.common.security.oauthbearer.internals.secured.ConfigurationUtils;
import org.apache.kafka.common.security.oauthbearer.internals.secured.JaasOptionsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocketFactory;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.sasl.SaslException;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AuthenticateCallbackHandler} that implements Kafka SASL/OAUTHBEARER using
 * <em>Keycloak federated client authentication</em> with a projected Kubernetes
 * ServiceAccount token.
 *
 * <p>The flow on every token refresh:
 * <ol>
 *   <li>Read the projected Kubernetes ServiceAccount token from
 *       {@value #K8S_TOKEN_FILE_CONFIG}.</li>
 *   <li>POST to the Keycloak token endpoint at {@value #TOKEN_ENDPOINT_URL_CONFIG} with
 *       {@code grant_type=client_credentials} and the SA token as a JWT bearer
 *       {@code client_assertion}.</li>
 *   <li>Extract the short-lived Keycloak access token and hand it to Kafka's
 *       OAUTHBEARER authentication layer.</li>
 * </ol>
 *
 * <p>No static client secret is required. Kafka's built-in refresh logic (controlled by
 * {@code sasl.login.refresh.*} properties) triggers periodic re-authentication before the
 * token expires.
 *
 * <h3>Minimal Kafka client configuration</h3>
 * <pre>{@code
 * security.protocol=SASL_SSL
 * sasl.mechanism=OAUTHBEARER
 * sasl.login.callback.handler.class=io.spoud.oauth.KeycloakFederatedLoginCallbackHandler
 * oauth.federated.token.endpoint.url=https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token
 * oauth.federated.k8s.token.file=/var/run/secrets/tokens/kafka
 * sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;
 * }</pre>
 *
 * <p>Optionally, set the Keycloak client ID in the JAAS stanza when Keycloak requires it:
 * <pre>{@code
 * sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required \
 *   clientId="kafka-demo-app";
 * }</pre>
 */
public class KeycloakFederatedLoginCallbackHandler implements AuthenticateCallbackHandler {

    private static final Logger log =
            LoggerFactory.getLogger(KeycloakFederatedLoginCallbackHandler.class);

    /** Kafka property: Keycloak token endpoint URL (required). */
    public static final String TOKEN_ENDPOINT_URL_CONFIG = "oauth.federated.token.endpoint.url";

    /** Kafka property: path to the projected Kubernetes ServiceAccount token file (required). */
    public static final String K8S_TOKEN_FILE_CONFIG = "oauth.federated.k8s.token.file";

    /** Optional JAAS option: Keycloak client ID. Added to the POST body when present. */
    public static final String CLIENT_ID_OPTION = "clientId";

    private static final String EXTENSION_PREFIX = "extension_";

    private Map<String, Object> moduleOptions;
    private JwtRetriever jwtRetriever;
    private JwtValidator jwtValidator;
    private boolean isInitialized = false;

    @Override
    public void configure(Map<String, ?> configs, String saslMechanism,
                          List<AppConfigurationEntry> jaasConfigEntries) {
        moduleOptions = JaasOptionsUtils.getOptions(saslMechanism, jaasConfigEntries);
        JwtRetriever retriever = createJwtRetriever(configs, saslMechanism, moduleOptions);
        JwtValidator validator = createJwtValidator(configs, saslMechanism);
        init(retriever, validator);
    }

    /** Package-visible for testing: injects retriever and validator directly. */
    void init(JwtRetriever jwtRetriever, JwtValidator jwtValidator) {
        this.jwtRetriever = jwtRetriever;
        this.jwtValidator = jwtValidator;
        isInitialized = true;
    }

    /** Package-visible for testing. */
    JwtRetriever getJwtRetriever() {
        return jwtRetriever;
    }

    static JwtRetriever createJwtRetriever(Map<String, ?> configs, String saslMechanism,
                                            Map<String, Object> jaasConfig) {
        ConfigurationUtils cu = new ConfigurationUtils(configs, saslMechanism);

        URL tokenEndpointUrl = cu.validateUrl(TOKEN_ENDPOINT_URL_CONFIG);

        String k8sTokenFile = (String) configs.get(K8S_TOKEN_FILE_CONFIG);
        if (k8sTokenFile == null || k8sTokenFile.isBlank())
            throw new ConfigException(K8S_TOKEN_FILE_CONFIG + " is required and must not be blank");

        JaasOptionsUtils jou = new JaasOptionsUtils(jaasConfig);
        String clientId = jou.validateString(CLIENT_ID_OPTION, false);

        SSLSocketFactory sslSocketFactory = null;
        if (jou.shouldCreateSSLSocketFactory(tokenEndpointUrl))
            sslSocketFactory = jou.createSSLSocketFactory();

        long retryBackoffMs = Optional.ofNullable(cu.validateLong(SASL_LOGIN_RETRY_BACKOFF_MS, false))
                .orElse(DEFAULT_SASL_LOGIN_RETRY_BACKOFF_MAX_MS);
        long retryBackoffMaxMs = Optional.ofNullable(cu.validateLong(SASL_LOGIN_RETRY_BACKOFF_MAX_MS, false))
                .orElse(DEFAULT_SASL_LOGIN_RETRY_BACKOFF_MAX_MS);

        log.debug("Creating KeycloakFederatedTokenRetriever: endpoint={}, k8sTokenFile={}",
                tokenEndpointUrl, k8sTokenFile);

        return new KeycloakFederatedTokenRetriever(
                tokenEndpointUrl.toString(),
                k8sTokenFile,
                clientId,
                sslSocketFactory,
                retryBackoffMs,
                retryBackoffMaxMs,
                cu.validateInteger(SASL_LOGIN_CONNECT_TIMEOUT_MS, false),
                cu.validateInteger(SASL_LOGIN_READ_TIMEOUT_MS, false));
    }

    private static JwtValidator createJwtValidator(Map<String, ?> configs, String saslMechanism) {
        JwtValidator validator = new ClientJwtValidator();
        validator.configure(configs, saslMechanism, List.of());
        return validator;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        checkInitialized();

        for (Callback callback : callbacks) {
            if (callback instanceof OAuthBearerTokenCallback) {
                handleTokenCallback((OAuthBearerTokenCallback) callback);
            } else if (callback instanceof SaslExtensionsCallback) {
                handleExtensionsCallback((SaslExtensionsCallback) callback);
            } else {
                throw new UnsupportedCallbackException(callback);
            }
        }
    }

    @Override
    public void close() {
        if (jwtRetriever != null) {
            try {
                jwtRetriever.close();
            } catch (IOException e) {
                log.warn("Encountered error when closing JwtRetriever", e);
            }
        }
    }

    private void handleTokenCallback(OAuthBearerTokenCallback callback) throws IOException {
        checkInitialized();
        String accessToken = jwtRetriever.retrieve();

        try {
            OAuthBearerToken token = jwtValidator.validate(accessToken);
            callback.token(token);
        } catch (JwtValidatorException e) {
            log.warn(e.getMessage(), e);
            callback.error("invalid_token", e.getMessage(), null);
        }
    }

    private void handleExtensionsCallback(SaslExtensionsCallback callback) {
        checkInitialized();

        Map<String, String> extensions = new HashMap<>();
        for (Map.Entry<String, Object> entry : moduleOptions.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(EXTENSION_PREFIX)) continue;
            Object valueRaw = entry.getValue();
            String value = valueRaw instanceof String ? (String) valueRaw : String.valueOf(valueRaw);
            extensions.put(key.substring(EXTENSION_PREFIX.length()), value);
        }

        SaslExtensions saslExtensions = new SaslExtensions(extensions);
        try {
            OAuthBearerClientInitialResponse.validateExtensions(saslExtensions);
        } catch (SaslException e) {
            throw new ConfigException(e.getMessage());
        }
        callback.extensions(saslExtensions);
    }

    private void checkInitialized() {
        if (!isInitialized)
            throw new IllegalStateException(String.format(
                    "To use %s, first call the configure or init method",
                    getClass().getSimpleName()));
    }
}
