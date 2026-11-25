#!/system/bin/sh
# 阶段 2（在 Android 设备上执行）：aapt2 link 出 base.apk
# aapt2 是 Termux 的 bionic 二进制，只能在设备上跑
set -e

ROOT=/data/local/tmp/fluiddl/edge-fluiddl
P=/data/local/tmp/tprefix/data/data/com.termux/files/usr
SDK=/data/local/tmp/fluiddl/sdk

if [ -f "$ROOT/version.env" ]; then
    . "$ROOT/version.env"
fi
: "${VERSION_CODE:=1}"
: "${VERSION_NAME:=1.0}"

export LD_LIBRARY_PATH=$P/lib

"$P/bin/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$ROOT/build/res.zip"

"$P/bin/aapt2" link \
    -o "$ROOT/build/base.apk" \
    -I "$SDK/platforms/android-34/android.jar" \
    --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    -A "$ROOT/app/src/main/assets" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    "$ROOT/build/res.zip"

ls -l "$ROOT/build/base.apk"
