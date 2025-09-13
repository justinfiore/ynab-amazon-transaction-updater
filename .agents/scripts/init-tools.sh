if [ -e "/opt/devin-scripts/java/jdk-11" ]; then
  current_java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
  echo "Current Java Version: $current_java_version"
  if [[ "$current_java_version" =~ ^11(\.|$) ]]; then
    echo "Shell is already configured to use JDK 11"
  else
    echo "Configured shell to use JDK 11"
    source /opt/devin-scripts/java/jdk-11
  fi
else
  echo "Didn't find a configuration script at: /opt/devin-scripts/java/jdk-11"
  echo "Java Path is: `which java`"
  echo "Java Version is: `java -version 2>&1 | awk -F '"' '/version/ {print $2}'`"
fi

echo "Java Path is: `which java`"
echo "Java Version is: `java -version 2>&1 | awk -F '"' '/version/ {print $2}'`"

# Verify Java 11 is available; fail if not after attempting configuration
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java not found on PATH after attempting to configure JDK 11." >&2
  exit 1
fi

current_java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
if [[ ! "$current_java_version" =~ ^11(\.|$) ]]; then
  echo "ERROR: Java 11.* is required. Found: $current_java_version" >&2
  exit 1
fi

