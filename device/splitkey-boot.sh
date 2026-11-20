#!/system/bin/sh
# SplitKey 开机自启：虚拟鼠标 + daemon + 键盘服务 + 输入法
sleep 15

# 1. 虚拟鼠标（系统光标）
setsid /data/local/tmp/uimouse >/dev/null 2>&1 </dev/null &

# 2. 按键注入 daemon
setsid sh /data/local/tmp/sk_daemon.sh >/dev/null 2>&1 </dev/null &

sleep 3

# 3. 输入法（重装/重启后系统可能重置）
ime set com.splitkey.ime/.HiddenIme >/dev/null 2>&1

# 4. 键盘悬浮窗
am start-foreground-service -n com.splitkey.ime/.KeyboardService >/dev/null 2>&1
