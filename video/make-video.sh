#!/bin/bash
# 生成 DuoPad 介绍视频（1920x1080，约 1 分钟），纯 FFmpeg 合成。
#
# 素材（只用自己的设备，不用第三方图；原实拍图不直接用，
# 因为原图上屏侧栏有用户名）：
#   video/assets/greenscreen.png   掌机照片，两块屏涂成纯绿
#   video/assets/screen-dual.mp4   上下拼接的双屏录屏（1920x2040@60fps）
#   video/assets/hw-*.jpg          compose-demo.py 产出的机身图（绿幕已合成）
#   video/assets/demo-*.mp4        真机操作片段
#
# 剪辑原则：一段一个信息点，不重复。宁可短，不要拖。
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
A=$HERE/assets
OUT=$HERE/out
mkdir -p "$OUT" "$A"

FONT=${FONT:-$(fc-match -f '%{file}' 'Noto Sans CJK SC' 2>/dev/null)}
[ -n "$FONT" ] && [ -f "$FONT" ] || FONT=/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc
W=1920; H=1080; FPS=30
C_ACC=0x7FB3E8; C_TXT=white; C_DIM=0xCCCCCC

# ---------- 时长 ----------
S1=7    # 机身 + 标题
S2=8    # 痛点
S3=12   # 三个设计决策
S4=10   # 真机：浏览器打中文
S5=7    # 真机：日常浏览
S6=8    # 开源
S7=6    # 收尾
TOTAL=$((S1+S2+S3+S4+S5+S6+S7))
echo "总时长 ${TOTAL}s"

ph() {  # ph <out> <color> <text>
    [ -f "$1" ] && return 0
    ffmpeg -y -v error -f lavfi -i "color=c=$2:s=${W}x${H}:d=1" -frames:v 1 \
        -vf "drawtext=fontfile=$FONT:text='[缺素材] $3':fontcolor=0x555555:fontsize=28:x=24:y=24" "$1"
    echo "  占位 $(basename "$1")"
}
ph "$A/hw-chat.jpg"   0x1B1B1E "机身图 hw-chat.jpg"
ph "$A/hw-browse.jpg" 0x1B1B1E "机身图 hw-browse.jpg"

# ---------- 1 开场 ----------
echo "=== 1 开场 ==="
ffmpeg -y -v error -loop 1 -t $S1 -i "$A/hw-chat.jpg" \
    -vf "scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},\
zoompan=z='min(zoom+0.0006,1.07)':d=1:s=${W}x${H},\
fade=t=in:st=0:d=0.7,drawtext=fontfile=$FONT:text='DuoPad':fontcolor=$C_ACC:fontsize=112:\
x=(w-text_w)/2:y=(h-text_h)/2-60,\
drawtext=fontfile=$FONT:text='Retroid Pocket Duo 下屏键盘 + 触摸板':fontcolor=$C_TXT:fontsize=46:\
x=(w-text_w)/2:y=(h-text_h)/2+60" \
    -r $FPS -pix_fmt yuv420p -c:v libx264 -crf 20 "$OUT/01.mp4"

# ---------- 2 痛点 ----------
echo "=== 2 痛点 ==="
ffmpeg -y -v error -loop 1 -t $S2 -i "$A/hw-browse.jpg" \
    -vf "scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},\
drawbox=y=0:h=ih:w=iw:color=black@0.6:t=fill,\
fade=t=in:st=0:d=0.4,\
drawtext=fontfile=$FONT:text='下屏是 1280×960 触摸屏':fontcolor=$C_TXT:fontsize=56:x=(w-text_w)/2:y=(h-text_h)/2-46,\
drawtext=fontfile=$FONT:text='安卓默认不把它当键盘，打字得外接':fontcolor=0xFF9A9A:fontsize=42:\
x=(w-text_w)/2:y=(h-text_h)/2+50" \
    -r $FPS -pix_fmt yuv420p -c:v libx264 -crf 20 "$OUT/02.mp4"

# ---------- 3 三个设计 ----------
echo "=== 3 三个设计 ==="
ffmpeg -y -v error -f lavfi -i "color=c=0x101014:s=${W}x${H}:d=$S3:r=$FPS" \
    -vf "fade=t=in:st=0:d=0.4,\
drawtext=fontfile=$FONT:text='键盘和触摸板是一个整体':fontcolor=$C_ACC:fontsize=58:x=180:y=262,\
drawtext=fontfile=$FONT:text='一起出现、一起收起；✕ 只收窗口，服务常驻':fontcolor=$C_DIM:fontsize=36:x=180:y=340,\
drawtext=fontfile=$FONT:text='触摸板是真鼠标':fontcolor=$C_ACC:fontsize=58:x=180:y=452,\
drawtext=fontfile=$FONT:text='uinput 注册 BUS_USB 设备，系统光标，不是画的箭头':fontcolor=$C_DIM:fontsize=36:x=180:y=530,\
drawtext=fontfile=$FONT:text='中文输入接 Rime':fontcolor=$C_ACC:fontsize=58:x=180:y=642,\
drawtext=fontfile=$FONT:text='候选浮在上屏光标下方；标点四态、长按连发':fontcolor=$C_DIM:fontsize=36:x=180:y=720" \
    -r $FPS -pix_fmt yuv420p -c:v libx264 -crf 20 "$OUT/03.mp4"

# ---------- 4 真机：浏览器 ----------
# demo-chat 的真实内容：上屏是 DeepSeek 的中文回答，下屏键盘在操作。
# 字幕只描述看得见的事，不写「终端打中文」这类画面撑不住的说法
# （终端那段 86s 之后紧接 fastfetch，弃用）
echo "=== 4 真机：打中文 ==="
ffmpeg -y -v error -i "$A/demo-chat.mp4" \
    -vf "scale=${W}:${H}:force_original_aspect_ratio=decrease,pad=${W}:${H}:(ow-iw)/2:(oh-ih)/2:color=0x101014,\
fade=t=in:st=0:d=0.4,drawtext=fontfile=$FONT:text='浏览器里中文提问，候选浮在上屏光标下方':fontcolor=$C_ACC:fontsize=40:\
x=(w-text_w)/2:y=h-86" -t $S4 \
    -r $FPS -pix_fmt yuv420p -c:v libx264 -crf 20 "$OUT/04.mp4"

# ---------- 5 真机：日常浏览 ----------
echo "=== 5 真机：日常浏览 ==="
ffmpeg -y -v error -i "$A/demo-browse.mp4" \
    -vf "scale=${W}:${H}:force_original_aspect_ratio=decrease,pad=${W}:${H}:(ow-iw)/2:(oh-ih)/2:color=0x101014,\
fade=t=in:st=0:d=0.4,drawtext=fontfile=$FONT:text='上屏当桌面，下屏负责输入':fontcolor=$C_ACC:fontsize=42:\
x=(w-text_w)/2:y=h-86" -t $S5 \
    -r $FPS -pix_fmt yuv420p -c:v libx264 -crf 20 "$OUT/05.mp4"

# ---------- 6 开源 ----------
echo "=== 6 开源 ==="
ffmpeg -y -v error -f lavfi -i "color=c=0x101014:s=${W}x${H}:d=$S6:r=$FPS" \
    -vf "fade=t=in:st=0:d=0.4,\
drawtext=fontfile=$FONT:text='开源':fontcolor=$C_ACC:fontsize=84:x=(w-text_w)/2:y=340,\
drawtext=fontfile=$FONT:text='github.com/brejce/duopad':fontcolor=$C_TXT:fontsize=52:x=(w-text_w)/2:y=480,\
drawtext=fontfile=$FONT:text='Rime · OpenCC · LevelDB · marisa · yaml-cpp':fontcolor=0x9A9AA6:fontsize=34:\
x=(w-text_w)/2:y=590" \
    -r $FPS -pix_fmt yuv420p -c:v libx264 -crf 20 "$OUT/06.mp4"

# ---------- 7 收尾 ----------
echo "=== 7 收尾 ==="
ffmpeg -y -v error -loop 1 -t $S7 -i "$A/hw-chat.jpg" \
    -vf "scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},\
drawbox=y=0:h=ih:w=iw:color=black@0.5:t=fill,fade=t=in:st=0:d=0.5,\
drawtext=fontfile=$FONT:text='DuoPad':fontcolor=$C_TXT:fontsize=98:x=(w-text_w)/2:y=(h-text_h)/2-46,\
drawtext=fontfile=$FONT:text='为双屏掌机补上缺失的那一半':fontcolor=0xD0D0D8:fontsize=42:\
x=(w-text_w)/2:y=(h-text_h)/2+56" \
    -r $FPS -pix_fmt yuv420p -c:v libx264 -crf 20 "$OUT/07.mp4"

# ---------- 拼接 ----------
echo "=== 拼接 ==="
: > "$OUT/list.txt"
for i in 1 2 3 4 5 6 7; do echo "file '$OUT/0$i.mp4'" >> "$OUT/list.txt"; done
ffmpeg -y -v error -f concat -safe 0 -i "$OUT/list.txt" \
    -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -movflags +faststart \
    "$HERE/duopad-intro.mp4"

# 音轨：配音 narration.wav（由 voiceover.py 生成，逐段对齐）+
# 可选 BGM（压低到 -22dB 垫底）
NAR=$HERE/narration.wav
BGM=$A/bgm.m4a
if [ -f "$NAR" ] && [ -f "$BGM" ]; then
    echo "=== 混音：配音 + BGM ==="
    ffmpeg -y -v error -i "$HERE/duopad-intro.mp4" -i "$NAR" -i "$BGM" \
        -filter_complex "[2:a]volume=-22dB,aloop=loop=-1:size=2e9[bg];[1:a][bg]amix=inputs=2:duration=first:dropout_transition=0[a]" \
        -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 160k -shortest "$OUT/_mux.mp4"
    mv "$OUT/_mux.mp4" "$HERE/duopad-intro.mp4"
elif [ -f "$NAR" ]; then
    echo "=== 混音：配音 ==="
    ffmpeg -y -v error -i "$HERE/duopad-intro.mp4" -i "$NAR" \
        -map 0:v -map 1:a -c:v copy -c:a aac -b:a 160k -shortest "$OUT/_mux.mp4"
    mv "$OUT/_mux.mp4" "$HERE/duopad-intro.mp4"
fi

echo
echo "=== 完成 ==="
ffprobe -v error -show_entries format=duration,size -of default=nw=1 "$HERE/duopad-intro.mp4"
