#!/usr/bin/env bash

set -euo pipefail

: "${TEST_JAR_NAME:?TEST_JAR_NAME must be set}"
: "${COMPATIBILITY_COMMAND:?COMPATIBILITY_COMMAND must be set}"

artifact="/workspace/build/libs/${TEST_JAR_NAME}"

if [[ ! -f "${artifact}" ]]; then
  echo "Artifact not found inside container: ${artifact}" >&2
  exit 1
fi

export CLASSPATH="${artifact}"

set +e
output="$(bash -lc "${COMPATIBILITY_COMMAND}" 2>&1)"
status=$?
set -e

printf '%s\n' "${output}"

if grep -Eq 'NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|LinkageError' <<<"${output}"; then
  echo "Detected linkage/classpath failure" >&2
  exit 1
fi

if grep -Eq 'This tool helps to create, delete, describe, or change a topic\.|Option[[:space:]]+Description' <<<"${output}"; then
  exit 0
fi

if grep -Eq 'Connection to node|Bootstrap broker|Timed out waiting|TimeoutException' <<<"${output}"; then
  exit 0
fi

exit "${status}"
