  script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source "${script_dir}"/init-tools.sh

rm -rf test-results/;
./gradlew integrationTest $*;
cp -r build/reports/tests test-results/;

echo "Inspect the results in test-results/";