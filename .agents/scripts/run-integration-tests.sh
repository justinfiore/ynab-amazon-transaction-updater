#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)
source "${script_dir}/init-tools.sh"
cd "$repo_root"

rm -rf test-results/
./gradlew integrationTest "$@"
cp -r build/reports/tests test-results/

echo "Integration report: test-results/integrationTest/index.html"
