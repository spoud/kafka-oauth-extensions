
# 1.8-SNAPSHOT

* Two new `BearerAuthCredentialProvider` implementations for Schema Registry authentication via
  RFC 7523 JWT Bearer client assertion — filling a gap in the built-in credential sources, none
  of which support JWT assertion (`OAUTHBEARER` and `SASL_OAUTHBEARER_INHERIT` only support
  `client_id` + `client_secret`; `STATIC_TOKEN` requires a long-lived token). On JDK 24+
  (JEP 486), `Subject.getSubject(AccessControlContext)` throws `UnsupportedOperationException`
  because the Security Manager is no longer functional, so any Subject-inheritance approach
  also fails at runtime on modern JDKs.
  * `io.spoud.oauth.KeycloakFederatedRegistryBearerAuthCredentialProvider` (`KEYCLOAK_FEDERATED`) —
    uses the `oauth.federated.*` config namespace from `KeycloakFederatedLoginCallbackHandler`;
    best when Kafka and Schema Registry auth share the same property file; own 30 s cache with
    best-effort stale-token fallback on refresh failure
  * `io.spoud.oauth.JwtAssertionBearerAuthCredentialProvider` (`JWT_ASSERTION`) —
    uses the standard `bearer.auth.*` namespace from `SchemaRegistryClientConfig`; delegates
    caching and claim validation to the SR client's own `CachedOauthTokenRetriever`; configurable
    expiry buffer (default 300 s); propagates exceptions on refresh failure
  * Both registered via ServiceLoader; both re-read the assertion file on each refresh for
    transparent Kubernetes projected token rotation
* Shadow jar (`-all`): service descriptor for `BearerAuthCredentialProvider` now lists all
  built-in Confluent providers (`STATIC_TOKEN`, `OAUTHBEARER`, `SASL_OAUTHBEARER_INHERIT`,
  `CUSTOM`) alongside the new providers, so the shadow jar works standalone
* Dependency bumps: kafka-clients 4.3.1, kafka-schema-registry-client 8.3.0,
  JUnit 6.1.1, Shadow plugin 9.4.3, Gradle 9.6.1, actions/checkout v7

# 1.7-SNAPSHOT

* Removed all deprecated `io.confluent.oauth.*` proxy classes — migrate to the canonical `io.spoud.oauth.*` equivalents

# 1.6-SNAPSHOT

* Packaging fixes for Confluent Platform compatibility
  * Publish the thin jar as the primary artifact for Kafka/Confluent runtime classpaths
  * Keep the shadow jar as a secondary artifact while excluding bundled Kafka classes to avoid classpath conflicts
  * Release assets now include both the thin jar and the shadow jar
* Add Docker/Compose-based compatibility smoke tests for Apache Kafka OSS and Confluent Platform CLI startup
* Add a real Keycloak federated client-auth integration test using a no-mocking `kind` + Keycloak + Kafka harness
  * Mint a real Kubernetes ServiceAccount token with `kubectl create token`
  * Validate real broker operations from both Apache Kafka OSS and Confluent Platform CLI containers
* Deprecated `io.confluent.oauth.*` proxy classes remain available in 1.6-SNAPSHOT
  * Planned removal moved to 1.7-SNAPSHOT

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

# 1.4-SNAPSHOT

* CI and dependency bumps

# 1.3-SNAPSHOT

* Updated to Apache Kafka 4
* Testing
* Dependabot

# 1.2-SNAPSHOT

Migrated to spoud github repository https://github.com/spoud/kafka-oauth-extensions

* workload identity support
* user account support with azure cli
* support for schema registry
* introduced github package

# 1.1-SNAPSHOT

Updated to use Apache Kafka 3.6.1.

=> over at https://github.com/confluentinc/confluent-oauth-extensions


# 1.0-SNAPSHOT and earlier

Initial version, client code adapted from Apache Kafka 3.3.1.  Allows JWT
OAuth tokens to be fetch from Azure IMDS via HTTP GET.
