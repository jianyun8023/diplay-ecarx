#!/usr/bin/env bash
# Bug-level Kotlin static analysis via the detekt CLI.
#
# Why the CLI and a JDK<=22 guard: detekt 1.23.8 (latest on Maven Central) bundles a Kotlin
# compiler that rejects JDK 25, which is the JVM this repository's Gradle daemon pins
# (gradle/gradle-daemon-jvm.properties toolchainVersion=25). The Gradle detekt plugin therefore
# cannot run under that daemon, so this runs as a standalone step on JDK 21.
#
# Scope: config/detekt/detekt.yml enables bug-level rules only (potential-bugs/coroutines/
# performance minus SpreadOperator) and disables the opinionated style/complexity/naming groups.
# It reports zero issues on the current tree, so it fails only on a NEW likely-bug pattern.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="1.23.8"
CACHE="${DETEKT_CACHE_DIR:-$HOME/.cache/detekt}"
JAR="$CACHE/detekt-cli-$VERSION-all.jar"
URL="https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/$VERSION/detekt-cli-$VERSION-all.jar"

java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
if [ -z "${java_major:-}" ] || [ "$java_major" -gt 22 ]; then
  echo "detekt $VERSION requires a JDK <= 22 (found major '${java_major:-unknown}'). Run on JDK 21." >&2
  exit 1
fi

if [ ! -f "$JAR" ]; then
  mkdir -p "$CACHE"
  echo "Downloading detekt-cli $VERSION ..."
  curl -fSL "$URL" -o "$JAR"
fi

cd "$ROOT"
inputs=""
for module in shared common; do
  if [ -d "$module/src/main/java" ]; then
    inputs="${inputs:+$inputs,}$module/src/main/java"
  fi
done

mkdir -p build/reports/detekt
exec java -jar "$JAR" \
  --build-upon-default-config \
  --config "$ROOT/config/detekt/detekt.yml" \
  --parallel \
  --input "$inputs" \
  --report "txt:$ROOT/build/reports/detekt/detekt.txt" \
  --report "sarif:$ROOT/build/reports/detekt/detekt.sarif"
