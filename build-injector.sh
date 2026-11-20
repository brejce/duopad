#!/bin/bash
# 编译并部署鼠标注入器（root app_process 常驻）
set -e

BT=$HOME/Project/android-buildtools/android-14
PLATFORM=$HOME/Project/android-buildtools/android-36
JAR=$PLATFORM/android.jar
SRC=$HOME/Project/splitkey/injector
OUT=$HOME/Project/splitkey/build/injector
D=192.168.110.128:5555

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "=== javac ==="
javac -source 8 -target 8 -classpath "$JAR" -d "$OUT/classes" \
    $(find "$SRC" -name '*.java') 2>&1 | grep -v 'bootstrap class path' || true

echo "=== d8 ==="
"$BT/d8" --lib "$JAR" --min-api 30 --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

echo "=== push ==="
adb -s $D shell "mkdir -p /data/local/tmp/injector"
adb -s $D push "$OUT/dex/classes.dex" /data/local/tmp/injector/classes.dex

echo "=== mkfifo + start ==="
adb -s $D shell "rm -f /data/local/tmp/sk_mouse; mkfifo /data/local/tmp/sk_mouse; chmod 666 /data/local/tmp/sk_mouse"
adb -s $D shell "su -c 'pkill -f com.splitkey.Injector; sleep 1; CLASSPATH=/data/local/tmp/injector setsid app_process /system/bin com.splitkey.Injector >/data/local/tmp/injector.log 2>&1 </dev/null &'"
sleep 3
adb -s $D shell "cat /data/local/tmp/injector.log 2>&1 | head -5; echo '--- proc ---'; ps -A | grep -c Injector"

echo done
