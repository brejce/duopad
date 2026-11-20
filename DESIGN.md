# SplitKey 接口设计

## 总原则

**下屏键盘不知道也不关心字符最终怎么进上屏。** 它只调用一个统一的输出接口，由 Shuttle 决定走哪条路（注入按键 / 提交文本）。这样加中文、加剪贴板、加 Rime 全都不用动键盘代码。

## 三层

```
┌──────────────────────────────────────┐
│ KeyboardView (下屏 UI)               │
│   按下 'a' → out.sendText("a")       │
│   按下 中文  → out.sendText("你")     │
└──────────────┬───────────────────────┘
               │ 统一接口
┌──────────────▼───────────────────────┐
│ Shuttle (分发器，跑在下屏进程)        │
│   sendText(str)                      │
│   sendKey(KeyEvent)                  │
│   sendBackspace() / sendEnter() ...  │
│   setCandidates(List) / clear()      │
└──────────────┬───────────────────────┘
               │
   ┌───────────┴────────────┬──────────────────┐
   ▼ ASCII                   ▼ 中文              ▼ 特殊
 injectInputEvent      broadcast → 隐藏IME    broadcast
 (KeyEvent)            commitText()          (功能键)
```

## 为什么分两条路

| 类型 | 通道 | 原因 |
|---|---|---|
| ASCII (a-z, 0-9, 标点) | `injectInputEvent(KeyEvent)` | 走 InputDispatcher，天然到焦点窗口，无延迟 |
| 中文 / emoji / 任意 Unicode | `broadcast → 隐藏IME.commitText()` | KeyEvent 无法表达非 ASCII |
| Backspace / Enter / 方向键 | `injectInputEvent(KeyEvent)` | 同上，是真正的按键 |

**接口对调用者是透明的**：`sendText("a")` 和 `sendText("你")` 走不同内部路径，但签名相同。

## 接口定义

```java
public interface OutputChannel {
    /** 发送任意文本。ASCII 走注入，非 ASCII 走 IME 提交。 */
    void sendText(String text);

    /** 发送功能键（DEL/ENTER/TAB/方向键等） */
    void sendKey(int keyCode);

    /** 组合键（Ctrl+C 之类） */
    void sendKeyWithMeta(int keyCode, int metaState);

    /** 更新上屏胶囊里的候选词。空列表 = 隐藏胶囊。 */
    void setCandidates(List<String> candidates);
}
```

## 隐藏 IME 侧的协议（广播契约）

下屏 → 上屏，只用一个 action 空间，前缀固定：

```
action: com.splitkey.ime.CMD
extra:  "op"   = commit | candidates | ping
extra:  "text" = 要提交的文本 / JSON 候选数组
```

| op | 作用 |
|---|---|
| `commit` | `commitText(text)` 到当前光标 |
| `candidates` | 更新胶囊显示（JSON: `["你","尼","泥"]`） |
| `ping` | 探测 IME 是否在线（返回广播） |

**为什么用广播而不是 AIDL/Binder**：跨进程、无需绑定服务、IME 随时可能被系统重启，广播天然容错。延迟在 5-20ms，打字场景可接受。

## 中文接入点（现在不实现，只留位置）

```
KeyboardView 按下拼音键 'n', 'i'
    ↓
RimeEngine.input("ni")  ← 现在返回 null
    ↓ 有候选时
shuttle.setCandidates(["你","尼","泥"])
shuttle.sendText("你")   ← 用户选词后
```

**RimeEngine 是一个接口，现在给个空实现**，后面把 librime JNI 塞进去就行，键盘和 Shuttle 代码一行不用改。

## 关键约束（写代码时要守住）

1. **下屏键盘窗口必须 `FLAG_NOT_FOCUSABLE`**，否则焦点被抢走，注入的按键会打在键盘自己身上。
2. **胶囊是独立的 PopupWindow，`setTouchable(true)` 但 `NOT_FOCUSABLE`**，点候选不丢输入框焦点。
3. **ASCII 注入和广播提交不能同时用一条消息**，避免重复输入（刚才「你好世界」出现两遍就是重复的教训）。
