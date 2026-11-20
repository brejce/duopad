#!/bin/bash
# 编译 SplitKey APK（无 Gradle，直接用 aapt2 + javac + d8 + apksigner）
set -e

BT=$HOME/Project/android-buildtools/android-14
PLATFORM=$HOME/Project/android-buildtools/android-36
PROJ=$HOME/Project/splitkey
OUT=$PROJ/build
JAR=$PLATFORM/android.jar

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex"

# 1. 编译资源
echo "=== aapt2 compile ==="
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/compiled/res.zip"

# 2. 链接资源 + 生成 R.java
echo "=== aapt2 link ==="
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --java "$OUT/classes" \
    --min-sdk-version 30 \
    --target-sdk-version 36 \
    "$OUT/compiled/res.zip"

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
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("classes.dex added")
PY

# 6. 对齐 + 签名
echo "=== zipalign + sign ==="
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

KS="$HOME/Project/splitkey/debug.keystore"
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
