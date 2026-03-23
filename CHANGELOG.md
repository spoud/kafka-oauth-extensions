
# 1.0-SNAPSHOT and earlier

Initial version, client code adapted from Apache Kafka 3.3.1.  Allows JWT
OAuth tokens to be fetch from Azure IMDS via HTTP GET.

# 1.1-SNAPSHOT

Updated to use Apache Kafka 3.6.1.

=> over at https://github.com/confluentinc/confluent-oauth-extensions


# 1.2-SNAPSHOT

Migrated to spoud github repository https://github.com/spoud/kafka-oauth-extensions

* workload identity support
* user account support with azure cli
* support for schema registry
* introduced github package


# 1.3-SNAPSHOT

* Updated to Apache Kafka 4
* Testing
* Dependabot

# 1.4-SNAPSHOT

* CI and dependency bumps

# 1.5-SNAPSHOT

* Keycloak federated client authentication with Kubernetes ServiceAccount tokens
  * New `io.spoud.oauth.KeycloakFederatedLoginCallbackHandler` (canonical location)
  * Reads the projected K8s ServiceAccount token from a configurable file and exchanges it for a
    Keycloak access token via `grant_type=client_credentials` + JWT bearer `client_assertion`
  * No static client secret required
  * Re-reads the token file on every refresh so K8s token rotation is handled transparently
  * Config keys: `oauth.federated.token.endpoint.url`, `oauth.federated.k8s.token.file`;
    optional JAAS option `clientId`
* Package reorganization: all Azure/Managed Identity classes moved to canonical `io.spoud.oauth.*` location
  * `io.spoud.oauth.HttpAccessTokenRetriever` (canonical)
  * `io.spoud.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler` (canonical)
  * `io.spoud.oauth.azure.managedidentity.RegistryBearerAuthCredentialProvider` (canonical)
  * `io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityUtils` (canonical)
  * `io.spoud.oauth.azure.managedidentity.utils.WorkloadIdentityKafkaClientOAuthBearerAuthenticationException` (canonical)
  * All `io.confluent.oauth.*` counterparts kept as deprecated proxies that log a warning on use;
    will be removed in 1.6-SNAPSHOT

# 1.6-SNAPSHOT (planned)

* Remove all deprecated `io.confluent.oauth.*` proxy classes