# 手机控制、模型选择与 System Prompt：本机 APK 静态证据

## 样本与边界

- 样本：`artifacts/com.miui.voiceassist/VoiceAssistAndroidT.apk`
- 包名 / 版本：`com.miui.voiceassist` / `7.11.20.3514`（versionCode `507011020`）
- SHA-256：`ae216062a595e58b0ef6fe7969de2696e00e427d5fd9c2c5d2e4ac07acb18e06`
- 工具：本地 JADX CLI 1.5.6；证据是 shell 静态分析，不是 REA 方法分析 Evidence。REA Android 方法分析因未配置 `REA_JADX_MCP_JAR` 未运行。
- 反编译输出：`analysis/decompiled/full/sources`；整体日志 `analysis/jadx-full.log` 报告 193 项错误。以下只引用实际生成并检查过的方法；反射、下载后的 RN/H5、服务端模型编排不在这些静态结论的覆盖范围。
- 设备观察由主任务记录：Android 16，产品 `nabu`，LSPosed 2.2.1。这里未执行手机操作、未改变原厂控制门控。

## 结论与证据

| 问题 | 静态观察 | 推断 / 尚缺证据 |
| --- | --- | --- |
| 专用 LLM 问答入口 | `a50.h0.sendLLMEventRequest(String,boolean,e,String,boolean,boolean)` 构造 `Nlp.RequestLargeLanguageModelContent` 后调用 `b30.g.getInstance().sendEvent(event)`。见 `a50/h0.java:930-944`。 | 该方法运行命中、云端具体模型版本，需要运行日志。 |
| 客户端模型选择 | `Nlp.RequestLargeLanguageModelContent` 的字段是 `query`、`skill_id`、`change_llm_vendor`、`is_search_enhancer`、`ai_reply`、`phone_call`、`scene`；见 `com/xiaomi/ai/api/Nlp.java:2705-2805`。`change_llm_vendor` 是布尔值，不是任意模型名。 | 云端如何解释该布尔值、真实供应商/模型名称未由 APK 证明。 |
| System Prompt | 对所有 `classes*.dex` 字符串做 `system_prompt` / `systemPrompt` / `system prompt` / `prompt_template` 的有界检索，未见这些 LLM 系统提示字段。上述专用问答 payload 也未暴露 `system` / `messages`。 | 这是有界负面证据，不等于全产品没有提示词；云端、独立 AICR 服务、动态 H5/RN 的内部模板仍未知。 |
| 名为 PromptGenerator 的实现 | `com.xiaomi.ai.domain.mobileapp.provider.PromptGenerator` 的 `genHints`、`makePromptHints`、`parseIntentionAction` 构造应用打开/关闭/安装等执行后的 UI/TTS 提示。见该类 `:24-220`。 | 它不是已证明的 LLM System Prompt 构造器。 |
| 普通应用控制 | APK 同时包含 `com.xiaomi.ai.domain.mobileapp.*`、`phonecall.*` 本地规则/解析器、`Application` / `UIController` / `Settings` 指令处理体系。 | “控制手机全部由 LLM 直接生成点击”不成立；某次请求走本地规则、云 NLP 还是 UIAgent，需要运行归因。 |
| 多步 UIAgent | `sd0.yd`（原 Kotlin 名 UIAgentOperation）消费 `Instruction<Application.UIAgent>`；`onProcessOp(vb0.l)` 检查 `c20.b.checkOperationShouldGoOn(dialogId)` 后通过协程调用 `X(...)`，再到 `sd0.zd.handle(callback,dialogId,payload,Continuation)`。见 `sd0/yd.java:122-164`。 | 多步规划器本体及模型源不在该执行器内。 |
| 原厂 UIAgent 生命周期 | `sd0.ae` 消费 `Application.UIAgentPhase`；`y00.i.onInstruction(Instruction)` 先通知状态管理器，再处理 `General.Push` / `Application.UIAgentPhase`。见 `sd0/ae.java` 与 `y00/i.java:1153-1173`。 | 修改规划结果时应维护 dialogId、Phase、执行完成与下一步截图的关联。 |
| 本机平板门控 | `c20.b.isSupportUIAgent()` 调用 `c20.l` 与 `c20.n`；`c20.l.shouldStop()` 要求 Android >= 35 且 HyperOS > 2；`c20.n.shouldStop()` 在 `q70.i.isPad()` 为真时返回拒绝原因。见 `c20/b.java:1415-1440`、`c20/l.java`、`c20/n.java`。 | 本机被识别成 Pad 时，原厂多步 UIAgent 将受此门控；本报告未执行该分支验证。普通语音控制与多步 UIAgent 应分开验收。 |
| 截图/执行回传 | `c20.b`（原 MultiUiAgentController）提供 `takeScreenShot()`、`takePlanBScreenShot()`、`fillContexts(...)`、`reportOperationExecMsg(...)`；`y00.i` 提供 `saveUploadBitmap(...)`、`setUploadResourceEventId(...)`。 | 替换控制模型应实现“屏幕上下文 -> 模型 -> 结构化动作 -> 执行回执 -> 屏幕上下文”的闭环，单改 HTTP URL 不足以证明完整控制。 |
| 独立端侧/系统能力 | `com.xiaomi.aicr.llm.LLMManager.a()` 显式绑定 `com.xiaomi.aicr/com.xiaomi.aicr.llm.LLMService`；公开方法含 `addOnLineCreationRequest`、`addTextEditCOTRequest`、改写/纠错/摘要等；见 `LLMManager.java:648-802`。 | 这是独立 Binder 服务接入证据，不证明它就是全部小爱对话的后端。本次未分析该服务 APK 或 native 模型。 |
| /llm/proxy 的实际用途 | `rh.l.requestSuggestQuery(...)` 用于推荐 query；`rh.j.requestImageCreativeData(...)` 与 `rh.k.pullImageCreativeData(...)` 使用 `ImageCreativeEntity`，均为 Retrofit `PUT /api/access/oauth/llm/proxy`。 | 路径名含 llm 不等于通用 OpenAI-compatible chat 接口。主任务另行跟进图像接口。 |

## 控制协议形状

`com.xiaomi.ai.api.Application.UIAgent`（`Application.java:6145-6317`）的必填项是 `ui_agent_type`。枚举定义见 `:6771`：`UNKNOWN=-1`、`TAP=0`、`TYPING=1`、`SWIPE=2`、`LONG_PRESS=3`、`SEARCH=4`。

选填项：`start_pos`、`end_pos`、`text`、`start_time`、`duration`、`screen`、`times`、`app_package_name`、`directed_area`、`search_delay_time`、`retry_action_delay_time`、`uiagent_scene`。`UIAgentPosition` 含 float `x` / `y`。

`sd0.zd` 在执行 TAP / LONG_PRESS / SWIPE 等动作时通过 `d20.x.getWidthForPercentInDisplay` 与 `getHeightForPercentInDisplay` 转换坐标；使用的是百分比坐标，不是直接像素坐标。执行下沉到 `m20.g0.mockClickInDisplay` / `mockScrollInDisplay` 等；TYPING 及 SEARCH 还依赖输入焦点与文本处理。

`Application.UIAgentPhaseStatus` 定义为 `INIT=0`、`ACTIVE=1`、`PAUSE=2`、`FINISH=3`、`FAIL=4`；`UNKNOWN=-1`。仅注入一个 TAP 而没有有效生命周期 / 当前 dialogId，不应视作 UIAgent 集成完成。

## 已定位的模块适配边界

1. **问答入口**：hook `a50.h0.sendLLMEventRequest` 可获得明确的 LLM query；更下游 `b30.g.sendEvent` 能区分多个 AIVS 事件种类，适合统一路由。各事件必须按其协议适配，而非把所有事件直接发到 `/chat/completions`。
2. **原厂显示/执行入口**：`com.xiaomi.voiceassistant.instruction.base.OperationManager` 提供 `onInstruction(Instruction[],boolean)`、`onFlowableInstruction(Instruction,boolean)`、`addInstructionsToOperationQueue(Instruction[])`；见 `OperationManager.java:230-237,672-719`。具体线程、query 状态及生命周期由主任务继续运行验证。
3. **UIAgent 状态入口**：实际类名是 `y00.i`，不是 metadata 中的 `com.xiaomi.uiagent.UiAgentManager`。生产 hook 必须使用 DEX 中的实际类/方法并做版本适配。
4. **System Prompt 的插件设置**：为自选后端构造真正的 `messages[{role:"system",content:...}, ...]`；把文字前缀塞进原厂 `query` 与设置服务端 System Prompt 是不同能力，界面和文档应区分。原厂云端内部系统模板仍属于未知项。
5. **端侧服务**：若要改变 AICR 改写/摘要模型，还需分析 `com.xiaomi.aicr` 独立服务及其 `ILLMService`。仅 hook 小爱问答入口覆盖不到所有 Binder 能力。

## 仍待主任务验证

- 模块加载后，每个实际 hook 的命中次数、进程（主进程 / `:core`）、签名与线程。
- 一次自定义问答请求的 base URL、模型与 System Prompt 在后端实际收到的请求中一致，并在小爱 UI 显示结果。
- 普通手机控制与多步屏幕控制分别验证；对于本机 Pad，实际门控返回与可用控制路径要记录。
- 原厂服务端 System Prompt、云模型版本与模型路由配置：当前 APK 静态证据不足，保持未验证状态。

## 问答回填链的追加核验

主任务提出的 `b30.o0.u0(Event)` hook 位于 `sm.e.postEvent` 之前；该方法原本也更新 `X` / `Y` / `Z` 排除映射及 `s0.recordQuerySession(...)`。截断原方法时应核查这些副作用是否与当前请求有关，而非仅根据布尔返回值判断请求状态正确。

`b30.o0.W(Instruction)` 同步调用 `r30.e -> d40.a -> q30.c -> s30.g -> p30.k0` 处理链。非流式回填可使用同一 `dialog_id` 的：

1. `Nlp.StartAnswer`，payload `{}`。
2. `Nlp.EventACK`，payload `{ "status": 0 }`（必填 int `status`，见 `Nlp.java:829`；过滤器按 fullName 识别 ACK）。
3. `Template.Toast`，payload `{ "text": "回答", "query": "用户问题" }`。
4. `Nlp.FinishAnswer`，payload `{}`。
5. `Dialog.Finish`，payload `{}`。

静态依据：`p30.k0.handle` 在 StartAnswer 执行 `onInsStart`；`c40.c.h` 清空待执行指令集合 `E`，普通 Toast 加入 `E`；`c40.c.e -> g -> l` 在 FinishAnswer 将集合提交到 `f9453e.onInstruction(Instruction[],false)`。这条非流式路径没有要求先发 StartPreStream / StartStream。后两者用于另一套流式/预流式处理器。

`q30.d.filterInstruction` 还追踪 EventACK：FinishAnswer 到达时，若未收到 ACK 且存在指令，会走 `g(dialogId,engineIdPrefix,false)`，其中包含停止音频与取消操作的处理。发送 ACK 让本地结果遵循正常 ACK 路径。`W` 会同步修改引擎指令/请求状态；插件宜在同一引擎 Handler 原始字段 `y` 中顺序提交整组，HTTP I/O 留在后台；`f9396y` 仅是 JADX 别名。

更直接的 UI 入口是 `OperationManager.insertTtsAndCardOperationWithQuery(query,toSpeak,toast)`。`ac0.j` / `bc0.h` 会将这些值装入 Bundle 后发送 `MSG_INSERT_TTS_CARD` 到执行线程，原厂读屏功能也使用此入口。该入口只覆盖显示/TTS 插入；引擎请求状态、dialogId 和网络请求收尾需要另行处理。因此仍应以实际端到端运行证据判断哪条路径完整。
