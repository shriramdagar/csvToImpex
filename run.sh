#!/usr/bin/env bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/build/libs/hybris-impex-converter-1.0.0.jar"

# Auto-detect JAVA_HOME if not already configured
if [ -z "$JAVA_HOME" ]; then
    if [ -x "/opt/homebrew/opt/openjdk/bin/java" ]; then
        export JAVA_HOME="/opt/homebrew/opt/openjdk"
        export PATH="$JAVA_HOME/bin:$PATH"
    elif [ -x "/usr/libexec/java_home" ] && /usr/libexec/java_home >/dev/null 2>&1; then
        export JAVA_HOME="$(/usr/libexec/java_home)"
        export PATH="$JAVA_HOME/bin:$PATH"
    elif [ -d "/Library/Java/JavaVirtualMachines/sapmachine-jdk.jdk/Contents/Home" ]; then
        export JAVA_HOME="/Library/Java/JavaVirtualMachines/sapmachine-jdk.jdk/Contents/Home"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi

if [ ! -f "$JAR_PATH" ]; then
    echo "[INFO] Building application with Gradle..."
    chmod +x "$SCRIPT_DIR/gradlew"
    "$SCRIPT_DIR/gradlew" jar
fi

java -jar "$JAR_PATH" "$@"

