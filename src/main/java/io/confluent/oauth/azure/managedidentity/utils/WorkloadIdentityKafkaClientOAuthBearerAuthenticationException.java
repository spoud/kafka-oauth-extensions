package io.confluent.oauth.azure.managedidentity.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @deprecated Use {@link io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityKafkaClientOAuthBearerAuthenticationException} instead.
 *             This class will be removed in the 1.6-SNAPSHOT release.
 */
@Deprecated
public class WorkloadIdentityKafkaClientOAuthBearerAuthenticationException
        extends io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityKafkaClientOAuthBearerAuthenticationException {

    private static final Logger log = LoggerFactory.getLogger(WorkloadIdentityKafkaClientOAuthBearerAuthenticationException.class);

    public WorkloadIdentityKafkaClientOAuthBearerAuthenticationException(String message) {
        super(message);
        log.warn("io.confluent.oauth.azure.managedidentity.utils.WorkloadIdentityKafkaClientOAuthBearerAuthenticationException is deprecated; migrate to io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityKafkaClientOAuthBearerAuthenticationException");
    }
}
