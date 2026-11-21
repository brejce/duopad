#!/bin/bash
# 从掌机采集视频素材。需要 root（录屏 + 触摸注入都要）。
#
#   ADB_SERIAL=192.168.1.10:5555 ./video/shoot.sh
#
# 产出 video/assets/：
#   screen-typing.mp4        上屏真实打字（中文候选 + 标点 + 退格）
#   still-keyboard.png       下屏键盘（含触摸板）
#   still-typing.png         上屏带候选胶囊的静帧
#   still-touchpad-full.png  触摸板全屏态
#
# 触摸坐标写死在下方变量里，是 1280×960 下屏的实测值。
# 键盘布局改了就跟着改，别指望脚本自己找。
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
A=$HERE/assets
mkdir -p "$A"

ADB_TARGET=""
[ -n "$ADB_SERIAL" ] && ADB_TARGET="-s $ADB_SERIAL"
adb_() { command adb $ADB_TARGET "$@"; }

D2_W=1280; D2_H=960
D2_UID=${D2_UID:-4630946671160541331}      # 下屏 screencap -d 的 uniqueId
D2=2                                        # input -d 的 displayId

# ---- 下屏键盘坐标（实测） ----
K_N="764 723"; K_I="846 519"; K_SPACE="754 900"
K_COMMA="988 795"; K_PERIOD="1112 795"
K_DEL="1210 519"; K_SHIFT="85 766"; K_CTRL="40 900"
# 放大按钮的 hitbox 会随状态移动（TouchpadView.zoomLeft()：全屏时贴屏幕左边）
# 非全屏 leftInset=165 -> x 179..231；全屏 leftInset=-1 -> x 14..66
K_ZOOM="205 49"                             # 非全屏态：放大
K_ZOOM_OUT="40 49"                          # 全屏态：缩小（换坐标，否则点不到）

tap2() { adb_ shell "input -d $D2 tap $1" >/dev/null 2>&1; sleep "${2:-0.4}"; }

echo "=== 检查设备 ==="
adb_ get-state >/dev/null 2>&1 || { echo "设备未连接"; exit 1; }

echo "=== 确保键盘在 ==="
adb_ shell "su -c 'am start-foreground-service -n com.splitkey.ime/.KeyboardService --ez pin true'" >/dev/null 2>&1
sleep 3

# ---- 静帧：下屏键盘 ----
echo "=== 截下屏键盘 ==="
adb_ shell "screencap -p -d $D2_UID /data/local/tmp/_k.png" >/dev/null 2>&1
adb_ pull /data/local/tmp/_k.png "$A/still-keyboard.png" >/dev/null 2>&1

# ---- 全屏触摸板 ----
echo "=== 触摸板全屏 ==="
tap2 "$K_ZOOM" 2
adb_ shell "screencap -p -d $D2_UID /data/local/tmp/_t.png" >/dev/null 2>&1
adb_ pull /data/local/tmp/_t.png "$A/still-touchpad-full.png" >/dev/null 2>&1
tap2 "$K_ZOOM_OUT" 2       # 还原（必须用全屏态坐标）

# ---- 录屏：真实打字 ----
# 先在某个能输入的地方拿到焦点。优先浏览器地址栏；拿不到就只录键盘操作。
echo "=== 准备输入焦点 ==="
adb_ shell "am start -a android.intent.action.VIEW -d https://example.com" >/dev/null 2>&1
sleep 6
adb_ shell "input tap 540 70" >/dev/null 2>&1     # 上屏地址栏附近
sleep 1

SECS=${SECS:-16}
echo "=== 录屏 ${SECS}s（上屏 + 下屏同时录）==="
# screenrecord 只能录一个 display。上屏是主体，下屏靠静帧交代。
adb_ shell "rm -f /data/local/tmp/_rec.mp4"
adb_ shell "screenrecord --time-limit $SECS --bit-rate 8000000 --size 1280x720 /data/local/tmp/_rec.mp4" >/dev/null 2>&1 &
REC_PID=$!
sleep 2

# 录的过程中操作：中文 -> 空格选词 -> 标点 -> 退格
tap2 "$K_N" 0.3
tap2 "$K_I" 0.8
tap2 "$K_SPACE" 1.0
tap2 "$K_COMMA" 0.6
tap2 "$K_PERIOD" 0.6
tap2 "$K_DEL" 0.5
tap2 "$K_DEL" 0.5

wait $REC_PID 2>/dev/null || true
sleep 1
adb_ pull /data/local/tmp/_rec.mp4 "$A/screen-typing.mp4" >/dev/null 2>&1 || \
    echo "  录屏拉取失败（screenrecord 可能被别的进程占用）"

# ---- 静帧：整机（上屏候选 + 下屏键盘） ----
echo "=== 截整机：上屏候选 + 下屏键盘 ==="
tap2 "$K_N" 0.3
tap2 "$K_I" 1.0
adb_ shell "screencap -p /data/local/tmp/_s.png" >/dev/null 2>&1
adb_ pull /data/local/tmp/_s.png "$A/still-top.png" >/dev/null 2>&1
adb_ shell "screencap -p -d $D2_UID /data/local/tmp/_k.png" >/dev/null 2>&1
adb_ pull /data/local/tmp/_k.png "$A/still-keyboard.png" >/dev/null 2>&1

# 上屏缩到下屏宽度（1280），上下拼接 = 整机外观 1280x1680
if [ -f "$A/still-top.png" ] && [ -f "$A/still-keyboard.png" ]; then
    ffmpeg -y -v error -i "$A/still-top.png" -i "$A/still-keyboard.png" \
        -filter_complex "[0:v]scale=1280:-1:flags=lanczos[t];[t][1:v]vstack=inputs=2" \
        "$A/still-typing.png"
    echo "  整机视图：still-typing.png ($(identify -format '%wx%h' "$A/still-typing.png" 2>/dev/null))"
fi

adb_ shell "rm -f /data/local/tmp/_k.png /data/local/tmp/_t.png /data/local/tmp/_s.png" >/dev/null 2>&1

echo
echo "=== 完成 ==="
ls -lh "$A"
echo
echo "现在跑：./video/make-video.sh"
echo "掌机实拍照片请自己放到：video/assets/hardware-closed.jpg / hardware-open.jpg"
