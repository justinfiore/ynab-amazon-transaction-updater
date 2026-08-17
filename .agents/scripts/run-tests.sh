#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)
source "${script_dir}/init-tools.sh"
cd "$repo_root"

rm -rf test-results/
./gradlew testAll "$@"
cp -r build/reports/tests test-results/

echo "Unit report: test-results/test/index.html"
echo "Integration report: test-results/integrationTest/index.html"
