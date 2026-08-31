#!/usr/bin/env bash
# Rebuilds libs/boxexpress-ws-shim-*.jar from java-src/. Only needs re-running
# if boxexpress/ws/*.java changes — the compiled jar in libs/ is what BoxLang
# actually loads at runtime, same as every other vendored dependency.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="1.0.0"
BUILD_DIR="java-src/build"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

javac -cp "libs/undertow-core-2.4.2.Final.jar:libs/xnio-api-3.8.16.Final.jar" \
	-d "$BUILD_DIR" \
	java-src/boxexpress/ws/*.java

jar cf "libs/boxexpress-ws-shim-${VERSION}.jar" -C "$BUILD_DIR" .
rm -rf "$BUILD_DIR"

echo "Built libs/boxexpress-ws-shim-${VERSION}.jar"
