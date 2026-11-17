# Codex 订阅 Provider

## 手机独立运行

按照用户选择，登录、令牌保存、刷新、模型列表与推理均在 Android 上完成。没有导入电脑的 Codex 账号文件，没有 app-server 桥接，也不依赖电脑常驻。APK 覆盖升级后，已登录账号的真实对话再次通过。

实现参照 OpenAI 官方 [Codex 认证说明](https://developers.openai.com/codex/auth) 与 [rust-v0.169.0 源码](https://github.com/openai/codex/tree/rust-v0.169.0/codex-rs)。本地 CLI 版本为 0.169.0，仅用于核对公开协议，不参与手机请求。

## 认证与凭据

- `CodexOAuth` 在固定 `https://auth.openai.com` 发起设备代码认证，用户通过官方 `/codex/device` 页面确认。
- `/api/accounts/deviceauth/usercode` 取得代码；`/api/accounts/deviceauth/token` 轮询授权；`/oauth/token` 交换及刷新令牌。公共客户端标识与 redirect URI 依据上述官方版本源码。
- `CodexAccounts` 使用 AndroidKeyStore 的 AES/GCM 密钥加密 `codex_secure` 私有首选项。refresh token 与 ID token 留在模块进程，普通配置 JSON 不含这些字段。
- UID 限定的 `ConfigProvider` 仅向模块自身和实际小爱 UID 提供当前 access token、账户标识与到期时间。普通 shell UID 的访问被拒绝。
- 到期前刷新；请求遇到 HTTP 401 时刷新并重试一次。取消设备代码登录保留原有已登录账号；退出清除本机登录。
- Manifest 关闭应用数据备份，登录 Activity 不导出。

首次手机认证返回 HTTP 403，而电脑相同公开认证请求返回 200。用户调整手机网络后，手机实际完成登录与模型列表获取；后续所有真实推理均直接由手机发出。这是网络前后状态记录，不把电脑请求作为手机成功证据。

## 推理协议与通道

`Profile.provider` 增加 `codex_subscription`，原配置默认仍为 `openai_compatible`。订阅 endpoint 固定为 `https://chatgpt.com/backend-api/codex`，不使用用户 API Token 字段。

`CodexProtocol` 将既有模块消息转换为 Responses：

| 模块输入 | Codex 请求 |
| --- | --- |
| system / developer | `instructions` |
| 文本、屏幕或附件图像 | `input_text` / `input_image` |
| 函数定义 | Responses `function` 工具 |
| assistant 工具调用、tool 结果 | `function_call` / `function_call_output`，保持 `call_id` |
| 上一步订阅原始 output | 原样回传，包括加密 reasoning 内容 |

`CodexClient` 使用 Bearer 与 `ChatGPT-Account-Id`，请求 `/responses`，`stream=true`、`store=false`。SSE 必须收到 `response.completed` 才返回成功；缺失完整 output 时从 `response.output_item.done` 补全。失败或截断流不执行未完成的工具结果。

对话、语音和控制共享这套 transport。语音仍使用小爱原厂 ASR / TTS。0.7.0 起，绘图还支持同一账号的 Codex 原生 Images 请求：在 Codex 基地址下直接 POST `/images/generations` 或 `/images/edits`，后者使用 JSON inline image references，不是 multipart。对话的 `generate_image` 既可调用该订阅绘图源，也可调用自选图片 API；两者都回填小爱原生 Gallery。见 `codex-images.md`。

## 0.5.0 真实设备验证（原有通道）

设备：Xiaomi Pad 5，Android 16，小爱 7.11.20.3514，LSPosed 2.2.1。订阅模型由手机取得的列表选择，测试模型为 `gpt-6.1-sol`。

1. 手机完成官方设备代码登录，并取得非空模型列表。
2. 真实订阅文本请求遵循自定义 System Prompt，原生小爱卡片显示 `CODEX_CHAT_OK`。
3. 语音通道文本输入经订阅模型返回 `CODEX_VOICE_OK`，原厂 TTS 正常播放结束。原厂麦克风 → ASR 链路另已通过人工发声测试；未把此处文本入口冒充重复麦克风验证。
4. 订阅控制模型接收真实屏幕，调用工具，接收工具结果与新屏幕后进行第二次模型调用，原生卡片显示 `CODEX_CONTROL_OK`，停止浮窗正确移除。
5. 真实订阅对话调用 `generate_image`；独立图片测试 API 收到指定 token、模型及 Prompt 前缀；结果回到订阅对话，再显示原生卡片和实际图片像素。图像内容是确定性测试图，不冒充订阅模型生成图。
6. 更新安装 0.5.0 APK 后，保留手机账号，再次真实订阅对话成功。

本地证据位于忽略目录 `evidence/codex-live-*-result.json`、相应 UI XML / 截图及模型列表 UI。测试脚本 `scripts/codex_device_test.py` 备份并恢复普通配置，不读取账号令牌。

## 0.5.0 自动化检查

core 的真实 loopback HTTP 测试覆盖设备代码、pending、授权交换、刷新、账户 metadata、认证头、Responses 转换、SSE 完成与截断失败；原有 API Provider 测试保留。共 43 项测试通过，JaCoCo 指令覆盖率 3528/3927（89.84%）。Debug、release 构建及 Android lint 通过；release 不包含 debug 测试入口。
