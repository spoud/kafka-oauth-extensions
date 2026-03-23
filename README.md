# SPOUD kafka-oauth-extensions

This is a fork of the Confluent OAuth extensions for Apache Kafka, with additional support for Azure Managed Identities and Workload Identities.

Apache Kafka client library providing additional integrations relating to OAuth/OIDC integrations with Confluent Cloud and Apache Kafka.

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

### Optional: Keycloak client ID

If your Keycloak client configuration requires a `client_id` in the token request, add it as a
JAAS option:

```properties
sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required \
  clientId="kafka-demo-app";
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
export CLASSPATH="build/libs/kafka-oauth-extensions-1.2-SNAPSHOT-all.jar"
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
