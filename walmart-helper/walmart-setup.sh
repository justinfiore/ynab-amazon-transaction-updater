#!/usr/bin/env sh
set -eu
directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
java=java
if [ -n "${JAVA_HOME:-}" ]; then java="$JAVA_HOME/bin/java"; fi
mode=--walmart-setup
if [ "${1:-}" = --categories ]; then mode=--walmart-categories; shift; fi
exec "$java" -cp "$directory/../lib/*" com.ynab.amazon.YNABAmazonTransactionUpdater "$mode" "$@"
