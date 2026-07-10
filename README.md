# SPOUD kafka-oauth-extensions

This is a fork of the Confluent OAuth extensions for Apache Kafka, with additional support for Azure Managed Identities and Workload Identities.

Apache Kafka client library providing additional integrations relating to OAuth/OIDC integrations with Confluent Cloud and Apache Kafka.

## Artifacts and classpath usage

Use the artifact that matches how you run the client:

- **Confluent Platform CLI tools or any environment that already ships Kafka classes:** use the thin jar `kafka-oauth-extensions-<version>.jar`.
- **Standalone/manual classpath setup:** the release also includes `kafka-oauth-extensions-<version>-all.jar`, but it intentionally excludes Kafka classes so the runtime's Kafka distribution stays authoritative.

For Maven or Gradle consumption, add GitHub Packages for this repository and depend on the thin jar as the default artifact:

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/spoud/kafka-oauth-extensions")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation "io.spoud.kafka.oauth:kafka-oauth-extensions:<version>"

    // Optional secondary artifact if you specifically want the shadow jar.
    runtimeOnly "io.spoud.kafka.oauth:extensions-shadow:<version>"
}
```

If you consume from GitHub Packages outside GitHub Actions, provide credentials that can read packages for this repository. If you only need the CLI/classpath jar, you can download the release asset directly instead of configuring Maven/Gradle.

## Compatibility smoke tests

The repository includes a Docker/Compose-based smoke test that checks whether the thin jar starts cleanly on both:

- Apache Kafka OSS tooling
- Confluent Platform tooling

It is intentionally a startup/classpath compatibility check, not a full broker integration test. The harness mounts the built thin jar into each container, puts it on `CLASSPATH`, runs the Kafka CLI, and fails on linkage errors such as `NoSuchMethodError` or `NoClassDefFoundError`.

Run it locally after building the project:

```bash
./gradlew build
bash ./scripts/compatibility-smoke.sh
```

By default the script uses the thin jar from `build/libs`. You can also pass an explicit jar path:

```bash
bash ./scripts/compatibility-smoke.sh build/libs/kafka-oauth-extensions-<version>.jar
```

The script also fails fast if the selected jar bundles Kafka runtime classes (`org/apache/kafka/**` or `kafka/**`), which protects against the broken pre-fix `1.5-SNAPSHOT-all.jar` packaging.

## Real Keycloak + Kafka integration test

The repository includes a no-mocking end-to-end integration harness for the federated client-authentication flow.

It uses:

- a real `kind` Kubernetes cluster
- a real ServiceAccount token minted with `kubectl create token`
- a real Keycloak 26.5.5 container with federated JWT client authentication enabled
- a real Apache Kafka 4.2.0 broker configured with SASL/OAUTHBEARER validation against Keycloak JWKS
- both Apache Kafka OSS and Confluent Platform CLI containers performing actual topic operations
- the actual `io.spoud.oauth.KeycloakFederatedLoginCallbackHandler` from this repository

The topology keeps the Kubernetes part real: the `kind` API server signs the ServiceAccount token, an HTTPS issuer proxy forwards the live discovery/JWKS endpoints from the cluster, Keycloak exchanges the Kubernetes JWT for a Keycloak access token, and Kafka validates that token on the broker listener.

Run it locally with:

```bash
bash ./scripts/keycloak-integration-smoke.sh
```

Prerequisites:

- Docker
- `kind`
- `kubectl`
- `keytool` (from the JDK)

By default the script uses the shadow jar from `build/libs` because the manual CLI `CLASSPATH` setup needs this project's transitive dependencies. The existing compatibility smoke remains the thin-jar startup/classpath guard for Apache Kafka OSS and Confluent Platform tooling.

This integration test proves actual broker operations (`create`, `list`, `describe`) succeed through Keycloak-issued OAuth tokens for both client distributions.

## Authenticating to Confluent Cloud via OAuth, using Azure Managed Identities

Example Kafka client config and JAAS config for authenticating to Confluent Cloud using Azure Managed Identities / Pod Identity:

```
bootstrap.servers=pkc-xxxxx.ap-southeast-2.aws.confluent.cloud:9092
security.protocol=SASL_SSL
sasl.oauthbearer.token.endpoint.url=http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https%3A%2F%2Fxxxxxxxx.onmicrosoft.com%2Fxxxxxxxxxx%2Fxxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx&client_id=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxx
sasl.login.callback.handler.class=io.spoud.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler
sasl.mechanism=OAUTHBEARER
sasl.jaas.config= \
	org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required \
		clientId='ignored' \
		clientSecret='ignored' \
		extension_logicalCluster='lkc-xxxxxx' \
		extension_identityPoolId='pool-xxxx';
```

Use Azure K8s Workload Identities:

```
bootstrap.servers=pkc-xxxxx.ap-southeast-2.aws.confluent.cloud:9092
security.protocol=SASL_SSL
sasl.oauthbearer.token.endpoint.url=${AZURE_AUTHORITY_HOST}${AZURE_TENANT_ID}/oauth2/v2.0/token
sasl.login.callback.handler.class=io.spoud.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler
sasl.mechanism=OAUTHBEARER
sasl.jaas.config= \
	org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required \
		clientId='ignored' \
		clientSecret='ignored' \
		useWorkloadIdentity='true' \
        scope='${CONFLUENT_CLOUD_APP_ID}/.default' \
		extension_logicalCluster='lkc-xxxxxx' \
		extension_identityPoolId='pool-xxxx';
```



Use with Schema Registry

example:
```
echo '{"make": "Ford", "model": "Mustang", "price": 10000}' | kafka-avro-console-producer \
  --bootstrap-server <bootstrap>.confluent.cloud:9092 \
  --property schema.registry.url=https://<registry>.confluent.cloud \
  --property bearer.auth.credentials.source='CUSTOM' \
  --property bearer.auth.custom.provider.class=io.spoud.oauth.azure.managedidentity.RegistryBearerAuthCredentialProvider \
  --property bearer.auth.logical.cluster='lsrc-xxxxxx' \
  --producer.config client.properties \
  --reader-config client.properties \
  --topic cars \
  --property value.schema='{"type": "record", "name": "Car", "namespace": "io.spoud.training", "fields": [{"name": "make", "type": "string"}, {"name": "model", "type": "string"}, {"name": "price", "type": "int", "default":  0}]}'
```


Example use with a user account. This might be useful for testing and development, but not recommended for production use.

```properties

```bash

az login --scope ${CONFLUENT_CLOUD_APP_ID}/.default --tenant ${AZURE_TENANT_ID}

# if you see something like this you would need to add Azure CLI to the list of `Authorized client applications` in the Azure AD App Registration
# AADSTS650057: Invalid resource. The client has requested access to a resource which is not listed in the requested permissions in the client's application registration. Client app ID: 04b07795-8ddb-461a-bbee-02f9e1bf7b46(Microsoft Azure CLI). Resource value from request: ${CONFLUENT_CLOUD_APP_ID}. Resource app ID: ${CONFLUENT_CLOUD_APP_ID}. List of valid resources from app registration:...'


cat > /tmp/client.properties <<EOF
security.protocol=SASL_SSL
sasl.oauthbearer.token.endpoint.url=${AZURE_AUTHORITY_HOST}${AZURE_TENANT_ID}/oauth2/v2.0/token

sasl.login.callback.handler.class=io.spoud.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler

sasl.mechanism=OAUTHBEARER
sasl.jaas.config= \
   org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required \
       clientId='ignored' \
       clientSecret='ignored' \
       useUserIdentity='true' \
       useWorkloadIdentity='false' \
       scope='${CONFLUENT_CLOUD_APP_ID}' \
       extension_logicalCluster='lkc-xxxxx' \
       extension_identityPoolId='pool-xxxx';
EOF


echo '{"make": "Ford", "model": "Mustang", "price": 10000}' |kafka-avro-console-producer --bootstrap-server <bootstrap>.confluent.cloud:9092 \
--property schema.registry.url=https://<registry>.confluent.cloud \
--property bearer.auth.credentials.source='CUSTOM' \
--property bearer.auth.custom.provider.class=io.spoud.oauth.azure.managedidentity.RegistryBearerAuthCredentialProvider \
--property bearer.auth.logical.cluster='lsrc-xxxxx' \
--producer.config client.properties --reader-config client.properties --topic cars \
--property value.schema='{"type": "record", "name": "Car", "namespace": "io.spoud.training", "fields": [{"name": "make", "type": "string"}, {"name": "model", "type": "string"}, {"name": "price", "type": "int", "default":  0}]}'

```


## Schema Registry Authentication via JWT Bearer Assertion

Two providers ship in this library for authenticating to a Confluent-compatible Schema Registry
(such as Apicurio Registry) using a Kubernetes ServiceAccount JWT as a client assertion. Both
perform an RFC 7523 JWT Bearer token exchange against Keycloak. They are registered via
`ServiceLoader` and available after adding the jar to your classpath.

| Provider | `bearer.auth.credentials.source` | Config namespace | Cache/validation |
|---|---|---|---|
| `KeycloakFederatedRegistryBearerAuthCredentialProvider` | `KEYCLOAK_FEDERATED` | `oauth.federated.*` | Own cache, 30 s buffer, exp-only, best-effort fallback |
| `JwtAssertionBearerAuthCredentialProvider` | `JWT_ASSERTION` | `bearer.auth.*` | SR client `CachedOauthTokenRetriever`, configurable buffer (default 300 s), `ClientJwtValidator`, exception on failure |

### Why built-in credential sources do not cover this use case

Confluent Schema Registry ships four built-in `bearer.auth.credentials.source` values for OAuth
bearer auth: `STATIC_TOKEN`, `OAUTHBEARER`, `SASL_OAUTHBEARER_INHERIT`, and `CUSTOM`. None
supports RFC 7523 JWT Bearer client assertions:

- **`STATIC_TOKEN`** — requires a long-lived token baked into the configuration; incompatible
  with short-lived Kubernetes projected ServiceAccount tokens.
- **`OAUTHBEARER`** and **`SASL_OAUTHBEARER_INHERIT`** — both fetch a token via
  `client_id` + `client_secret` (HTTP Basic). Neither can use a JWT as the credential to exchange.
- **`CUSTOM`** — loads any `BearerAuthCredentialProvider` by class name. Both providers in this
  library are usable via `CUSTOM` as an alternative to their ServiceLoader aliases (see below).

Additionally, on JDK 24 and later (JEP 486), `Subject.getSubject(AccessControlContext)` always
throws `UnsupportedOperationException` because the Security Manager is no longer functional. Any
approach that relied on inheriting a SASL `Subject` across threads fails at runtime on modern JDKs.

### Using `KEYCLOAK_FEDERATED`

Uses the same `oauth.federated.*` keys as `KeycloakFederatedLoginCallbackHandler`, so no new
values need to be introduced in a pod that already configures the Kafka handler.

```bash
kafka-avro-console-consumer \
  --bootstrap-server broker:9094 \
  --topic my-topic \
  --command-config /tmp/client.properties \
  --formatter-property schema.registry.url=https://apicurio.example.com/apis/ccompat/v7 \
  --formatter-property bearer.auth.credentials.source=KEYCLOAK_FEDERATED \
  --formatter-property oauth.federated.token.endpoint.url=https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token \
  --formatter-property oauth.federated.k8s.token.file=/var/run/secrets/tokens/kafka
```

| Property | Required | Description |
|---|---|---|
| `oauth.federated.token.endpoint.url` | Yes | Keycloak token endpoint URL |
| `oauth.federated.k8s.token.file` | Yes | Path to the projected Kubernetes ServiceAccount token file |
| `clientId` | No | Optional `client_id` to include in the token request |

### Using `JWT_ASSERTION`

Uses the standard `bearer.auth.*` namespace from `SchemaRegistryClientConfig`. Use this when
configuring Schema Registry auth in isolation — for example when the Schema Registry and Kafka
broker authenticate against different Keycloak realms, or when the Schema Registry client is
configured independently of the Kafka consumer.

```bash
kafka-avro-console-consumer \
  --bootstrap-server broker:9094 \
  --topic my-topic \
  --command-config /tmp/client.properties \
  --formatter-property schema.registry.url=https://apicurio.example.com/apis/ccompat/v7 \
  --formatter-property bearer.auth.credentials.source=JWT_ASSERTION \
  --formatter-property bearer.auth.issuer.endpoint.url=https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token \
  --formatter-property bearer.auth.client.assertion.location=/var/run/secrets/tokens/kafka
```

| Property | Required | Description |
|---|---|---|
| `bearer.auth.issuer.endpoint.url` | Yes | Keycloak token endpoint URL |
| `bearer.auth.client.assertion.location` | Yes | Path to the JWT file used as the `client_assertion` |
| `bearer.auth.client.id` | No | Optional `client_id` to include in the token request |
| `bearer.auth.cache.expiry.buffer.seconds` | No | Seconds before expiry to trigger a refresh (default: 300) |

### CUSTOM alternative

Both providers can also be loaded by class name, without relying on the ServiceLoader alias:

```
bearer.auth.credentials.source=CUSTOM
bearer.auth.custom.provider.class=io.spoud.oauth.KeycloakFederatedRegistryBearerAuthCredentialProvider
# or:
bearer.auth.custom.provider.class=io.spoud.oauth.JwtAssertionBearerAuthCredentialProvider
```

### Token caching and failure behavior

`KEYCLOAK_FEDERATED` keeps its own in-memory cache with a fixed 30-second expiry buffer. It only
parses the `exp` claim for cache decisions and does not validate the token further. On refresh
failure it logs a warning and returns the stale cached token as a best-effort fallback; if no
cached token exists, it returns an empty string (resulting in a 401 from the registry).

`JWT_ASSERTION` delegates caching to the SR client's own `CachedOauthTokenRetriever`. The expiry
buffer is controlled by `bearer.auth.cache.expiry.buffer.seconds` (default 300 s). Tokens are
validated via `ClientJwtValidator` before being cached. On refresh failure,
`SchemaRegistryOauthTokenRetrieverException` is propagated to the caller.

Both providers re-read the assertion token file on every refresh, so Kubernetes projected token
rotation is handled transparently.

---

## Keycloak Federated Client Authentication (Kubernetes ServiceAccount)

Use `KeycloakFederatedLoginCallbackHandler` when your Kafka client runs on Kubernetes and you
want to authenticate to Kafka via Keycloak **without a static client secret**.

The handler reads the projected Kubernetes ServiceAccount token from a file, sends it to
Keycloak as a JWT bearer `client_assertion`, and exchanges it for a short-lived Keycloak access
token. Kafka's built-in OAUTHBEARER refresh mechanism calls the handler again before the token
expires, and the handler re-reads the token file each time so K8s token rotation is transparent.

### Required Kafka client properties

```properties
security.protocol=SASL_SSL
sasl.mechanism=OAUTHBEARER
sasl.login.callback.handler.class=io.spoud.oauth.KeycloakFederatedLoginCallbackHandler

# Keycloak token endpoint for your realm
oauth.federated.token.endpoint.url=https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token

# Path to the projected Kubernetes ServiceAccount token inside the pod
oauth.federated.k8s.token.file=/var/run/secrets/tokens/kafka

sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;

# Kafka 4+: add the Keycloak URL to the OAuth allowed-URL list
# (set as JVM system property or KAFKA_OPTS environment variable)
# -Dorg.apache.kafka.sasl.oauthbearer.allowed.urls=https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token
```

### Optional: `client_id` request parameter

For the federated Kubernetes ServiceAccount flow, you usually **omit** `clientId`.
Keycloak can resolve the client from the JWT issuer + subject.

If you do need to send a `client_id`, Keycloak requires it to match the JWT `sub` claim, not the
internal Keycloak client alias:

```properties
sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required \
  clientId="system:serviceaccount:my-namespace:my-serviceaccount";
```

### Kubernetes pod configuration

Mount a projected ServiceAccount token with the audience your Kafka client should request:

```yaml
volumes:
  - name: kafka-token
    projected:
      sources:
        - serviceAccountToken:
            audience: kafka
            expirationSeconds: 3600
            path: kafka
volumeMounts:
  - name: kafka-token
    mountPath: /var/run/secrets/tokens
    readOnly: true
```

Set `oauth.federated.k8s.token.file=/var/run/secrets/tokens/kafka` accordingly.

### Refresh timing

Kafka's `OAuthBearerLoginModule` handles refresh automatically. Tuning knobs:

| Property | Default | Description |
|---|---|---|
| `sasl.login.refresh.window.factor` | `0.8` | Refresh when this fraction of the token lifetime has elapsed |
| `sasl.login.refresh.buffer.seconds` | `300` | Refresh this many seconds before expiry |
| `sasl.login.refresh.min.period.seconds` | `60` | Minimum wait between refreshes |

### Error conditions

| Situation | Behaviour |
|---|---|
| `oauth.federated.token.endpoint.url` missing | `ConfigException` at startup |
| `oauth.federated.k8s.token.file` missing | `ConfigException` at startup |
| Token file not found at refresh time | `JwtRetrieverException` with the file path |
| Token file is empty | `JwtRetrieverException` with the file path |
| Keycloak returns HTTP 4xx | Non-retryable `JwtRetrieverException` |
| Keycloak returns HTTP 5xx | Retryable `JwtRetrieverException` (Kafka retries with backoff) |
| Response has no `access_token` | `JwtRetrieverException` |
| Returned token expires in < 10 s | `WARN` log; token is still returned |


## Debug

To debug the OAuth flow, you can enable debug logging for the OAuthBearerLoginModule by setting the following system property:

```bash
export KAFKA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 -Dorg.apache.kafka.sasl.oauthbearer.allowed.urls=${AZURE_AUTHORITY_HOST}${AZURE_TENANT_ID}/oauth2/v2.0/token"
export CLASSPATH="build/libs/kafka-oauth-extensions-<version>.jar"
kafka-topics.sh --bootstrap-server ${BOOTSTRAP_SERVER} --command-config /tmp/client.properties --list
```

## Kafka OAuthBearer Allowed URLs (Kafka 4+ Security Feature)

Kafka 4 introduces a new security feature: by default, only explicitly allowed OAuth token endpoint URLs can be used with the SASL/OAUTHBEARER mechanism. If you use a custom OAuth endpoint (for example, `https://example.com/token`), you must set the system property `org.apache.kafka.sasl.oauthbearer.allowed.urls` to include your endpoint:

```
System.setProperty("org.apache.kafka.sasl.oauthbearer.allowed.urls", "https://example.com/token");
```

If you do not set this property, you may see errors like:

```
https://example.com/token is not allowed. Update system property 'org.apache.kafka.sasl.oauthbearer.allowed.urls' to allow https://example.com/token
```

This property is required both in production and in tests if you use non-default endpoints. Update this property accordingly if you add or change OAuth endpoints in your configuration or tests.
