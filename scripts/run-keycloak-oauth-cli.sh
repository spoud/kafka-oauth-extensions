#!/usr/bin/env bash

set -euo pipefail

: "${TEST_JAR_NAME:?TEST_JAR_NAME must be set}"
: "${KAFKA_BOOTSTRAP_SERVER:?KAFKA_BOOTSTRAP_SERVER must be set}"
: "${TOKEN_ENDPOINT_URL:?TOKEN_ENDPOINT_URL must be set}"
: "${K8S_TOKEN_FILE:?K8S_TOKEN_FILE must be set}"
: "${KAFKA_TOPICS_BIN:?KAFKA_TOPICS_BIN must be set}"
: "${KAFKA_TEST_TOPIC:?KAFKA_TEST_TOPIC must be set}"
: "${KAFKA_TEST_ROLE:?KAFKA_TEST_ROLE must be set}"

artifact="/workspace/build/libs/${TEST_JAR_NAME}"
config_file="/tmp/client.properties"

if [[ ! -f "${artifact}" ]]; then
  echo "Artifact not found inside container: ${artifact}" >&2
  exit 1
fi

if [[ ! -f "${K8S_TOKEN_FILE}" ]]; then
  echo "Kubernetes token file not found inside container: ${K8S_TOKEN_FILE}" >&2
  exit 1
fi

cat > "${config_file}" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=OAUTHBEARER
sasl.login.callback.handler.class=io.spoud.oauth.KeycloakFederatedLoginCallbackHandler
oauth.federated.token.endpoint.url=${TOKEN_ENDPOINT_URL}
oauth.federated.k8s.token.file=${K8S_TOKEN_FILE}
sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;
EOF

export CLASSPATH="${artifact}${CLASSPATH:+:${CLASSPATH}}"
export KAFKA_OPTS="${KAFKA_OPTS:-} -Dorg.apache.kafka.sasl.oauthbearer.allowed.urls=${TOKEN_ENDPOINT_URL}"

topics() {
  "${KAFKA_TOPICS_BIN}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" --command-config "${config_file}" "$@"
}

case "${KAFKA_TEST_ROLE}" in
  creator)
    topics --create --topic "${KAFKA_TEST_TOPIC}" --partitions 1 --replication-factor 1
    topics --describe --topic "${KAFKA_TEST_TOPIC}"
    topics --list | grep -Fx "${KAFKA_TEST_TOPIC}"
    ;;
  verifier)
    topics --list | grep -Fx "${KAFKA_TEST_TOPIC}"
    topics --describe --topic "${KAFKA_TEST_TOPIC}"
    ;;
  *)
    echo "Unknown KAFKA_TEST_ROLE: ${KAFKA_TEST_ROLE}" >&2
    exit 1
    ;;
esac

