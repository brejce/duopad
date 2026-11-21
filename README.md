# DuoPad

**Retroid Pocket Duo（RP Duo / 口袋掌游 / 双屏掌机）的屏幕键盘与触摸板。**
下屏出键盘，驱动上屏的**真实系统鼠标**和中文输入；不需要外接键盘，
也不占用上屏任何画面。

> 关键词：Retroid Pocket Duo、RP Duo、口袋掌游、口袋 DS、双屏掌机、双屏安卓掌机、
> 下屏键盘、屏幕键盘、虚拟键盘、软键盘、触摸板、触控板、鼠标指针、中文输入法、
> Rime、librime、Android 悬浮窗、副屏键盘、second screen keyboard、touchpad。

### 关于名字：DuoPad 与 SplitKey

本项目曾叫 **SplitKey**，2026-11 改名为 **DuoPad**。
改名只动了**用户可见的名称**（桌面图标、通知、许可页、仓库名）。

以下这些**仍是 splitkey**，是故意保留的，不是漏改：

| 位置 | 值 | 为什么不动 |
|---|---|---|
| 包名 | `com.splitkey.ime` | 改包名要重编 `librime` 的 `.so`；且设备上会变成「新 App」，所有设置丢失 |
| logcat TAG | `SplitKey` | 排查命令（`adb logcat -s SplitKey`）和脚本都依赖它 |
| SharedPreferences | `splitkey` | 改名等于重置主题、震动、亮度设置 |
| uinput 设备名 | `SplitKey Virtual Mouse` | 已注册的设备名，改名会改变系统设备列表的稳定标识 |
| 设备端临时文件 | `/data/local/tmp/sk_*` | `sk_in` / `sk_mouse` 是脚本与 App 之间的 FIFO 约定 |
| 环境变量 | `LIBSPLITKEY_RIME_SO` | 已写进构建脚本，改了要连带改文档 |
| 产物文件名 | `build/splitkey.apk` | `device/install.sh` 按这个路径找 APK |
| 仓库目录 | 开发机上是 `~/Project/splitkey` | 个人习惯，和仓库名无关 |

所以看到 `com.splitkey` / `SplitKey` 时不用怀疑：**应用叫 DuoPad，内部标识还是 splitkey。**
这和「应用叫 VS Code、包名是 `com.microsoft.vscode`」是一回事。

### 为什么需要它

Retroid Pocket Duo 是翻盖双屏掌机，下屏是一块 1280×960 的触摸屏。
安卓本身不会把下屏当键盘用，模拟器/终端/浏览器在下屏打字只能靠外接键盘。
DuoPad 把下屏变成一块**屏幕键盘 + 笔记本式触摸板**，上屏就能当桌面用。

### 特性

- **键盘和触摸板是一个整体悬浮窗**，一起出现、一起消失；✕ 只收起窗口，服务常驻
- **真实系统鼠标光标**：走 uinput 虚拟鼠标，不是自绘箭头（自绘的追不上系统光标）
- **中文输入**：接 [librime](https://github.com/rime/librime)（Rime），候选胶囊浮在上屏光标下方
- **标点四态**：中文/英文/Shift 取反/Ctrl 强制半角，键面标签跟着变
- **长按连发**：⌫、空格、方向键按住持续触发；长按字母键出上方数字
- **触摸板可放大到整个下屏**（纯黑底，OLED 省电、防烧屏）
- **防烧屏**：整体亮度无极可调 + 闲置变暗/自动收起（这台机系统的烧屏保护是关的）
- **按键震动**，强度四档可调

仓库里**不带**任何词库或第三方二进制，全部靠脚本现场拉 —— 见第 2 节。

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

## 2. 准备环境

仓库里**不含**任何第三方二进制或词库，都靠脚本拉。需要的东西：

| 依赖 | 用途 | 怎么来 |
|---|---|---|
| build-tools | `aapt2/d8/zipalign/apksigner` | `scripts/fetch-buildtools.sh` |
| android.jar | 编译期 API | 同上（`platform-36`） |
| NDK r29 | 交叉编译 librime | `scripts/build-librime.sh` 自动下 |
| librime + 依赖 | 中文输入引擎 | 同上 |
| Rime 词库 | 拼音词典 | 同上（librime 自带 minimal 词库） |
| OpenCC 字典 | 繁→简转换 | 主机侧 `opencc` 包，`build.sh` 自动拷 |
| aarch64-linux-gnu-gcc | 编 uimouse | `device/install.sh` 会提示 |

一键：

```bash
./scripts/fetch-buildtools.sh    # build-tools + android.jar
./scripts/build-librime.sh       # NDK + librime + 依赖（首次 20~40 分钟）
```

## 3. 编译

```bash
./build.sh
# 产物 build/splitkey.apk
```

`build.sh` 全部路径可用环境变量覆盖：

```bash
ANDROID_BUILDTOOLS=/path/to/android-buildtools \
LIBSPLITKEY_RIME_SO=/path/to/libsplitkey_rime.so \
RIME_DATA=/path/to/rime/data/minimal \
./build.sh
```

默认值：

| 变量 | 默认 |
|---|---|
| `ANDROID_BUILDTOOLS` | `~/Project/android-buildtools` |
| `BT` | `$ANDROID_BUILDTOOLS/android-14` |
| `PLATFORM` | `$ANDROID_BUILDTOOLS/android-36` |
| `LIBSPLITKEY_RIME_SO` | `~/Project/splitkey-librime-build/out/libsplitkey_rime.so` |
| `RIME_DATA` | `~/Project/splitkey-librime-build/librime/data/minimal` |
| `OPENCC_DATA` | `/usr/share/opencc` |

词库和 OpenCC 字典都**不进仓库**（体积 + 许可证），编的时候从本地拷进 `assets/`，
这个目录也在 `.gitignore` 里。

## 4. 部署

```bash
ADB_SERIAL=192.168.1.10:5555 ./device/install.sh
```

它做四件事（顺序不能乱）：

1. 交叉编译并推 `uimouse`（虚拟鼠标，C 程序）
2. 推 `sk_daemon.sh`（按键注入 daemon）
3. `adb install -r` 装 APK
4. **`ime set com.splitkey.ime/.HiddenIme`** —— 重装 APK 会把默认输入法重置回
   Gboard，不重设的话 `commitText` 无处可去，中文打不出来
5. 拉起 uimouse + daemon + `KeyboardService`

开机自启：`device/splitkey-boot.sh`（`sleep 15` 后拉起上面几项），
自己放到设备的开机脚本里。

后台进程**必须断掉 stdin**（`</dev/null`），否则 `adb shell` 会一直挂着不返回。

---

## 5. 日常使用

| 操作 | 效果 |
|---|---|
| 点桌面 DuoPad 图标 | 强制显示键盘（`pin=true`） |
| 上屏点可编辑输入框 | 焦点广播 → 键盘自动出现 |
| 点左上角 `✕` | **只收起键盘，服务不死** |

**`✕` 只是隐藏，不是退出。** 服务一直在跑，所以点回输入框键盘立刻回来。
这条是踩过坑的：以前 `✕` 调 `stopSelf()`，服务死了之后焦点广播没人接，键盘永远出不来。

键盘和触摸板是**一个整体窗口**（`kbRoot`），一起出现、一起消失，不拆开。

---

## 6. 键盘

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

## 7. 触摸板

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

## 8. 鼠标注入（最容易踩坑的部分）

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

## 9. 输入是怎么注入的（以及游戏兼容性）

两条通道**性质完全不同**，别混着理解。

### 触摸板 = 真正的鼠标设备

`device/uimouse.c` 用 `/dev/uinput` 注册了一台 **BUS_USB 物理鼠标**：

```c
usetup.id.bustype = BUS_USB;
usetup.id.vendor  = 0x1234;
usetup.id.product = 0x5678;
strcpy(usetup.name, "SplitKey Virtual Mouse");
```

系统的设备列表里能看到它：

```
36: SplitKey Virtual Mouse
    Classes: CURSOR | EXTERNAL
    Identifier: bus=0x0003, vendor=0x1234, product=0x5678
    Capabilities: BTN_LEFT BTN_RIGHT BTN_MIDDLE REL_X REL_Y REL_WHEEL REL_HWHEEL
```

所以它**就是鼠标**，不是触摸事件的伪装。这也是唯一能画出系统光标的路子：
自绘箭头追不上系统光标的速度曲线（早期版本做过，已删）。

### 键盘 = 逐次事件注入，不是设备

键盘走 root 的 `input` 命令（`device/sk_daemon.sh` 从 FIFO 读命令）：

```sh
TXT:*)   input text "${line#TXT:}" ;;        # ASCII 文本
KEY:*)   input keyevent "${line#KEY:}" ;;    # 单键
COMBO:*) input keycombination $args ;;       # 组合键（Ctrl/Alt/Shift + 键）
```

`input` 通过 `INJECT_INPUT_EVENT` 走 `InputManager.injectInputEvent()`，
**不注册任何输入设备**。`dumpsys input` 的 12 个设备里没有我们创建的键盘。

| | 触摸板 | 键盘 |
|---|---|---|
| 通道 | `/dev/uinput` 注册设备 | `input` 命令逐次注入 |
| 系统看到的 | 一台 BUS_USB 鼠标（`EXTERNAL`） | 无设备，只有事件 |
| 光标 | 有，系统原生 | — |
| 持续性 | 设备常驻，状态连续 | 每次注入独立，无设备状态 |

### 会被当成键鼠吗

**会。** 有鼠标设备存在 + 光标可见，应用层就能判断出「有键鼠」。这是应用
自己的决定（`Input.getDeviceType()`、光标可见性、Unity/Unreal 的输入后端探测），
系统不会替它判断。

### 手柄还能用吗

**能。** 依据：

```
9: Retroid Pocket Controller
    Classes: KEYBOARD | GAMEPAD | JOYSTICK | EXTERNAL
```

手柄是独立的物理设备，我们**没有注册任何会抢它位置的设备**；
`input keyevent` 也不建立持续的设备状态，不会改动厂商 ID 或设备映射。

「被识别成键鼠」和「手柄不能用」是两件事 —— 前者是应用层看到光标，
后者需要输入设备冲突才会发生。

### 已知的兼容性风险（未实测游戏）

本项目的目标是**安卓软件 + 远程桌面**（Chrome、Termux、Moonlight、
各种远程桌面客户端），不是手游。下面是按实现推断的风险，不是实测结论：

- **远程桌面 / 浏览器 / 终端**：没问题。它们只关心字符与鼠标事件，
  这正是我们注入的东西。Termux 的 `inputType=TYPE_NULL` 有专门处理
  （`HiddenIme.TERMINAL_PKGS` 白名单，见第 13 节）。
- **`input keyevent` 无设备来源**：注入事件的 `deviceId = -1`。
  少数引擎（部分 Unity 手游）会把这类「模拟输入」当噪声忽略。
  如果某个 App 收不到按键但能收到鼠标，多半是这个原因。
- **检测到鼠标就切键鼠 UI**：部分手机会因此隐藏屏幕手柄提示或切换 UI 布局。
  这是游戏自己的策略，不是设备被占用。
- **组合键走 `input keycombination`**：需要较新的 Android（本项目实测
  android-13 基线可用）。老系统上 Ctrl/Alt 组合键可能失效。
- **中文不走键盘通道**：非 ASCII 文字通过隐藏 IME 的 `commitText` 上屏，
  和上面两条都无关。所以中文能进的地方，英文一定也能进。

---

## 10. 焦点与生命周期（关键设计）

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

## 11. 排查

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
| 键盘不出现 | `✕` 杀过服务 / 小球拦截触摸 / 输入法没设成 `com.splitkey.ime/.HiddenIme` |
| 有事件无光标 | `capabilities/rel` 不是 143；或 touch mode = 1 |
| 双指滑完指针跑 | 多指结束剩的手指被当指针（`suppressPointer` 负责拦） |
| 三指很难触发 | 用 `onUp` 的单指坐标算位移（应看 MOVE 阶段重心） |
| 返回手势要点一下才生效 | 起点不在边缘带（`mEdgeWidthLeft=69px`）内 |

---

## 12. 文件清单

```
src/com/splitkey/ime/
  KeyboardService.java  常驻服务：窗口、焦点、胶囊、触摸板接线                                  (653 行)
  KeyboardView.java     键盘渲染 + 命中判定 + 长按连发                                   (600 行)
  TouchpadView.java     触摸板手势 + 光标/滚轮                                        (596 行)
  RimeEngineImpl.java   librime 会话：输入、候选、提交                                  (264 行)
  CapsuleView.java      候选胶囊（无焦点时纯透明）                                        (238 行)
  HiddenIme.java        0 高度 IME：commitText + 焦点广播                           (256 行)
  Theme.java            明/暗主题（自动跟随系统 / 手动固定）                                 (170 行)
  PageActivity.java     设置页与许可页的基类                                           (145 行)
  KeyboardLayout.java   布局纯数据（改布局只动这里）                                       (126 行)
  Shuttle.java          输出分发：ASCII→FIFO，Unicode→HiddenIme                    (149 行)
  RimeNative.java       JNI 声明                                               (77 行)
  SettingsActivity.java 设置界面                                                 (66 行)
  KeySpec.java          键声明                                                  (84 行)
  RimeEngine.java       引擎接口 + NULL 空实现                                      (52 行)
  LicensesActivity.java 第三方许可原文                                              (68 行)
  ImeBinder.java        让系统真正绑定 HiddenIme（ime set）                           (51 行)
  Launcher.java         桌面图标：拉起服务后立刻退出                                       (29 行)
  OutputChannel.java    统一输出接口                                               (22 行)
```

```
jni/splitkey_rime.cpp        JNI 桥：librime 会话、选项、上下文
scripts/build-librime.sh     交叉编译 librime + 依赖，链接出 .so
scripts/fetch-buildtools.sh  下载 build-tools + android.jar
build.sh                     编 APK（aapt2 + javac + d8 + apksigner，无 Gradle）
build-injector.sh            编/部署 injectInputEvent 注入器（调试用）

device/uimouse.c             虚拟鼠标（uinput），需交叉编译
device/sk_daemon.sh          按键注入 daemon（FIFO 读命令）
device/splitkey-boot.sh      开机自启
device/install.sh            一键：编 uimouse + 推文件 + 装 APK + 设 IME + 拉起
```

---

## 13. 已知未完成

- **候选胶囊点击选择**：`wordRects` 在 `onDraw` 里算，点击命中可能滞后。
  键盘侧已经能选（左右键移高亮 + 数字键直选），所以这条优先级不高
- **`✕` 之后进程常驻**：没有「彻底退出」的入口，只能 `am force-stop`
- **游戏兼容性未实测**：本项目面向安卓软件与远程桌面，没在手游上验证过。
  第 9 节列的是按实现推断的风险（`input keyevent` 的 `deviceId=-1`、
  应用检测到鼠标后切换键鼠 UI），不是实测结论
- **终端类 app 白名单写死**：`HiddenIme.TERMINAL_PKGS` 只放了 `com.termux`。
  别的终端 app（同样用 `inputType=TYPE_NULL`）要自己加，否则看不到候选
- **Termux 里胶囊位置固定**：它不支持 `requestCursorUpdates`（返回 false），
  拿不到插入符坐标，胶囊只能落在 `screenH * 0.62`，不跟随光标
- **调试日志未清**：`rime input`、`ctx preedit`、`drainCommit`、`opts`、
  `commitRemote`、`onChar`、`fire a=` 等仍是 `Log.e`，
  过滤 `adb logcat -s SplitKey`

---

## 14. 第三方组件

`libsplitkey_rime.so` 静态链接下面这些组件。全部是宽松许可（无 GPL），
不要求 DuoPad 自身开源，但要求随二进制保留声明。

| 组件 | 许可 |
|---|---|
| librime | BSD-3-Clause |
| Boost | Boost 1.0 |
| LevelDB | BSD-3-Clause |
| yaml-cpp | MIT |
| marisa-trie | BSD-2-Clause **或** LGPL-2.1-or-later（本项目取 BSD-2）|
| OpenCC | Apache-2.0 |

完整许可文本在 `licenses/`。发 APK 时把该目录一起发出去（或在 app 内提供
「开源许可」页面）。

**词库单独算**：`luna_pinyin` 等输入方案由 Rime 社区维护，许可与引擎不同，
打包前需逐个确认。
