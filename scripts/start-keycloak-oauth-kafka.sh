#!/usr/bin/env bash

set -euo pipefail

: "${KAFKA_CLUSTER_ID:?KAFKA_CLUSTER_ID must be set}"
: "${KAFKA_KEYCLOAK_ISSUER:?KAFKA_KEYCLOAK_ISSUER must be set}"
: "${KAFKA_KEYCLOAK_JWKS_ENDPOINT:?KAFKA_KEYCLOAK_JWKS_ENDPOINT must be set}"
: "${KAFKA_KEYCLOAK_EXPECTED_AUDIENCE:=account}"

export KAFKA_OPTS="${KAFKA_OPTS:-} -Dorg.apache.kafka.sasl.oauthbearer.allowed.urls=${KAFKA_KEYCLOAK_JWKS_ENDPOINT}"

cat > /tmp/server.properties <<EOF
process.roles=broker,controller
node.id=${KAFKA_NODE_ID:-1}
controller.quorum.voters=1@kafka:9093
listeners=CLIENT://:9092,INTERNAL://:9094,CONTROLLER://:9093
advertised.listeners=CLIENT://kafka:9092,INTERNAL://kafka:9094
listener.security.protocol.map=CLIENT:SASL_PLAINTEXT,INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
inter.broker.listener.name=INTERNAL
controller.listener.names=CONTROLLER
log.dirs=/var/lib/kafka/data
num.partitions=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
sasl.enabled.mechanisms=OAUTHBEARER
sasl.oauthbearer.jwks.endpoint.url=${KAFKA_KEYCLOAK_JWKS_ENDPOINT}
sasl.oauthbearer.expected.issuer=${KAFKA_KEYCLOAK_ISSUER}
sasl.oauthbearer.expected.audience=${KAFKA_KEYCLOAK_EXPECTED_AUDIENCE}
listener.name.client.sasl.enabled.mechanisms=OAUTHBEARER
listener.name.client.oauthbearer.sasl.server.callback.handler.class=org.apache.kafka.common.security.oauthbearer.OAuthBearerValidatorCallbackHandler
listener.name.client.oauthbearer.sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;
listener.name.client.sasl.oauthbearer.jwks.endpoint.url=${KAFKA_KEYCLOAK_JWKS_ENDPOINT}
listener.name.client.sasl.oauthbearer.expected.issuer=${KAFKA_KEYCLOAK_ISSUER}
listener.name.client.sasl.oauthbearer.expected.audience=${KAFKA_KEYCLOAK_EXPECTED_AUDIENCE}
EOF

if [[ ! -f /var/lib/kafka/data/meta.properties ]]; then
  /opt/kafka/bin/kafka-storage.sh format --ignore-formatted -t "${KAFKA_CLUSTER_ID}" -c /tmp/server.properties
fi

exec /opt/kafka/bin/kafka-server-start.sh /tmp/server.properties
