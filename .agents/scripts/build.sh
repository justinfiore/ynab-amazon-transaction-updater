script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source "${script_dir}"/init-tools.sh

./gradlew compileJava compileTestJava $*;
