#!/usr/bin/env sh
set -eu
directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -d "$directory/../lib" ]; then
    lib="$directory/../lib"
else
    lib="$directory/../build/install/ynab-budget-assistant/lib"
fi
if [ ! -d "$lib" ]; then
    echo 'Run ./gradlew installDist before using the source-checkout migration script.' >&2
    exit 1
fi
java=java
if [ -n "${JAVA_HOME:-}" ]; then java="$JAVA_HOME/bin/java"; fi
exec "$java" -cp "$lib/*" com.ynab.budgetassistant.config.ConfigMigration "$@"
