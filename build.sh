#!/bin/bash
# 编译 SplitKey APK（无 Gradle，直接用 aapt2 + javac + d8 + apksigner）
set -e

# 路径全部可用环境变量覆盖，别人的目录结构不一样也能编
PROJ=$(cd "$(dirname "$0")" && pwd)
ANDROID_BUILDTOOLS=${ANDROID_BUILDTOOLS:-$HOME/Project/android-buildtools}
BT=${BT:-$ANDROID_BUILDTOOLS/android-14}
PLATFORM=${PLATFORM:-$ANDROID_BUILDTOOLS/android-36}
OUT=$PROJ/build
JAR=$PLATFORM/android.jar

# librime 原生库（静态链接产物，见 scripts/build-librime.sh）
PROJ_LIBSPLITKEY_RIME_SO=${LIBSPLITKEY_RIME_SO:-$HOME/Project/splitkey-librime-build/out/libsplitkey_rime.so}

for d in "$BT" "$PLATFORM"; do
    [ -d "$d" ] || { echo "缺少 build-tools：$d"; echo "见 README 第 2 节「准备环境」"; exit 1; }
done
[ -f "$PROJ_LIBSPLITKEY_RIME_SO" ] || {
    echo "缺少 librime 原生库：$PROJ_LIBSPLITKEY_RIME_SO"
    echo "先跑 scripts/build-librime.sh，或用 LIBSPLITKEY_RIME_SO=... 指定"
    exit 1
}

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex"

# 1. 编译资源
echo "=== aapt2 compile ==="
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/compiled/res.zip"

# 1.5 licenses/ 是唯一来源，同步进 assets/ 供 aapt2 -A 打包
#     （aapt2 -A 把目录内容映射到 APK 的 assets/ 根，所以必须放在 assets/licenses/）
echo "=== sync licenses ==="
rm -rf "$PROJ/assets/licenses"
mkdir -p "$PROJ/assets"
cp -r "$PROJ/licenses" "$PROJ/assets/licenses"

# 1.6 Rime 词库：不进仓库，构建时从 RIME_DATA 同步
#     默认用 librime 源码自带的 minimal 词库（luna_pinyin，够验证中文输入）
echo "=== sync rime data ==="
RIME_DATA=${RIME_DATA:-$HOME/Project/splitkey-librime-build/librime/data/minimal}
if [ -d "$RIME_DATA" ]; then
    rm -rf "$PROJ/assets/rime"
    mkdir -p "$PROJ/assets/rime"
    cp -r "$RIME_DATA"/* "$PROJ/assets/rime/"
    echo "  rime data: $RIME_DATA"

    # OpenCC 字典：简体转换（zh_simp）要靠它。librime 从
    # <shared_data_dir>/opencc/ 找 t2s.json + *.ocd2，缺了就只能出繁体。
    OPENCC_DATA=${OPENCC_DATA:-/usr/share/opencc}
    if [ -d "$OPENCC_DATA" ]; then
        mkdir -p "$PROJ/assets/rime/opencc"
        cp "$OPENCC_DATA"/t2s.json "$OPENCC_DATA"/s2t.json "$PROJ/assets/rime/opencc/" 2>/dev/null
        # 只带 t2s 链上需要的字典，别把整套搬进去
        cp "$OPENCC_DATA"/CJK_Compatibility_Ideographs.ocd2 \
           "$OPENCC_DATA"/TSPhrases.ocd2 \
           "$OPENCC_DATA"/TSCharacters.ocd2 \
           "$OPENCC_DATA"/TSCharactersExt.ocd2 \
           "$PROJ/assets/rime/opencc/" 2>/dev/null
        echo "  opencc: $(ls "$PROJ/assets/rime/opencc" | wc -l) files"
    else
        echo "  !!! OPENCC_DATA 不存在（简体转换不可用）"
    fi
else
    echo "  !!! RIME_DATA 不存在：$RIME_DATA（中文输入会不可用）"
fi

# 2. 链接资源 + 生成 R.java
echo "=== aapt2 link ==="
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --java "$OUT/classes" \
    --min-sdk-version 30 \
    --target-sdk-version 36 \
    "$OUT/compiled/res.zip" \
    -A "$PROJ/assets"

# 3. 编译 Java
echo "=== javac ==="
find "$PROJ/src" -name '*.java' > "$OUT/sources.txt"
# 不能用管道吞掉 javac 的退出码，否则会打包上一次的旧 class
javac -source 8 -target 8 \
    -classpath "$JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
grep -v -E 'bootstrap class path|source value 8|target value 8|已过时|deprecat' "$OUT/javac.log" || true
if [ $JAVAC_RC -ne 0 ]; then
    echo "!!! javac 失败 (rc=$JAVAC_RC)"; exit 1
fi

# 4. 转 dex
echo "=== d8 ==="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$JAR" --min-api 30 --output "$OUT/dex" @"$OUT/classes.txt"

# 5. 打包
echo "=== package ==="
SO="$PROJ_LIBSPLITKEY_RIME_SO"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$SO" <<'PY'
import os, sys, zipfile
apk, dex, so = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
    if so and os.path.isfile(so):
        # 原生库必须不压缩，否则安装后 mmap 失败
        z.write(so, 'lib/arm64-v8a/libsplitkey_rime.so', zipfile.ZIP_STORED)
        print("native lib added")
    else:
        print("native lib MISSING: " + str(so))
PY

# 6. 对齐 + 签名
echo "=== zipalign + sign ==="
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

KS=$PROJ/debug.keystore
if [ ! -f "$KS" ]; then
    keytool -genkeypair -v -keystore "$KS" -alias androiddebugkey \
        -storepass android -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$OUT/splitkey.apk" "$OUT/aligned.apk"

echo
echo "=== 完成 ==="
ls -lh "$OUT/splitkey.apk"
