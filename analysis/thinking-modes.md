# 原生默认 / 思考模式配置边界

目标仍为本机小爱 `com.miui.voiceassist` 7.11.20.3514 / 507011020，APK SHA-256 `ae216062a595e58b0ef6fe7969de2696e00e427d5fd9c2c5d2e4ac07acb18e06`。以下是复用现有 JADX 导出的静态证据，不是新的 REA 方法 Evidence 或运行命中记录；整体反编译覆盖限制同 `progress.md`。

## 模式的真实来源

1. `ti0.h` 是 `DeepThinkingStateHelper`，原始单例字段为 **`a`**，JADX 的 `f112211a` 只是别名。`isDeepThinking()` 在实验组读取 `DeepThinkingStrategy`，其他组读取进程状态。见 `ti0/h.java:32-56,273-275`。
2. `StandardEditInputView.onCheckedChanged` 对 `btn_deep_think` 调 `ti0.h.a.setDeepThinking(checked,true)`；展开输入框和 functionbar 的 `DEEP_THINK` 项也调用同一 helper。该模式不是从用户问题包含“思考”等文字推测的。见 `StandardEditInputView.java:531-542`、`mainui/functionbar/b.java:1190-1194`。
3. `com.xiaomi.voiceassist.engine.context.b.generateDialogStateContext()` 生成 `Dialog.DialogState.deepseek_switch`。其准确公式为：`(isDeepThinkingStateOpen() || reAnswerByDeepThinking) && !directAnswer`。见 `engine/context/b.java:729-749`。
4. SDK 的公开读取方法是 `Dialog.DialogState.isDeepseekSwitch()`，结果是 optional boolean。`Nlp.RequestLargeLanguageModelContent.change_llm_vendor` 是另外一个供应商切换字段，不作为思考模式判断依据。
5. “深度思考重答”可临时设置 `setReAnswerByDeepThinking(true)`；“直接回答”临时设置 `setDirectAnswer(true)`，后者约 1500ms 后复位。只读持久 UI 开关会漏掉这些原生行为。见 `instruction/card/stream/c.java:319-321,636-651`。
6. `ti0.h.updateDeepThinkStateContext()` 可以通过 `j30.h` 生成并单独上传 DialogState。因此缺少随请求携带的 DialogState 时，仍需读取真实当前生成器，而不是把缺字段当作默认模式。

## 新增代码

- `ThinkingMode.isThinking(Object event, ClassLoader loader)`：先读该事件的 `Dialog.DialogState` context 与 `isDeepseekSwitch()`；字段不存在时，调用上述真实 `generateDialogStateContext()`。生成器内部包含 UI helper 与两个一次性覆盖，方法没有改写 UI 状态或伪造事件。
- `ModeProfiles.resolve(JSONObject config, String route, boolean thinking)`：只允许 `chat` / `voice` 使用各自的 `thinking` 子配置。子配置 `enabled=true` 时完整独立使用；不存在 / 禁用时沿用默认。`image` / `control` 不受此模式切换影响。没有把默认 Token、endpoint 或 System Prompt 隐式并入思考子配置。

文本请求应在 outgoing `Nlp.Request` / `Nlp.RequestLargeLanguageModelContent` 的同一事件上读取模式；先确定路由，仅文本 / 语音路由执行模式检测。

语音应在 outgoing `SpeechRecognizer.Recognize` 时捕获模式，并与该 dialog 的附件一起保存为 pending 数据；ASR final instruction 没有同样的事件上下文，使用原识别请求的模式快照解析当前配置，而非在 final 临时读取可能变化的 UI 状态。

## 测试建议与状态

`ModeProfilesTest` 先 red、再 green，覆盖旧配置兼容、独立字段、禁用回落、仅启用语音思考配置、不继承默认秘密字段、Codex pinned endpoint、图像 / 控制不被替换、启用配置校验。

运行集成验证由主任务统一进行，当前子任务没有操作设备或读取凭据。优先点击真实 `com.miui.voiceassist:id/btn_deep_think`。如果该机服务端菜单不显示此项，debug nonce 限定的测试可以调用同一真实 helper，再通过正常请求链观察实际模型分流；不得伪造最终模式检测结果。

测试若临时改变原生开关，应先记录原 `isDeepThinking()` 并最终用 `setDeepThinking(old,true)` 还原。实验组中 `isUserTrigger=false` 会读取并保留持久状态，因此还原时使用用户触发形式。

已准备 `scripts/thinking_mode_device_test.py` 与 debug `FixtureSupport` 的 nonce 限定 `thinking_mode` 动作。动作仅调用上述真实 helper，并回报 request UUID、旧值、当前值及实际生效值；release no-op 未修改。脚本默认保持原生主页面前台，依次验证文本和语音文本入口的默认 → 思考 → 默认，共六次真实 HTTP 请求，检查两套模型源 / Prompt 与用户可见历史；不合成 ASR final。普通配置及原生模式在 finally 中分别恢复，账号凭据不在测试读取范围。

从 `app` 目录执行：

```powershell
python -X utf8 ../scripts/thinking_mode_device_test.py --serial SERIAL --records ../evidence/switch-home-regression.jsonl
```

## 统一构建后的真机结果（2026-11-18）

六请求完整通过，实际目标进程加载了包含 `ThinkingMode` / `ModeProfiles` 的 debug 模块：

| 通道 | 模式顺序 | HTTP 路径 | 模型 / Prompt | 可见历史 |
| --- | --- | --- | --- | --- |
| chat | 默认 → 思考 → 默认 | `/planner/default/v1/chat/completions` → `/planner/thinking/v1/chat/completions` → default | `FIXTURE_DEFAULT` → `FIXTURE_THINKING` → default；独立 `CHAT_*_SYSTEM` | messages=2、4、6，先前用户消息保留 |
| voice 文本入口 | 默认 → 思考 → 默认 | 同上 | 同上；独立 `VOICE_*_SYSTEM` | messages=2、4、6，先前用户消息保留 |

每次请求的合成 Bearer Token 均与对应模型匹配。语音文本入口还等待了原厂 TTS 正常结束，不作为麦克风 / ASR 模式快照的重复验证。

本次原生 `btn_deep_think` 没有显示，脚本使用同一真实 `ti0.h` helper 切换；实际生成器 effective 状态与请求分流均核对。原生初始模式为 false，finally 已还原。普通配置确认恢复到测试前 `enabled=true`、四通道 Codex；fixture nonce 已移除、ADB reverse 为空，账号存储未触碰。

证据：`evidence/thinking-mode-results.json`、`thinking-mode-chat.png`、`thinking-mode-voice.png` 与共享 fixture 请求记录。测试准备阶段修正了目标旧 DEX 未重载、冷启动 UI 未就绪、外部读取首选项写入瞬间及短锁屏等 harness 问题；生产路由代码未因此修改。
