package io.confluent.oauth.azure.managedidentity.utils;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.WorkloadIdentityCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @deprecated Use {@link io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils} instead.
 *             This class will be removed in the 1.6-SNAPSHOT release.
 */
@Deprecated
public class WorkloadIdentityUtils
        extends io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils {

    private static final Logger log = LoggerFactory.getLogger(WorkloadIdentityUtils.class);

    @Deprecated
    public static String getTenantId() {
        log.warn("io.confluent.oauth.azure.managedidentity.utils.WorkloadIdentityUtils is deprecated; migrate to io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils");
        return io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils.getTenantId();
    }

    @Deprecated
    public static String getClientId() {
        log.warn("io.confluent.oauth.azure.managedidentity.utils.WorkloadIdentityUtils is deprecated; migrate to io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils");
        return io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils.getClientId();
    }

    @Deprecated
    public static WorkloadIdentityCredential createWorkloadIdentityCredentialFromEnvironment() {
        log.warn("io.confluent.oauth.azure.managedidentity.utils.WorkloadIdentityUtils is deprecated; migrate to io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils");
        return io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils.createWorkloadIdentityCredentialFromEnvironment();
    }

    @Deprecated
    public static TokenRequestContext createTokenRequestContextFromEnvironment(String scope) {
        log.warn("io.confluent.oauth.azure.managedidentity.utils.WorkloadIdentityUtils is deprecated; migrate to io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils");
        return io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils.createTokenRequestContextFromEnvironment(scope);
    }
}
