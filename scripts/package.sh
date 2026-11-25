#!/usr/bin/env bash
# 阶段 3（Linux / Debian chroot）：把 link 好的 base.apk 塞进 dex 并签名
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BT=/data/local/tmp/fluiddl/sdk/build-tools/34.0.0
OUT="$ROOT/build"

[ -f "$OUT/base.apk" ] || { echo "缺少 build/base.apk：先在设备上执行 scripts/link.sh"; exit 1; }

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)

if [ ! -f "$ROOT/scripts/keystore.jks" ]; then
  keytool -genkeypair -keystore "$ROOT/scripts/keystore.jks" -storepass android \
    -keypass android -alias module -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=mahiroDesu" >/dev/null 2>&1
fi

java -jar "$BT/lib/apksigner.jar" sign \
    --ks "$ROOT/scripts/keystore.jks" --ks-pass pass:android --key-pass pass:android \
    --out "$ROOT/build/Edgefluiddl.apk" "$OUT/unsigned.apk"

java -jar "$BT/lib/apksigner.jar" verify --print-certs "$ROOT/build/Edgefluiddl.apk" | head -3
ls -l "$ROOT/build/Edgefluiddl.apk"
