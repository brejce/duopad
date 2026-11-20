#!/system/bin/sh
# SplitKey 注入守护进程
# 协议（每行一条）：
#   TXT:<文本>        -> input text
#   KEY:<code>        -> input keyevent
#   COMBO:<m1,m2,k>   -> input keycombination m1 m2 k
# 中文不走这里，走广播给隐藏 IME commitText

FIFO=/data/local/tmp/sk_in
LOG=/data/local/tmp/sk_daemon.log

rm -f "$FIFO"
mkfifo "$FIFO"
chmod 666 "$FIFO"

# 读写方式打开，FIFO 永远有读者，app 写不会阻塞
exec 3<> "$FIFO"

echo "daemon start $(date)" > "$LOG"

while read -r line <&3; do
    [ -z "$line" ] && continue
    case "$line" in
        TXT:*)   input text "${line#TXT:}" ;;
        KEY:*)   input keyevent "${line#KEY:}" ;;
        SWIPE:*)
            a="${line#SWIPE:}"
            x1="${a%%,*}"; a="${a#*,}"
            y1="${a%%,*}"; a="${a#*,}"
            x2="${a%%,*}"; a="${a#*,}"
            y2="${a%%,*}"; ms="${a#*,}"
            input swipe "$x1" "$y1" "$x2" "$y2" "$ms"
            ;;
        TAP:*)
            xy="${line#TAP:}"
            input tap "${xy%,*}" "${xy#*,}"
            ;;
        LONG:*)
            xy="${line#LONG:}"
            lx="${xy%,*}"; ly="${xy#*,}"
            input motionevent DOWN "$lx" "$ly"
            sleep 0.6
            input motionevent UP "$lx" "$ly"
            ;;
        ROLL:*)
            # 走 uinput 虚拟鼠标，避免每次 fork input 的 40ms
            echo "W:${line#ROLL:}" > /data/local/tmp/sk_mouse 2>/dev/null
            ;;
        COMBO:*)
            args=$(echo "${line#COMBO:}" | tr ',' ' ')
            input keycombination $args
            ;;
        *)       continue ;;
    esac
    echo "done[$?]: $line" >> "$LOG"
done
