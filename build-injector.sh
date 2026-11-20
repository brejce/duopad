#!/bin/bash
# 编译并部署鼠标注入器（root app_process 常驻）
set -e

PROJ=$(cd "$(dirname "$0")" && pwd)
ANDROID_BUILDTOOLS=${ANDROID_BUILDTOOLS:-$HOME/Project/android-buildtools}
BT=${BT:-$ANDROID_BUILDTOOLS/android-14}
PLATFORM=${PLATFORM:-$ANDROID_BUILDTOOLS/android-36}
JAR=$PLATFORM/android.jar
SRC=$PROJ/injector
OUT=$PROJ/build/injector
# 目标设备：ADB_SERIAL=192.168.1.10:5555 ./build-injector.sh
[ -n "$ADB_SERIAL" ] && ADB_TARGET="-s $ADB_SERIAL"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "=== javac ==="
javac -source 8 -target 8 -classpath "$JAR" -d "$OUT/classes" \
    $(find "$SRC" -name '*.java') 2>&1 | grep -v 'bootstrap class path' || true

echo "=== d8 ==="
"$BT/d8" --lib "$JAR" --min-api 30 --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

echo "=== push ==="
adb $ADB_TARGET shell "mkdir -p /data/local/tmp/injector"
adb $ADB_TARGET push "$OUT/dex/classes.dex" /data/local/tmp/injector/classes.dex

echo "=== mkfifo + start ==="
adb $ADB_TARGET shell "rm -f /data/local/tmp/sk_mouse; mkfifo /data/local/tmp/sk_mouse; chmod 666 /data/local/tmp/sk_mouse"
adb $ADB_TARGET shell "su -c 'pkill -f com.splitkey.Injector; sleep 1; CLASSPATH=/data/local/tmp/injector setsid app_process /system/bin com.splitkey.Injector >/data/local/tmp/injector.log 2>&1 </dev/null &'"
sleep 3
adb $ADB_TARGET shell "cat /data/local/tmp/injector.log 2>&1 | head -5; echo '--- proc ---'; ps -A | grep -c Injector"

echo done
