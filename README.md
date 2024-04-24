# confluent-oauth-extensions

Apache Kafka client library providing additional integrations relating to OAuth/OIDC integrations with Confluent Cloud and Apache Kafka.

## Authenticating to Confluent Cloud via OAuth, using Azure Managed Identities

Example Kafka client config and JAAS config for authenticating to Confluent Cloud using Azure Managed Identities / Pod Identity:

```
bootstrap.servers=pkc-xxxxx.ap-southeast-2.aws.confluent.cloud:9092
security.protocol=SASL_SSL
sasl.oauthbearer.token.endpoint.url=http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https%3A%2F%2Fxxxxxxxx.onmicrosoft.com%2Fxxxxxxxxxx%2Fxxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx&client_id=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxx
sasl.login.callback.handler.class=io.confluent.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler
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
sasl.login.callback.handler.class=io.confluent.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler
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
  --property bearer.auth.custom.provider.class=io.confluent.oauth.azure.managedidentity.RegistryBearerAuthCredentialProvider \
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


cat > client.properties <<EOF
security.protocol=SASL_SSL
sasl.oauthbearer.token.endpoint.url=${AZURE_AUTHORITY_HOST}${AZURE_TENANT_ID}/oauth2/v2.0/token

sasl.login.callback.handler.class=io.confluent.oauth.azure.managedidentity.OAuthBearerLoginCallbackHandler

sasl.mechanism=OAUTHBEARER
sasl.jaas.config= \
   org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required \
       clientId='ignored' \
       clientSecret='ignored' \
       useUserIdentity='true' \
       scope='${CONFLUENT_CLOUD_APP_ID}/.default' \
       extension_logicalCluster='lkc-xxxxx' \
       extension_identityPoolId='pool-xxxx';
EOF


echo '{"make": "Ford", "model": "Mustang", "price": 10000}' |kafka-avro-console-producer --bootstrap-server <bootstrap>.confluent.cloud:9092 \
--property schema.registry.url=https://<registry>.confluent.cloud \
--property bearer.auth.credentials.source='CUSTOM' \
--property bearer.auth.custom.provider.class=io.confluent.oauth.azure.managedidentity.RegistryBearerAuthCredentialProvider \
--property bearer.auth.logical.cluster='lsrc-xxxxx' \
--producer.config client.properties --reader-config client.properties --topic cars \
--property value.schema='{"type": "record", "name": "Car", "namespace": "io.spoud.training", "fields": [{"name": "make", "type": "string"}, {"name": "model", "type": "string"}, {"name": "price", "type": "int", "default":  0}]}'

```
