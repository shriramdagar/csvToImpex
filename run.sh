#!/usr/bin/env bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/build/libs/hybris-impex-converter-1.0.0.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "[INFO] Building application with Gradle..."
    chmod +x "$SCRIPT_DIR/gradlew"
    "$SCRIPT_DIR/gradlew" jar
fi

java -jar "$JAR_PATH" "$@"
