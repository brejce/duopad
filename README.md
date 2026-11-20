# SplitKey 手册

双屏掌机（Retroid Pocket Duo）的下屏键盘 + 触摸板，驱动上屏的**真实系统鼠标**和按键输入。

- 上屏 = `displayId 0`，1080×1920（旋转后 1920×1080）
- 下屏 = `displayId 2`，1280×960，触摸设备 `/dev/input/event6`（`fts_ts_sec`）

---

## 1. 它由三部分组成

```
┌─────────────────── 下屏 ────────────────────┐
│  KeyboardService (常驻悬浮窗)                │
│    ├── KeyboardView   键盘渲染               │
│    └── TouchpadView   笔记本式触摸板          │
└──────────┬────────────────────┬─────────────┘
           │ /data/local/tmp/sk_in      │ /data/local/tmp/sk_mouse
           ▼                    ▼
   sk_daemon.sh            uimouse (C 程序)
   (input text/keyevent)   (uinput 虚拟鼠标)
           │                    │
           └────────┬───────────┘
                    ▼
               上屏（系统）
                    ▲
                    │ commitText / 焦点广播
┌───────────────────┴─────────────────────────┐
│  HiddenIme (0 高度 IME，只负责 commit + 焦点) │
└─────────────────────────────────────────────┘
```

为什么分三条路：

| 类型 | 通道 | 原因 |
|---|---|---|
| ASCII | `sk_in` → `input text` | 走系统注入 |
| 中文/Unicode | `HiddenIme.commitText()` | KeyEvent 无法表达非 ASCII |
| 鼠标/滚轮 | `sk_mouse` → uinput | **只有 uinput 才画出系统光标**，`injectInputEvent` 画不出来 |

---

## 2. 编译

### APK（无 Gradle）

```bash
cd ~/Project/splitkey
./build.sh
# 产物 build/splitkey.apk
adb install -r build/splitkey.apk
```

`build.sh` 用 `~/Project/android-buildtools/android-14` 的 `aapt2/d8/zipalign/apksigner`，
`android-36` 的 `android.jar`。

### uimouse（虚拟鼠标，C 程序）

源码：`~/.hermes/cache/scratch/uimouse.c`

```bash
sudo pacman -S aarch64-linux-gnu-gcc
aarch64-linux-gnu-gcc -static -O2 -o uimouse uimouse.c
adb push uimouse /data/local/tmp/uimouse
adb shell "su -c 'chmod 755 /data/local/tmp/uimouse'"
```

**必须用 libc 编译，不要手写 `svc #0`。** 见第 7 节。

---

## 3. 部署

首次或重装后：

```bash
# 1. 输入法（重装 APK 会把默认输入法重置回 Gboard）
adb shell "su -c 'ime set com.splitkey.ime/.HiddenIme'"

# 2. 虚拟鼠标
adb shell "su -c 'setsid /data/local/tmp/uimouse >/dev/null 2>&1 </dev/null &'"

# 3. 按键注入 daemon
adb shell "su -c 'setsid sh /data/local/tmp/sk_daemon.sh >/dev/null 2>&1 </dev/null &'"

# 4. 键盘悬浮窗（点桌面 SplitKey 图标也行）
adb shell "am start-foreground-service -n com.splitkey.ime/.KeyboardService"
```

开机自启脚本：`~/.hermes/cache/scratch/splitkey-boot.sh`（`sleep 15` 后拉起上面四步）。

---

## 4. 日常使用

| 操作 | 效果 |
|---|---|
| 点桌面 SplitKey 图标 | 强制显示键盘（`pin=true`） |
| 上屏点可编辑输入框 | 焦点广播 → 键盘自动出现 |
| 点左上角 `✕` | **只收起键盘，服务不死** |

**`✕` 只是隐藏，不是退出。** 服务一直在跑，所以点回输入框键盘立刻回来。
这条是踩过坑的：以前 `✕` 调 `stopSelf()`，服务死了之后焦点广播没人接，键盘永远出不来。

键盘和触摸板是**一个整体窗口**（`kbRoot`），一起出现、一起消失，不拆开。

---

## 5. 键盘

布局纯数据在 `KeyboardLayout.java`，改布局只动这个文件。

- 两层：`ALPHA`（字母）/ `SYMBOL`（数字符号），底行 `&123` / `ABC` 切换
- 每行总权重对齐到 11.4，所以各行列对齐（TAB 正好在 q 上方）
- 副标签：字母键上方显示数字

修饰键粘滞（`Ctrl`/`Alt`/`⇧`）：

- 单击 = **一次性**（浅蓝），下一个键带上后自动熄灭
- 再点 = **锁定**（深蓝），一直亮
- 再点 = 关闭

`KeySpec` 的 action 四种：

```
char:q      字符
key:67      Android keyCode
mod:shift   修饰键
none        空位（占列不画不可点）
```

---

## 6. 触摸板

`TouchpadView.java`。对齐 Windows 精密触摸板：

| 手势 | 动作 |
|---|---|
| 单指滑动 | 移动指针 |
| 单指点按 | 左键 |
| 单指双击（第二下不松手） | 进入拖动 |
| 双指点按 | 右键 |
| 双指上下滑 | 垂直滚动（带惯性） |
| 双指左右滑 | 横向滚动 |
| 三指点按 | 中键 |
| 三指上下滑 | 上=任务视图 下=回桌面 |
| 三指左右滑 | 左=上一个应用 右=下一个应用 |
| 右侧滚轮条 | 垂直滚动（辅助） |

关键实现点，都是踩坑换来的：

**方向锁定** — 双指落下后看哪个轴先超过 `AXIS_LOCK_PX`，锁死该轴，另一轴抖动不再触发。这是笔记本手感的核心。

**多指后剩下的手指不认指针**（`suppressPointer`）
多指手势结束时，`lastX/lastY` 还停在双指平均值上；下一帧剩一根手指时，`dx = 单指绝对位置 - 双指平均`，几十像素的假位移直接变成指针跳。
所以任意 `POINTER_DOWN` 到 2 根及以上就置位，单指分支直接 return，**不动 `lastX/lastY`**，直到全部抬起。
niri / libinput 也是这个规则。

**三指滑动判定用 MOVE 阶段的重心**（`threeLastX/threeLastY`）
不能在 `onUp` 里算：那一刻其它手指已经抬起，`avgY(e)` 只剩一根手指，和三指重心差七八十像素，
`> SWIPE_MIN_PX` 的门槛几乎永远够不到 —— 表现就是"三指很难触发"。
只在 `n >= 3` 的 MOVE 里更新重心。

**点按不判时长**
注入/慢速环境下 `ACTION_UP` 可能远晚于 240ms，但那仍然是一次点按。
所以 `wasTap = !moved`，不看 `downTime`。

**惯性** — `INERTIA_DECAY=0.93`，`INERTIA_MIN=0.04 px/ms`，16ms 一帧。

---

## 7. 鼠标注入（最容易踩坑的部分）

### 只有 uinput 能画出系统光标

| 路线 | 光标 | 说明 |
|---|---|---|
| `injectInputEvent(SOURCE_MOUSE)` | ❌ 永远画不出来 | 绕过了 CursorInputMapper，只剩 hover 效果 |
| uinput `/dev/uinput` | ✅ | 走物理设备路径 |

`injector/com/splitkey/Injector.java` 是**废弃路线**，留着仅供 hover 测试，不要用它做光标。

### uimouse 协议（`/data/local/tmp/sk_mouse`，每行一条）

```
M:dx,dy     相对移动
C           左键单击
R           右键单击
B           中键单击
D / U       左键按下 / 松开（拖动）
W:d         垂直滚轮（正=向上）
H:a         横向滚轮（正=向右）
```

### 必须用 libc 编译

freestanding 里手写 `svc #0` 调 `ioctl(UI_SET_RELBIT, REL_X)`，`arg=0` 会被当成无效值丢掉，
内核侧位偏移，得到 `rel=218`（REL_RX/REL_RY/REL_MISC）而不是 `143`（REL_X/REL_Y/REL_WHEEL/REL_HWHEEL）。
设备类就降级成 `CURSOR_ICON`，事件进得去、光标不出来。

```bash
# 权威判据
adb shell "su -c 'cat /sys/class/input/eventN/device/capabilities/rel'"
# 143 ✅
# 218 ❌
```

`INPUT_PROP_POINTER` 不需要设，真鼠标的 props 也是 `<none>`。

### touch mode

`dumpsys input | grep 'TouchMode'` 里 display 0 是 `1` 时，光标被强制隐藏。
**只有鼠标点击能退出 touch mode，hover/移动不能。**
这是 Android 设计，不是 bug。

---

## 8. 焦点与生命周期（关键设计）

```
上屏输入框获得焦点
    ↓ HiddenIme.onStartInput / onStartInputView
    ↓ sendFocus(true)  ──广播──▶  KeyboardService
    ↓                                  ├── toggleKeyboard(true)  显示
    └── ensureKeyboard()               └── updateCapsule()
        拉起服务（如果被 ✕ 停过）
```

四条规则：

1. **只开不关**：`applyFocus(on)` 里 `if (on) toggleKeyboard(true)`。焦点丢失不隐藏，
   否则触摸板跟着消失，你就没法把指针挪回来 —— 死锁。
2. **`✕` 只隐藏不杀服务**。
3. **`onStartInputView` 也发焦点**：点回同一个输入框时 `onStartInput` 不会重发。
4. **`ensureKeyboard()` 兜底**：`sendFocus(true)` 时顺手 `startForegroundService`，服务死了也能起来。

### 上屏小球（CapsuleView）

候选胶囊，折叠态（无候选）是**纯透明占位**：

- 不绘制任何东西
- `FLAG_NOT_TOUCHABLE` —— 否则它会吃掉点上屏输入框的事件，输入框拿不到焦点，键盘起不来
- 只有展开态（有候选词）才可触摸，才能点选候选

它存在的意义只有两个：承载候选词，以及给上屏一个可以挂焦点广播的窗口。

---

## 9. 排查

```bash
# 键盘/焦点/鼠标决策
adb logcat -s SplitKey

# 鼠标事件原始流（确认 uimouse 收到了）
adb shell "su -c 'getevent -lt /dev/input/event10'"   # SplitKey Virtual Mouse

# 真鼠标设备有没有被认对
adb shell "su -c 'dumpsys input | grep -A4 \"SplitKey Virtual Mouse\"'"
#    Classes: CURSOR      ✅
#    Classes: CURSOR_ICON ❌

# 手势有没有被 SystemUI 接受
adb shell "dumpsys activity service com.android.systemui/.SystemUIService | grep -oE 'Gesture \[[0-9]{13}[^]]*'"
#    alw=TRUE + left=TRUE  = 接受了、且识别为左边缘
```

常见现象对照：

| 现象 | 原因 |
|---|---|
| 键盘不出现 | `✕` 杀过服务 / 小球拦截触摸 / 输入法没设成 SplitKey |
| 有事件无光标 | `capabilities/rel` 不是 143；或 touch mode = 1 |
| 双指滑完指针跑 | 多指结束剩的手指被当指针（`suppressPointer` 负责拦） |
| 三指很难触发 | 用 `onUp` 的单指坐标算位移（应看 MOVE 阶段重心） |
| 返回手势要点一下才生效 | 起点不在边缘带（`mEdgeWidthLeft=69px`）内 |

---

## 10. 文件清单

```
src/com/splitkey/ime/
  KeyboardService.java  常驻服务：窗口、焦点、胶囊、光标、触摸板接线   (547 行)
  TouchpadView.java     触摸板手势                                    (545 行)
  KeyboardView.java     键盘渲染 + 命中判定                            (393 行)
  HiddenIme.java        0 高度 IME：commitText + 焦点广播              (228 行)
  CapsuleView.java      候选胶囊（折叠态纯透明）                       (198 行)
  Shuttle.java          输出分发：ASCII→FIFO，Unicode→广播             (144 行)
  KeyboardLayout.java   布局纯数据（改布局只动这里）                   (111 行)
  CursorView.java       自绘光标（系统光标通了之后可删）                 (55 行)
  KeySpec.java          键声明                                        (54 行)
  RimeEngine.java       输入法引擎占位（现在返回 NULL）                  (40 行)
  Launcher.java         桌面图标：拉起服务后立刻退出                     (32 行)
  OutputChannel.java    统一输出接口                                    (22 行)

injector/com/splitkey/
  Injector.java         injectInputEvent 路线（废弃，只供 hover 测试）
  MouseTest.java        一次性验证脚本
```

配套设备端脚本（不在本仓库）：

```
~/.hermes/cache/scratch/uimouse.c           虚拟鼠标源码（需交叉编译）
~/.hermes/cache/scratch/sk_daemon.sh        按键注入 daemon
~/.hermes/cache/scratch/splitkey-boot.sh    开机自启
```

---

## 11. 已知未完成

- **Rime 未接入**：`RimeEngine.NULL`，候选词是假的
- **`CursorView` 自绘光标仍在**：系统光标通了以后应该删掉，两个光标速度/曲线不同
- **中文路径**：`Shuttle.commitRemote()` → 广播 → `HiddenIme.commitText()` 通了，但没接引擎
- **`✕` 之后进程常驻**：没有"彻底退出"的入口
