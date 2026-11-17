# 设备与开发验证记录

## 目标保持不变

分析小爱的对话、语音、绘图、控制手机的 LLM 接入方式与 System Prompt 设置机制；实现可修改各通道模型源、模型名和 System Prompt 的 Xposed 模块，并在当前设备验证。用户后续增加手机独立登录直连的 Codex 订阅 Provider；当前完成审计见文末，各早期轮次的待办保留为历史记录。

## 已取得的证据（2026-11-17）

- ADB 设备型号：Xiaomi Pad 5 / nabu，Android 16 / API 36。`adb shell su -c id` 确认 shell root。
- LSPosed 2.2.1 (7944)，API 102；模块推荐作用域仅小爱。安装、启用模块后，在小爱进程实际收到模块状态回报。
- 小爱：`com.miui.voiceassist`，7.11.20.3514 / 507011020。
- 拉取 APK 的 REA Evidence：`ev_d8c19f6c9cbff4a38520c5fe262b96db47f01384c5117d8de4969b21b5d7f4d5`；SHA-256 `ae216062a595e58b0ef6fe7969de2696e00e427d5fd9c2c5d2e4ac07acb18e06`。
- REA Android 反编译 Provider 缺 `REA_JADX_MCP_JAR`；没有更改 MCP 配置。改用官方 JADX 1.5.6 CLI 对该 APK 离线分析。这些输出是普通静态分析证据，不冒充 REA Evidence。
- JADX 完整源码导出处理 24152 个单元，报告 193 个反编译错误；相关结论逐一依据实际已展开的方法，不把整个导出视作无缺口。

## 文本端到端实测

1. 本地合成服务监听主机 `127.0.0.1:8765`，通过 `adb reverse` 连接。
2. 临时配置使用 `FIXTURE_MODEL` / `FIXTURE_TOKEN` / `FIXTURE_SYSTEM_PROMPT`。
3. 实际调用小爱 `SpeechQueryService`，action `com.miui.voiceassist.query`，extras：`assist_query=XASWITCH_TEST`、`voice_assist_start_from_key=xiaoai_switch_fixture`、`use_offline_nlp=false`、`isCloseMic=true`。
4. 服务实际收到 `/v1/chat/completions`，`model=FIXTURE_MODEL`，`stream=false`，`messages[0]={role:system,content:FIXTURE_SYSTEM_PROMPT}`，用户 query 正确，合成 Bearer Token 匹配。
5. 小爱 OperationManager 实际处理 `Template.Toast` 成功。唤醒屏幕后重复 `XASWITCH_TEST_VISIBLE`，UIAutomator 树中原生卡片包含 `XASWITCH_FIXTURE_OK`；已保存截图。
6. 本地记录：`evidence/fixture-requests.jsonl`、`evidence/chat-fixture-ui.xml`、`evidence/chat-fixture.png`。这些测试只证明合成后端与当前文本原生显示链路，不证明真实提供商、语音、绘图或控制已通过。

## 实现相关的准确签名

- `b30.o0.u0(com.xiaomi.ai.api.common.Event):boolean`：AIVS 出站。
- `b30.o0.y0(com.xiaomi.ai.api.common.Instruction):void`：云端入站。
- `b30.o0.W(Instruction):void`：指令处理链；回填刻意不重新经过云端过滤 Hook。
- 引擎 Handler 的原始字段名是 **`y`**。JADX Java 显示别名 `f9396y`，不应拿别名反射。
- 原生非流式回填：`StartAnswer → EventACK{status:0} → Template.Toast{text,query} → FinishAnswer → Dialog.Finish`，同一 `dialog_id`。
- 语音 ASR-only：`Execution.RequestControl.disabled=[NLP,TTS]`；`SpeechRecognizer.RecognizeResult.isFinal()` 后取第一项 `getText()`。

## 本轮检查

- `core` 包内运行 11 个 Gradle 单元测试：零失败；JaCoCo 指令覆盖率 98.51%，80% 门槛通过。该数字仅覆盖 core，不代表 Android Hook 的自动化覆盖率。
- `:app:assembleDebug`：通过；APK 已安装。
- `:app:lintDebug`：通过（有警告，未把它描述为零警告）。
- 未运行 `bun run check`：初始空仓库没有 `package.json` 或 Bun 脚本；本工程使用 Gradle。没有 Protocol/Server HttpApi 或生成客户端改动。
- 普通 shell UID 调用配置 Provider 实际收到 `SecurityException: Caller not in module scope`，未返回配置。
- 测试后已结束本地合成服务、移除 `adb reverse tcp:8765`，将模块配置总开关恢复为 `false`，不保留合成 Token 为启用配置。更新后的开发 APK 已重新安装；LSPosed 作用域保持仅小爱。

## 第一轮结束时待办（后续轮次逐项验收）

1. HTTP 传输的实际本地服务集成测试；配置切换、会话清空、取消与失败状态测试。
2. 语音识别结果接管真机验证；保留/自选 TTS 回接与播放验证，明确 ASR 与 LLM 源的不同。
3. 绘图生成与原生图片回填，支持 URL / base64 的可验证路径，不能只改配置表单。
4. 控制 LLM 的工具协议、原生动作执行、结果/屏幕状态回传与下一步循环；本机 Pad 门控与可用执行路径分别确认。
5. 设置界面、故障恢复、禁用回原厂服务的真实回归检查；对所有通道做端到端验证后才进行完成审计。

## 第二轮：绘图、传输与原厂 TTS

前一轮属于有效进展：修改了源码并取得文本端到端设备证据。本轮重新检查了文件与当前 ADB 状态后继续，没有将待办改成只做聊天模块。

- 传输代码迁移至 core 的 `ModelClient`，新增真实 loopback HTTP 服务测试：UTF-8、Authorization、非 2xx、重定向、无 Token、异常 JSON 与 16 MiB 限制均实际执行。
- `QueryRouter` 支持默认通道和 `/chat`、`/voice`、`/draw`、`/control` 前缀。当前控制前缀和控制配置尚不接管请求。
- 图片请求经独立 `/images/generations`，把配置模型与 Prompt 前缀发送到服务。返回的 HTTP(S) URL 或 `b64_json` 转换为 `Template.ImageContentGallery`。
- 两种返回格式都在设备显示了确定性的橙青棋盘格；测试除原生描述文本外，还检查截图中的实际图片颜色。URL 测试使用默认 chat + `/draw`，确认前缀未发给模型。
- 原生 Gallery 的静态“结果来源淘宝”在自选服务卡片上改成真实来源；原厂卡片不受影响。
- 点击预览实际放大返回图片。修复了浮窗 Context 没有 Activity token、窗口层级和自定义 ImageView 零尺寸问题；最终使用原窗口类型和明确布局，截图与 UI 树均通过。
- ASR final 的后续处理移到原始方法结束后排队，避免在 final 状态尚未落地时抢先回填。
- 语音通道“文本 → 自选 LLM → 原生卡片 + 原厂 TTS”已经实际播放正常结束；麦克风/ASR 仍未实测，不能据此宣称完整语音链已完成。详见 `voice-pipeline.md`。
- 控制工具协议已增加：open_app、tap、swipe、type_text、press_key、screenshot；包含强类型动作、参数范围校验、截图消息、tool_call_id 对应关系和单元测试。它是模块自定义协议，不是复原出来的原厂云端协议；原生动作执行器与多步循环仍待接入。
- 当前 26 项 core 测试零失败；183 行均有测试执行，指令覆盖 1472/1483（99.26%）、分支覆盖 126/149（84.56%）。数字只针对 core，Android Hook 依靠上述真实设备样本测试，未伪称全 APK 自动化覆盖率。
- 构建与 `:app:lintDebug` 通过。开发版版本更新为 `0.2.0-dev` / 2。原厂文生图 CREATE_IMG 上下文检测已有静态适配代码，但本机浮窗菜单没有直接出现该入口；本轮验证的是模块默认绘图通道与显式前缀，不把它们冒充原厂按钮验证。
- 安装最终构建后重新通过绘图 URL / 点击预览与语音 LLM / TTS 测试，图像 base64 与文本通道也已在本轮通过。最终 APK SHA-256：`6c3181e563266415e789ad40ef7c097e7985d46ddecdf22121f83c712cac5d50`。
- 识别到关闭模块后旧 owned dialog 可能影响原厂连续对话的问题，现会清除旧接管标记；网络结果也核对最新配置 revision，不把旧配置结果回填到新配置会话。更完整的取消/连续对话回归仍保留在待办中。
- 本轮测试结束已恢复 `enabled=false`，核对 ADB reverse 列表为空，并结束主机 fixture 服务。没有把测试服务保持为用户默认模型源。

下一步仍以完整目标为准：先接控制 LLM 的实际工具执行、屏幕回传和循环，再完成麦克风/ASR 验证、取消/会话与故障回归；原厂图生图的文件 ID → 原图适配另行核验。当前没有完成审计通过的结论。

### 控制适配下一步可直接复用的静态入口

- `m20.g0` 单例原始字段 `a`；`injectEventIntoDisplay(Object,int):boolean` 调 InputManager 并返回注入结果。它会在 finally 回收传入的 MotionEvent，调用方不要重复 recycle。
- `m20.g0.insertFocusText(String)` 使用原厂 AIInputTextManager 写入焦点输入框，并等待最多 500ms；返回 void，不应据此伪造“文字已输入成功”，须核验屏幕/结果。
- `c20.b` 单例原始字段 `a`；`takePlanBScreenShot():Bitmap` 复用 `d20.w.takeScreenshotAndExcludeSelf`。静态方法体不先调用 UIAgent Pad 门控，但截图能力仍待当前设备实际验证。
- `UiManager.hideCardForActivityNotStopEngine()` 经 `uf0.b0` 调 `exeBackOperator(true,true,false)`；隐藏与引擎/取消状态的影响须实际核验，不能直接假设它不影响模块任务。
- 原厂浮窗覆盖整个屏幕，控制点击前必须处理自身 UI 的可见性/触摸阻挡，并具备取消和恢复行为；仅把截图发给模型并调用低层点击不等于控制闭环已完成。

## 第三轮：控制执行与真实麦克风

前一轮是有效进展。本轮先核对了工作树、ADB 设备与 root 状态，再补齐实际执行，而非仅增加配置表单或状态说明。

- `ControlLoop` 接入实际 HTTP 工具循环，`PhoneControl` 执行打开应用、点击/长按、滑动、焦点中文输入、导航键与截图，状态重新发给同一控制模型。它使用独立配置的控制模型和 System Prompt。
- 实测最初发现原厂 ASYNC 输入 helper 的 true 仅表示接收排队，系统随后因 MIUI AI 控制权限拒绝实际触摸。现改为同步 WAIT_FOR_FINISH，并调用原厂屏幕控制权限机制；在原厂界面开启了本机“AI智控”，未替换系统校验函数。
- 真机基本流程 4 次请求实际打开设置、取屏、返回桌面，再显示原生结果卡片。真实 JPEG 内容与 tool_call_id 一同回到后端；日志中的图像用摘要代替，原图仅本地留存。
- 真机交互样本中点击使 count=1，中文输入为 `控制测试XASWITCH`，滑动 scroll>0；这些值来自真实 View 监听器。最终版本重跑时 scroll=1949。
- 后端延迟响应时点击停止，窗口即时恢复并断开请求；等候超过服务器延迟后没有下一次模型调用或后续操作。步数设 1 的异常收尾也在真机通过，只发送一次调用并显示明确上限信息。
- 所有控制出口恢复小爱自身窗口状态，移除停止按钮；返回 Home 后以原厂 idle 模式恢复结果卡片。控制期间停止按钮保持屏幕亮起，不改写用户系统休眠设置。
- 语音真实人工发声已通过。用户说出短句后，原厂 ASR 实际产生 `12+6等于多少`，发送至 `FIXTURE_VOICE`、`FIXTURE_VOICE_PROMPT` 对应的测试服务，并完成原生显示 / TTS 正常结束。这不是 Intent 文本输入或模拟 RecognizeResult。详见 `voice-pipeline.md`。
- 本机合成音频回放未得到预期短句，保留为未通过的声学诊断，不当作识别正确证据；上述人工发声测试随后核验了完整链路。偶然非测试 ASR 内容在本地日志中已脱敏。
- final 为空时不再向 LLM 发送空 query；解除接管，交由原厂完成收尾。诊断仅额外保存 ASR 开始时间，不录制或存储用户音频。
- 现在 core 共 30 项测试零失败，覆盖指令 1661/1698（97.82%）、分支 144/173（83.24%）；包括实际 loopback HTTP、多步控制、取消入口与步数限制。百分比不冒充 Android 全 APK 覆盖率。
- Debug / release 构建及 lint 通过。release manifest 和 DEX 检查确认不含 debug 交互 / 音频测试入口。开发版已更新安装为 `0.3.0-dev` / 3。
- 最终安装后再次通过控制正常结束、点击 / 文字 / 滑动、停止、步数上限、文本、图片预览与语音 LLM / TTS 回归；真实麦克风人工测试使用相同语音 Hook 实现（随后版本变更只涉及界面文案与控制保持亮屏标志）。
- 当前开发 APK SHA-256：`bf47c829fb493f05c769726f9f01ca6fdb3f086a0b9716b1d4c17c3fc2b4c927`。本轮 fixture 服务已结束，停止按钮及 ADB reverse 均已清理，总开关恢复为 false；系统“AI智控”保留开启以供控制功能使用。

当前四通道核心执行链路均有设备证据，整个目标仍保持 active：原厂图生图文件 ID / 原图适配、普通语音/对话到绘图/控制的自然语言自动分流、配置/会话与故障回归仍需完成。现有显式通道/前缀功能不当作这些剩余工作的替代。

## 第四轮：原生图生图、自动分流与生命周期

- `ImageBridge` 解析原厂 CREATE_IMG / EDIT_IMG 上下文与 Web `MultiModal.ImageGeneration`。文件 ID 经原厂本地缓存映射取得原图，保留原厂上传，没有用假上传结果代替生产链路。
- 实际进入原厂 `AiImg2ImgActivity`，上传测试原图并提交编辑请求。独立 `/images/edits` 收到原始文件，SHA-256 与上传前文件完全一致；原生卡片显示与原图不同的返回像素。CREATE_IMG 未附原图时进入 `/images/generations`。
- debug nonce 限定的测试调用真实 `vf0.a.sendWebImageGenEvent` producer，观察到生产事件与独立 multipart 编辑请求。release 对应实现为空，不包含该触发入口。
- `DialogueLoop` 给对话模型提供 `generate_image` / `control_phone` 工具，分别使用独立通道的 endpoint、token、模型与 Prompt；结果返回规划模型后回填原生界面。对话 → 绘图与语音通道 → 控制的真实 HTTP 循环均通过。
- 用户在实际麦克风聆听后说“帮我画一只猫”，原厂 ASR 得到相同文本；规划模型、独立绘图 API、原生图片与原厂 TTS 完整通过。没有注入识别结果。
- 上一张生成图片可作为下一次编辑的 `previous` 输入；两次真实规划与 multipart 编辑请求通过，并检查编辑后原生像素。
- `lifecycle_test.py` 的八项门槛全部通过：history、clear_session、configuration_revision、settings_ui_save、superseded_request、http_error_ui、recovery、disabled_passthrough。设置 UI 修改真实 System Prompt 后，新请求立即使用新配置；慢请求被新输入取代后不显示过期回复。

## 第五轮：Codex 手机订阅直连

用户选择“手机独立登录和直连，不依赖电脑常驻”。实现 `CodexOAuth`、`CodexAccounts`、`CodexProtocol`、`CodexClient` 与账号页面，既有对话 / 控制循环通过 transport 注入使用新 Provider。API Key Provider 保持默认兼容。详见 `codex-provider.md`。

- 手机最初认证 HTTP 403；用户调整手机网络后，手机独立完成官方设备代码登录，并从订阅取得实际模型列表。
- 手机真实请求 `gpt-6.1-sol`：自定义系统指令对应的 `CODEX_CHAT_OK` 显示在小爱原生卡片；语音通道响应 `CODEX_VOICE_OK`，TTS 正常结束。
- 控制模型实际接收屏幕并完成工具调用 / 结果回传 / 第二次模型调用，显示 `CODEX_CONTROL_OK`，停止浮窗移除。
- 真实订阅规划模型调用独立图片测试源，图片 API 接到指定 Prompt、模型与 token，然后返回订阅模型，最终原生卡片同时显示文字与实际图片像素。图片内容是测试图，不作为订阅模型绘画质量证据。
- AndroidKeyStore AES/GCM 保存账号，refresh / ID token 不放入普通配置，也不交给小爱；模型调用仅按 UID 读取当前 access token 与账户标识。刷新协议由真实 loopback OAuth 测试验证。没有读取或导入电脑登录文件。
- 更新安装 0.5.0 后，账号保留，再次真实订阅文本请求通过。

### 最终构建与回归

从 `core` 执行 `../gradlew.bat test jacocoTestCoverageVerification :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain` 成功。43 项 core 测试零失败，JaCoCo 指令覆盖 3528/3927（89.84%），80% 门槛通过；lint 0 errors、21 warnings。这不是 Android 全 APK 自动化覆盖率。

最终安装的 0.5.0 上再次通过：Codex 实际文本、配置 / 会话八项门槛、API 图片 URL / base64 与放大预览、控制点击 / 中文输入 / 滑动、延迟请求停止、原厂图生图、Web producer 图生图。预览测试曾有一次 tap 未打开预览；测试脚本补充点击前唤醒设备后，URL 与 base64 均通过，生产预览代码未因此改动。

release manifest / DEX 实际检查不含 `ControlFixtureActivity`、`play_audio`、debug FIXTURE action 或测试交互标记；Codex 代码与账号页面正常包含。Python 脚本语法检查与未跟踪源码空白检查通过。

### 完成审计与最终设备状态

| 用户要求 | 交付与证据 |
| --- | --- |
| 分析对话、语音、绘图、手机控制接入 | `control-prompt.md`、`voice-pipeline.md`、`image-pipeline.md`、`image-input-hook.md`、`control-runtime.md`，区分客户端证据与原厂服务端未知部分 |
| 四通道模型源 / 模型 / System Prompt 设置 | 生产配置 UI、UID 限定配置 Provider、实际后端字段验证与原生卡片 / TTS / 图片 / 动作执行 |
| 原厂图生图与自然语言分流 | 原图摘要匹配的 multipart、真实 producer、人工发声的绘图闭环与独立模型任务委派 |
| Codex 订阅手机独立登录直连 | 官方设备代码流程、手机模型列表、真实文本 / TTS / 视觉工具循环 / 图片源分流、APK 更新保留账号 |

模块 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，版本 0.5.0 / 5，SHA-256 `a8fc5d4db3995e14c0a84fb35a9d07a83ecaa93954fab985508ec73c4977f4d5`。release 另有未签名构建产物。

测试结束已关闭主机 fixture 服务，ADB reverse 列表为空，停止浮窗已清理。普通配置无 fixture endpoint 或 token；通过真实设置 UI 选择并保存文本通道 Codex / `gpt-6.1-sol`，System Prompt 留空供用户设置。文本通道选中，模块总开关仍为 false；配置在进程重启后再次核对一致。手机加密账号存储保留。LSPosed 作用域仅小爱，原厂“AI智控”保留开启。

原目标与后加的 Codex Provider 已完成上述审计。

## 第六轮：按用户要求使用 Miuix 重写 UI

0.6.0 将模块设置与订阅账号页面迁移到真实 Miuix Compose，保留 Hook、配置格式与手机密钥库。支持系统深浅色、宽屏双栏、固定保存栏、通道独立草稿和旋转恢复；具体文件与证据见 `miuix-ui.md`。

47 项 core 测试、90.25% 指令覆盖率门槛、Debug / release 构建与 lint 通过。真实 UI 自动化通过主题、三种尺寸、Provider / 通道切换、模型列表、退出取消与配置保存；新界面上的完整生命周期八项回归和手机 Codex 实际对话再次通过。

新版已覆盖安装，保留原 Codex 登录与模型；总开关恢复关闭，尺寸 / 主题和 ADB 连接恢复，测试服务停止。最终 SHA-256：`98988fa89ef6e8b23c84b5d3742d6346c985d5c32b47b2cd00e1ffff931b5293`。

## 后续迭代汇总：0.10.0

用户继续提出的改动已整合：

- Codex 手机订阅原生文生图、图生图与对话自动绘图，红猫 / 棋盘格蓝猫 / 自动绿色猫实际出图并显示原生卡片；Provider 引用配置下也再次通过真实订阅自动绘图。
- 应用改名“小爱 Switch”，首页简化为激活卡片、版本、接管开关与配置二级菜单；卡片参考 SukiSU-Nova 经典 Miuix 布局，保留红 / 绿状态。
- 高对比保存成功 / 失败提示；统一 Provider CRUD、模型目录和模型引用，地址 / 密钥不再逐个模型重复维护；迁移保留有效配置和旧 API 草稿密钥。
- 原厂 PAG 全局控制光效真实运行，点击、中文输入、滑动及停止 / StepLimit / 完成清理均通过；模型截图不含光效。
- 原厂 Markdown renderer 实际产生 26 个标题 / 列表 / 引用 / 代码 / 表格 / 链接 span；原始模型历史与纯文本回归不变。
- 默认 / 思考独立 Provider、模型和 Prompt；真实原生状态切换完成文本与语音文本入口六请求验证，独立 Token 与可见历史正确。

70 项 core 测试零失败，指令覆盖率 91.26%；Debug / release 构建和 lint 通过。统一 Provider 真机 UI、完整生命周期八项、API 图片与预览、引用式 HTTP 对话和引用式 Codex 原生自动绘图均通过。详细证据见新增各专项报告和本地 evidence。

与早期测试不同，当前用户真实配置已自行开启总开关并设置四通道 Codex；后续测试按最新备份恢复，不再擅自强制关闭。账号与应用包名保持不变。

最终 0.10.0 debug APK SHA-256：`a00fcffcc329fa0d9abc2a46e19ec3306b7d776131e606b2ee71632cd4556929`。签名验证通过，release DEX 不含 debug 交互 / thinking fixture / 音频入口。状态卡红 / 绿与深浅色实测完成：未确认态使用本地缓存 fixture，确认态重新加载真实小爱取得；没有停用 LSPosed。

真实用户旧配置已通过设置页提交迁移：四通道继续使用原 Codex 模型与 Prompt，统一引用一个 Codex Provider；保留 `enabled=true`，模型配置不再含地址 / 密钥内联字段。测试 reverse 清空，fixture 服务停止，屏幕尺寸与原主题已还原。当前首页截图为 `evidence/home-final.png`。
