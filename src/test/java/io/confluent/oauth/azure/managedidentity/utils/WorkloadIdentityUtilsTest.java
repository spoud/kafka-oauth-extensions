package io.confluent.oauth.azure.managedidentity.utils;

import com.azure.identity.WorkloadIdentityCredential;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadIdentityUtilsTest {

    @Test
    void getTenantIdThrowsWhenMissing() {
        Map<String, String> env = new HashMap<>();
        WorkloadIdentityUtils.setEnvProvider(env::get);
        try {
            Exception ex = assertThrows(WorkloadIdentityKafkaClientOAuthBearerAuthenticationException.class, WorkloadIdentityUtils::getTenantId);
            assertTrue(ex.getMessage().contains("Missing environment variable"));
        } finally {
            WorkloadIdentityUtils.resetEnvProvider();
        }
    }

    @Test
    void getClientIdThrowsWhenMissing() {
        Map<String, String> env = new HashMap<>();
        WorkloadIdentityUtils.setEnvProvider(env::get);
        try {
            Exception ex = assertThrows(WorkloadIdentityKafkaClientOAuthBearerAuthenticationException.class, WorkloadIdentityUtils::getClientId);
            assertTrue(ex.getMessage().contains("Missing environment variable"));
        } finally {
            WorkloadIdentityUtils.resetEnvProvider();
        }
    }

    @Test
    void createWorkloadIdentityCredentialFromEnvironmentThrowsWhenMissingEnv() {
        Map<String, String> env = new HashMap<>();
        WorkloadIdentityUtils.setEnvProvider(env::get);
        try {
            Exception ex = assertThrows(WorkloadIdentityKafkaClientOAuthBearerAuthenticationException.class, WorkloadIdentityUtils::createWorkloadIdentityCredentialFromEnvironment);
            assertTrue(ex.getMessage().contains("AZURE_FEDERATED_TOKEN_FILE"));
            // Set token file, unset authority
            env.put(WorkloadIdentityUtils.AZURE_AD_WORKLOAD_IDENTITY_MUTATING_ADMISSION_WEBHOOK_ENV_FEDERATED_TOKEN_FILE, "tokenfile");
            ex = assertThrows(WorkloadIdentityKafkaClientOAuthBearerAuthenticationException.class, WorkloadIdentityUtils::createWorkloadIdentityCredentialFromEnvironment);
            assertTrue(ex.getMessage().contains("Missing environment variable AZURE_AUTHORITY_HOST"));
        } finally {
            WorkloadIdentityUtils.resetEnvProvider();
        }
    }

    @Test
    void createWorkloadIdentityCredentialFromEnvironmentReturnsCredential() {
        Map<String, String> env = new HashMap<>();
        env.put(WorkloadIdentityUtils.AZURE_AD_WORKLOAD_IDENTITY_MUTATING_ADMISSION_WEBHOOK_ENV_FEDERATED_TOKEN_FILE, "/tmp/tokenfile");
        env.put(WorkloadIdentityUtils.AZURE_AD_WORKLOAD_IDENTITY_MUTATING_ADMISSION_WEBHOOK_ENV_AUTHORITY_HOST, "https://login.microsoftonline.com/");
        env.put(WorkloadIdentityUtils.AZURE_AD_WORKLOAD_IDENTITY_MUTATING_ADMISSION_WEBHOOK_ENV_TENANT_ID, "tenant");
        env.put(WorkloadIdentityUtils.AZURE_AD_WORKLOAD_IDENTITY_MUTATING_ADMISSION_WEBHOOK_ENV_CLIENT_ID, "client");
        WorkloadIdentityUtils.setEnvProvider(env::get);
        try {
            WorkloadIdentityCredential credential = WorkloadIdentityUtils.createWorkloadIdentityCredentialFromEnvironment();
            assertNotNull(credential);
        } finally {
            WorkloadIdentityUtils.resetEnvProvider();
        }
    }

    @Test
    void createTokenRequestContextFromEnvironmentReturnsContext() {
        Map<String, String> env = new HashMap<>();
        env.put(WorkloadIdentityUtils.AZURE_AD_WORKLOAD_IDENTITY_MUTATING_ADMISSION_WEBHOOK_ENV_TENANT_ID, "tenant");
        env.put(WorkloadIdentityUtils.AZURE_AD_WORKLOAD_IDENTITY_MUTATING_ADMISSION_WEBHOOK_ENV_CLIENT_ID, "client");
        WorkloadIdentityUtils.setEnvProvider(env::get);
        try {
            var ctx = WorkloadIdentityUtils.createTokenRequestContextFromEnvironment(null);
            assertNotNull(ctx);
            assertEquals("tenant", ctx.getTenantId());
            assertTrue(ctx.getScopes().contains("client/.default"));
            // With explicit scope
            ctx = WorkloadIdentityUtils.createTokenRequestContextFromEnvironment("myscope");
            assertTrue(ctx.getScopes().contains("myscope"));
        } finally {
            WorkloadIdentityUtils.resetEnvProvider();
        }
    }

    @Test
    void createTokenRequestContextFromEnvironmentThrowsWhenTenantIdMissing() {
        Map<String, String> env = new HashMap<>();
        env.put(WorkloadIdentityUtils.AZURE_AD_WORKLOAD_IDENTITY_MUTATING_ADMISSION_WEBHOOK_ENV_CLIENT_ID, "client");
        WorkloadIdentityUtils.setEnvProvider(env::get);
        try {
            Exception ex = assertThrows(WorkloadIdentityKafkaClientOAuthBearerAuthenticationException.class, () ->
                WorkloadIdentityUtils.createTokenRequestContextFromEnvironment(null));
            assertTrue(ex.getMessage().contains("AZURE_TENANT_ID"));
        } finally {
            WorkloadIdentityUtils.resetEnvProvider();
        }
    }

    // Add more tests for other static methods as needed, including positive cases if you want to set env vars
}
