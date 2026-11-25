#!/usr/bin/env bash
# 阶段 1（Linux / Debian chroot）：javac 编译 + d8 生成 classes.dex
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK=/data/local/tmp/fluiddl/sdk
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
OUT="$ROOT/build"

rm -rf "$OUT/stubs" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex"

echo "[1/2] javac: compat stubs"
javac -nowarn -source 11 -target 11 -cp "$ANDROID_JAR" \
    -d "$OUT/stubs" $(find "$ROOT/compat/stubs" -name '*.java')

echo "[2/2] javac: module + d8"
javac -nowarn -source 11 -target 11 -cp "$ANDROID_JAR:$OUT/stubs" \
    -d "$OUT/classes" $(find "$ROOT/app/src/main/java" -name '*.java')

java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 26 --lib "$ANDROID_JAR" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

ls -l "$OUT/dex/classes.dex"
