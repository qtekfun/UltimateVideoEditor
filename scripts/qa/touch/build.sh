#!/usr/bin/env bash
# Builds the touch injector jar (scripts/qa/touch/Touch.java) with the SDK's javac and d8. Output: <out>/uv-touch.jar
# (default /home/qtekfun/uvdata/qa-touch). Run on the host; scripts/qa-smoke.sh pushes it to /data/local/tmp.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-/home/qtekfun/uvdata/qa-touch}"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
android_jar="$(ls -d "$sdk"/platforms/android-*/android.jar | sort -V | tail -1)"
d8="$(ls -d "$sdk"/build-tools/*/d8 | sort -V | tail -1)"
mkdir -p "$out/classes"
javac -source 11 -target 11 -Xlint:-options -cp "$android_jar" -d "$out/classes" "$here/Touch.java"
"$d8" --lib "$android_jar" --output "$out" "$out"/classes/*.class
cd "$out" && mv classes.dex uv-touch.dex 2>/dev/null || true
rm -f uv-touch.jar
cp uv-touch.dex classes.dex && zip -q uv-touch.jar classes.dex && rm -f classes.dex
echo "$out/uv-touch.jar"
