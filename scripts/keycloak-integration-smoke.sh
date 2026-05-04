#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/compose.keycloak-integration.yaml"
OUTPUT_DIR="${ROOT_DIR}/build/keycloak-integration"
KIND_CLUSTER_NAME="kafka-oauth-extension"
KIND_CONTEXT="kind-${KIND_CLUSTER_NAME}"
KIND_ISSUER_URL="https://k8s-issuer-proxy:8443"
REALM_ISSUER_URL="http://keycloak:8080/realms/kubernetes"
TOKEN_ENDPOINT_URL="${REALM_ISSUER_URL}/protocol/openid-connect/token"
CLIENT_ID="myclient"
SUBJECT="system:serviceaccount:default:my-serviceaccount"
SERVICE_ACCOUNT_NAME="my-serviceaccount"
KAFKA_CLUSTER_ID="MkU3OEVBNTcwNTJENDM2Qg"

resolve_default_artifact() {
  local -a candidates=()
  local candidate

  shopt -s nullglob
  for candidate in "${ROOT_DIR}"/build/libs/kafka-oauth-extensions-*-all.jar; do
    candidates+=("${candidate}")
  done
  shopt -u nullglob

  if [[ "${#candidates[@]}" -eq 0 ]]; then
    echo "No shadow jar found in build/libs." >&2
    exit 1
  fi

  printf '%s\n' "$(ls -t "${candidates[@]}" | head -n 1)"
}

assert_no_bundled_kafka_classes() {
  local artifact_path="$1"

  if jar tf "${artifact_path}" | grep -Eq '^(org/apache/kafka/|kafka/)'; then
    echo "Real Keycloak integration smoke test requires a Kafka-free artifact." >&2
    echo "Artifact bundles Kafka runtime classes: ${artifact_path}" >&2
    exit 1
  fi
}

require_command() {
  local cmd="$1"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "${cmd} is required" >&2
    exit 1
  fi
}

cleanup() {
  docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans >/dev/null 2>&1 || true
  kind delete cluster --name "${KIND_CLUSTER_NAME}" >/dev/null 2>&1 || true
}

wait_for_keycloak() {
  local -a kcadm=(
    docker compose -f "${COMPOSE_FILE}" exec -T keycloak /opt/keycloak/bin/kcadm.sh
  )
  local attempt
  for attempt in $(seq 1 90); do
    if "${kcadm[@]}" config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Keycloak did not become ready in time" >&2
  return 1
}

wait_for_kafka() {
  local attempt
  for attempt in $(seq 1 90); do
    if docker compose -f "${COMPOSE_FILE}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9094 --list >/dev/null 2>&1; then
      return 0
    fi
    if ! docker compose -f "${COMPOSE_FILE}" ps --status running kafka | grep -q kafka; then
      docker compose -f "${COMPOSE_FILE}" logs --no-color kafka >&2 || true
      echo "Kafka container stopped before becoming ready" >&2
      return 1
    fi
    sleep 2
  done
  docker compose -f "${COMPOSE_FILE}" logs --no-color kafka >&2 || true
  echo "Kafka did not become ready in time" >&2
  return 1
}

create_kind_cluster() {
  kind create cluster --name "${KIND_CLUSTER_NAME}" --config "${ROOT_DIR}/scripts/kind-keycloak-integration.yaml"
  kubectl --context "${KIND_CONTEXT}" create serviceaccount "${SERVICE_ACCOUNT_NAME}" >/dev/null
}

write_service_account_token() {
  mkdir -p "${OUTPUT_DIR}"
  kubectl --context "${KIND_CONTEXT}" create token "${SERVICE_ACCOUNT_NAME}" \
    --namespace default \
    --duration 15m \
    --audience "${REALM_ISSUER_URL}" > "${OUTPUT_DIR}/k8s-token"
}

prepare_proxy_tls() {
  mkdir -p "${OUTPUT_DIR}/proxy-tls"
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "${OUTPUT_DIR}/proxy-tls/key.pem" \
    -out "${OUTPUT_DIR}/proxy-tls/cert.pem" \
    -subj '/CN=k8s-issuer-proxy' \
    -addext 'subjectAltName=DNS:k8s-issuer-proxy' \
    -days 1 >/dev/null 2>&1
  rm -f "${OUTPUT_DIR}/proxy-tls/truststore.p12"
  keytool -importcert -noprompt \
    -alias k8s-issuer-proxy \
    -file "${OUTPUT_DIR}/proxy-tls/cert.pem" \
    -keystore "${OUTPUT_DIR}/proxy-tls/truststore.p12" \
    -storetype PKCS12 \
    -storepass changeit >/dev/null
  kubectl config view --raw --minify --context "${KIND_CONTEXT}" -o jsonpath='{.users[0].user.client-certificate-data}' \
    | base64 -d > "${OUTPUT_DIR}/proxy-tls/apiserver-client-cert.pem"
  kubectl config view --raw --minify --context "${KIND_CONTEXT}" -o jsonpath='{.users[0].user.client-key-data}' \
    | base64 -d > "${OUTPUT_DIR}/proxy-tls/apiserver-client-key.pem"
}

configure_keycloak() {
  local -a kcadm=(
    docker compose -f "${COMPOSE_FILE}" exec -T keycloak /opt/keycloak/bin/kcadm.sh
  )

  "${kcadm[@]}" config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null
  "${kcadm[@]}" create realms -s realm=kubernetes -s enabled=true -s sslRequired=NONE >/dev/null
  "${kcadm[@]}" create identity-provider/instances -r kubernetes \
    -s alias=kubernetes \
    -s providerId=kubernetes \
    -s config="{\"issuer\":\"${KIND_ISSUER_URL}\"}" >/dev/null
  "${kcadm[@]}" create clients -r kubernetes \
    -s clientId="${CLIENT_ID}" \
    -s enabled=true \
    -s serviceAccountsEnabled=true \
    -s fullScopeAllowed=true \
    -s clientAuthenticatorType=federated-jwt \
    -s attributes="{\"jwt.credential.issuer\":\"kubernetes\",\"jwt.credential.sub\":\"${SUBJECT}\"}" >/dev/null
}

run_cli_checks() {
  local services=(
    apache-kafka-oss-cli
    confluent-platform-cli
  )
  local service

  for service in "${services[@]}"; do
    echo "==> Running real OAuth integration check: ${service}"
    if ! docker compose -f "${COMPOSE_FILE}" run --rm "${service}"; then
      docker compose -f "${COMPOSE_FILE}" logs --no-color keycloak >&2 || true
      return 1
    fi
  done
}

trap cleanup EXIT

cd "${ROOT_DIR}"

./gradlew build

require_command docker
require_command kind
require_command kubectl
require_command keytool

ARTIFACT_PATH="${1:-$(resolve_default_artifact)}"
if [[ ! -f "${ARTIFACT_PATH}" ]]; then
  echo "Artifact not found: ${ARTIFACT_PATH}" >&2
  exit 1
fi
assert_no_bundled_kafka_classes "${ARTIFACT_PATH}"

export TEST_JAR_NAME="$(basename "${ARTIFACT_PATH}")"
export KAFKA_CLUSTER_ID

create_kind_cluster
prepare_proxy_tls
write_service_account_token

docker compose -f "${COMPOSE_FILE}" up -d k8s-issuer-proxy keycloak
wait_for_keycloak
configure_keycloak
docker compose -f "${COMPOSE_FILE}" up -d kafka
wait_for_kafka
run_cli_checks
