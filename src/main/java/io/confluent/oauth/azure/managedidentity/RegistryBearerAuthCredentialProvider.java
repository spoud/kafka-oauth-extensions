package io.confluent.oauth.azure.managedidentity;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @deprecated Use {@link io.spoud.oauth.azure.managedidentity.RegistryBearerAuthCredentialProvider} instead.
 *             This class will be removed in the 1.6-SNAPSHOT release.
 */
@Deprecated
public class RegistryBearerAuthCredentialProvider
        extends io.spoud.oauth.azure.managedidentity.RegistryBearerAuthCredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(RegistryBearerAuthCredentialProvider.class);

    @Override
    public void configure(Map<String, ?> configs) {
        log.warn("io.confluent.oauth.azure.managedidentity.RegistryBearerAuthCredentialProvider is deprecated; migrate to io.spoud.oauth.azure.managedidentity.RegistryBearerAuthCredentialProvider");
        super.configure(configs);
    }
}
