#!/bin/bash
# 把 uimouse + 注入 daemon + APK 装到设备上并拉起。
#
#   ADB_SERIAL=192.168.1.10:5555 ./device/install.sh
#
# 需要设备已 root（su 可用）。不要重装 APK 后忘了重设输入法 ——
# 重装会把默认输入法重置回 Gboard。
set -e

PROJ=$(cd "$(dirname "$0")/.." && pwd)
ADB_TARGET=""
[ -n "$ADB_SERIAL" ] && ADB_TARGET="-s $ADB_SERIAL"
adb() { command adb $ADB_TARGET "$@"; }

APK=${APK:-$PROJ/build/splitkey.apk}
[ -f "$APK" ] || { echo "没有 APK：$APK（先跑 ./build.sh）"; exit 1; }

# ---------- 1. uimouse（虚拟鼠标，需要交叉编译） ----------
if [ ! -f "$PROJ/build/uimouse" ]; then
    echo "=== 编译 uimouse ==="
    command -v aarch64-linux-gnu-gcc >/dev/null || {
        echo "缺少 aarch64-linux-gnu-gcc（Arch: pacman -S aarch64-linux-gnu-gcc）"
        exit 1
    }
    # 必须用 libc 编译，别手写 svc #0 —— 见 README 第 7 节
    aarch64-linux-gnu-gcc -static -O2 \
        -o "$PROJ/build/uimouse" "$PROJ/device/uimouse.c"
fi

echo "=== 推文件 ==="
adb shell "mkdir -p /data/local/tmp"
adb push "$PROJ/build/uimouse"            /data/local/tmp/uimouse
adb push "$PROJ/device/sk_daemon.sh"      /data/local/tmp/sk_daemon.sh
adb push "$PROJ/device/splitkey-boot.sh"  /data/local/tmp/splitkey-boot.sh
adb shell "su -c 'chmod 755 /data/local/tmp/uimouse /data/local/tmp/sk_daemon.sh /data/local/tmp/splitkey-boot.sh'"

# ---------- 2. APK ----------
echo "=== 安装 APK ==="
adb install -r "$APK"

# ---------- 3. 输入法：必须是默认 IME 才会被绑，否则 commitText 无处可去 ----------
echo "=== 设置输入法 ==="
adb shell "su -c 'ime set com.splitkey.ime/.HiddenIme'"

# ---------- 4. 常驻进程 ----------
# 每个后台进程都要断掉 stdin，否则 adb shell 会一直挂着不返回
echo "=== 拉起虚拟鼠标 + 注入 daemon ==="
# pkill -f 的模式会连自己所在的 shell 一起匹配（命令行里有这串字符），
# 所以模式要用字符类拆开：[u]imouse 匹不到 "pkill ... [u]imouse" 这行本身。
adb shell "su -c 'pkill -f \"[u]imouse\" || true'" || true
adb shell "su -c 'pkill -f \"[s]k_daemon.sh\" || true'" || true
sleep 1
adb shell "su -c 'setsid /data/local/tmp/uimouse >/dev/null 2>&1 </dev/null &'" || true
adb shell "su -c 'setsid sh /data/local/tmp/sk_daemon.sh >/dev/null 2>&1 </dev/null &'" || true
sleep 2

# ---------- 5. 键盘悬浮窗 ----------
echo "=== 启动键盘 ==="
adb shell "su -c 'am start-foreground-service -n com.splitkey.ime/.KeyboardService'"

echo
echo "=== 完成 ==="
adb shell "ps -A | grep -E 'uimouse|splitkey' | head -5"
