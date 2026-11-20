#!/bin/bash
# 下载 Android build-tools（aapt2/d8/zipalign/apksigner）和 android.jar。
#
#   ANDROID_BUILDTOOLS=~/android-buildtools ./scripts/fetch-buildtools.sh
#
# build.sh 默认找 ~/Project/android-buildtools，想换位置就设这个变量。
set -e

DEST=${ANDROID_BUILDTOOLS:-$HOME/Project/android-buildtools}
BT_VER=${BT_VER:-14}
PLATFORM_VER=${PLATFORM_VER:-36}
CMDLINE_VER=${CMDLINE_VER:-13114758}

mkdir -p "$DEST"
cd "$DEST"

# ---------- sdkmanager 的 zip 里同时有 build-tools 和 platforms ----------
if [ ! -d "android-$BT_VER" ] || [ ! -f "android-$PLATFORM_VER/android.jar" ]; then
    if [ ! -f cmdline-tools.zip ]; then
        echo "=== 下载 commandline-tools ==="
        curl -L -o cmdline-tools.zip \
            "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VER}_latest.zip"
    fi
    [ -d cmdline-tools ] || unzip -q cmdline-tools.zip

    adb_pkg() {   # 只装出压缩包，不解压到 SDK 目录，保持本仓库的目录布局
        yes | cmdline-tools/bin/sdkmanager --sdk_root="$DEST/sdk" "$1" >/dev/null
    }

    echo "=== 安装 build-tools;$BT_VER ==="
    adb_pkg "build-tools;$BT_VER"
    echo "=== 安装 platforms;android-$PLATFORM_VER ==="
    adb_pkg "platforms;android-$PLATFORM_VER"

    [ -d "android-$BT_VER" ] || mv "sdk/build-tools/$BT_VER" "android-$BT_VER"
    [ -d "android-$PLATFORM_VER" ] || mv "sdk/platforms/android-$PLATFORM_VER" "android-$PLATFORM_VER"
fi

# ---------- 校验 ----------
for f in "android-$BT_VER/aapt2" "android-$BT_VER/d8" "android-$BT_VER/zipalign" \
         "android-$BT_VER/apksigner" "android-$PLATFORM_VER/android.jar"; do
    [ -e "$f" ] || { echo "缺少 $DEST/$f"; exit 1; }
done

echo
echo "=== 完成 ==="
echo "ANDROID_BUILDTOOLS=$DEST"
