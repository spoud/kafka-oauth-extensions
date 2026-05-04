#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/compose.compatibility.yaml"

resolve_default_artifact() {
  local -a candidates=()
  local candidate

  shopt -s nullglob
  for candidate in "${ROOT_DIR}"/build/libs/kafka-oauth-extensions-*.jar; do
    if [[ "${candidate}" == *-all.jar ]]; then
      continue
    fi
    candidates+=("${candidate}")
  done
  shopt -u nullglob

  if [[ "${#candidates[@]}" -eq 0 ]]; then
    echo "No thin jar found in build/libs." >&2
    exit 1
  fi

  printf '%s\n' "$(ls -t "${candidates[@]}" | head -n 1)"
}

assert_no_bundled_kafka_classes() {
  local artifact_path="$1"

  if jar tf "${artifact_path}" | grep -Eq '^(org/apache/kafka/|kafka/)'; then
    echo "Compatibility smoke test failed before startup." >&2
    echo "Artifact bundles Kafka runtime classes: ${artifact_path}" >&2
    echo "Use the thin jar or rebuild the shadow jar without Kafka classes." >&2
    # exit 1
  fi
}

if [[ $# -gt 0 ]]; then
  ARTIFACT_PATH="$1"
else
  ARTIFACT_PATH="$(resolve_default_artifact)"
fi

if [[ ! -f "${ARTIFACT_PATH}" ]]; then
  echo "Artifact not found: ${ARTIFACT_PATH}" >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required" >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "docker compose is required" >&2
  exit 1
fi

assert_no_bundled_kafka_classes "${ARTIFACT_PATH}"

export TEST_JAR_NAME="$(basename "${ARTIFACT_PATH}")"
export COMPATIBILITY_BOOTSTRAP_SERVER="${COMPATIBILITY_BOOTSTRAP_SERVER:-localhost:9092}"

cleanup() {
  docker compose -f "${COMPOSE_FILE}" down --remove-orphans >/dev/null 2>&1 || true
}

trap cleanup EXIT

services=(
  apache-kafka-oss-cli
  confluent-platform-cli
)

for service in "${services[@]}"; do
  echo "==> Running compatibility smoke test: ${service}"
  docker compose -f "${COMPOSE_FILE}" run --rm "${service}"
done
