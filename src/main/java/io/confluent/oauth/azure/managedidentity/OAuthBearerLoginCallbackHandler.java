package io.confluent.oauth.azure.managedidentity;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import javax.security.auth.login.AppConfigurationEntry;
import org.apache.kafka.common.security.oauthbearer.JwtRetriever;
import org.apache.kafka.common.security.oauthbearer.internals.secured.ConfigurationUtils;
import org.apache.kafka.common.security.oauthbearer.internals.secured.JaasOptionsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @deprecated Use {@link io.spoud.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler} instead.
 *             This class will be removed in the 1.6-SNAPSHOT release.
 */
@Deprecated
public class OAuthBearerLoginCallbackHandler
        extends io.spoud.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthBearerLoginCallbackHandler.class);

    @Override
    public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {
        log.warn("io.confluent.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler is deprecated; migrate to io.spoud.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler");
        super.configure(configs, saslMechanism, jaasConfigEntries);
    }

    @Deprecated
    public static JwtRetriever createJwtRetriever(Map<String, ?> configs,
        String saslMechanism,
        Map<String, Object> jaasConfig) {
        return io.spoud.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler
                .createJwtRetriever(configs, saslMechanism, jaasConfig);
    }
}
