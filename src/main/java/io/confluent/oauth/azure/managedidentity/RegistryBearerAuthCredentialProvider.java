package io.confluent.oauth.azure.managedidentity;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig;
import io.confluent.kafka.schemaregistry.client.security.bearerauth.BearerAuthCredentialProvider;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.security.JaasContext;
import org.apache.kafka.common.security.oauthbearer.ClientJwtValidator;
import org.apache.kafka.common.security.oauthbearer.JwtRetriever;
import org.apache.kafka.common.security.oauthbearer.JwtRetrieverException;
import org.apache.kafka.common.security.oauthbearer.JwtValidator;
import org.apache.kafka.common.security.oauthbearer.JwtValidatorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.AppConfigurationEntry;

import static io.confluent.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler.createJwtRetriever;
import org.apache.kafka.common.security.oauthbearer.internals.secured.ConfigurationUtils;
import org.apache.kafka.common.security.oauthbearer.internals.secured.JaasOptionsUtils;

public class RegistryBearerAuthCredentialProvider implements BearerAuthCredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(RegistryBearerAuthCredentialProvider.class);
    public static final String SASL_IDENTITY_POOL_CONFIG = "extension_identityPoolId";

    private String targetSchemaRegistry;
    private String targetIdentityPoolId;
    private Map<String, Object> moduleOptions;

    private JwtRetriever jwtRetriever;
    private JwtValidator jwtValidator;
    private boolean isInitialized;


    @Override
    public void configure(Map<String, ?> configs) {
        // from SaslOauthCredentialProvider
        Map<String, Object> updatedConfigs = getConfigsForJaasUtil(configs);
        JaasContext jaasContext = JaasContext.loadClientContext(updatedConfigs);
        List<AppConfigurationEntry> appConfigurationEntries = jaasContext.configurationEntries();
        Map<String, ?> jaasconfig;
        if (Objects.requireNonNull(appConfigurationEntries).size() == 1
                && appConfigurationEntries.get(0) != null) {
            jaasconfig = Collections.unmodifiableMap(
                    ((AppConfigurationEntry) appConfigurationEntries.get(0)).getOptions());
        } else {
            throw new ConfigException(
                    String.format("Must supply exactly 1 non-null JAAS mechanism configuration (size was %d)",
                            appConfigurationEntries.size()));
        }

        // make sure we have scope and sub set
        Map<String, Object> myConfigs = new HashMap<>(configs);
        myConfigs.put(SaslConfigs.SASL_OAUTHBEARER_SCOPE_CLAIM_NAME, "scope");
        myConfigs.put(SaslConfigs.SASL_OAUTHBEARER_SUB_CLAIM_NAME, "sub");


        ConfigurationUtils cu = new ConfigurationUtils(myConfigs);
        JaasOptionsUtils jou = new JaasOptionsUtils((Map<String, Object>) jaasconfig);

        targetSchemaRegistry = cu.validateString(
                SchemaRegistryClientConfig.BEARER_AUTH_LOGICAL_CLUSTER, false);

        // if the schema registry oauth configs are set it is given higher preference
        targetIdentityPoolId = cu.get(SchemaRegistryClientConfig.BEARER_AUTH_IDENTITY_POOL_ID) != null
                ? cu.validateString(SchemaRegistryClientConfig.BEARER_AUTH_IDENTITY_POOL_ID)
                : jou.validateString(SASL_IDENTITY_POOL_CONFIG, false);

        String saslMechanism = cu.validateString(SaslConfigs.SASL_MECHANISM);
        moduleOptions = JaasOptionsUtils.getOptions(saslMechanism, appConfigurationEntries);

        JwtRetriever jwtRetriever = createJwtRetriever(myConfigs, saslMechanism, moduleOptions);
        JwtValidator jwtValidator = createJwtValidator(myConfigs, saslMechanism);

        init(jwtRetriever, jwtValidator);
    }

    /*
     * Package-visible for testing.
     */

    void init(JwtRetriever jwtRetriever, JwtValidator jwtValidator) {
        this.jwtRetriever = jwtRetriever;
        this.jwtValidator = jwtValidator;
        isInitialized = true;
    }

    private static JwtValidator createJwtValidator(Map<String, ?> configs, String saslMechanism) {
        JwtValidator jwtValidator = new ClientJwtValidator();
        jwtValidator.configure(configs, saslMechanism, List.of());
        return jwtValidator;
    }

    @Override
    public String getBearerToken(URL url) {
        if (!isInitialized)
            return "";

        try {
            String accessToken = jwtRetriever.retrieve();
            jwtValidator.validate(accessToken);
            return accessToken;
        } catch (JwtRetrieverException | JwtValidatorException e) {
            log.warn(e.getMessage(), e);
            return "";
        }
    }

    @Override
    public String getTargetIdentityPoolId() {
        return targetIdentityPoolId;
    }

    @Override
    public String getTargetSchemaRegistry() {
        return targetSchemaRegistry;
    }

    // from SaslOauthCredentialProvider
    Map<String, Object> getConfigsForJaasUtil(Map<String, ?> configs) {
        Map<String, Object> updatedConfigs = new HashMap<>(configs);
        if (updatedConfigs.containsKey(SaslConfigs.SASL_JAAS_CONFIG)) {
            Object saslJaasConfig = updatedConfigs.get(SaslConfigs.SASL_JAAS_CONFIG);
            if (saslJaasConfig instanceof String) {
                updatedConfigs.put(SaslConfigs.SASL_JAAS_CONFIG, new Password((String) saslJaasConfig));
            }
        }
        return updatedConfigs;
    }

}
