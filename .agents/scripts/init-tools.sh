#!/usr/bin/env bash
set -euo pipefail

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java 25 is required but java was not found on PATH." >&2
  exit 1
fi

java_version_output=$(java -version 2>&1)
if [[ "$java_version_output" =~ version\ \"([^\"]+)\" ]]; then
  java_version="${BASH_REMATCH[1]}"
else
  echo "ERROR: Unable to determine the Java version." >&2
  printf '%s\n' "$java_version_output" >&2
  exit 1
fi

if [[ ! "$java_version" =~ ^25(\.|$) ]]; then
  echo "ERROR: Java 25 is required. Found: $java_version" >&2
  exit 1
fi

echo "Java Path: $(command -v java)"
echo "Java Version: $java_version"
