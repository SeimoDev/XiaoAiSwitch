# Codex 订阅原生绘图

0.7.0 接入，随 0.8.0 的“小爱 Switch”首页 / 二级菜单布局一并交付。绘图通道可直接选择 Codex 订阅，不再强制使用独立图片 API。

## 协议与实现

依据 OpenAI 官方 rust-v0.169.0 的 [Images client](https://github.com/openai/codex/blob/rust-v0.169.0/codex-rs/codex-api/src/endpoint/images.rs)、[请求类型](https://github.com/openai/codex/blob/rust-v0.169.0/codex-rs/codex-api/src/images.rs) 与 [内置绘图工具](https://github.com/openai/codex/blob/rust-v0.169.0/codex-rs/ext/image-generation/src/tool.rs)：

- 手机当前账号的 OAuth Bearer 与 `ChatGPT-Account-Id`，复用原有 AndroidKeyStore 登录和刷新机制，不读电脑账号文件。
- 文生图：`POST https://chatgpt.com/backend-api/codex/images/generations`。
- 图生图：`POST https://chatgpt.com/backend-api/codex/images/edits`；使用 JSON `images: [{image_url: "data:image/...;base64,..."}]`，不是 API Key 图片源的 multipart。
- 默认模型 `gpt-image-2`；`quality=auto`、`size=auto`，一张输出，原图最多五张。绘图 Prompt 前缀与用户请求合并进 `prompt`。
- 图片请求带独立 `x-codex-image-turn-id`，读取超时为五分钟；取消复用现有连接断开机制，HTTP 401 刷新后仅重试一次。
- `CodexImages` 构造请求，`CodexClient.images` 负责 HTTP；`HookEntry.generate` 分发两类 Provider，统一通过 `ImageProtocol` 回填真实原生 Gallery / 预览。直接绘图、原厂编辑器、对话自动调用及上次图片引用共用该分发，不经过电脑转发。

UI 移除了绘图 Provider 的排除条件，可用默认绘图模型按钮填写模型；`SettingsConfig` 允许保存订阅绘图配置。原 API Key 绘图链路保留。

## 真实手机验证

使用手机已有订阅登录，没有设置额外 API Key、没有本地图片服务，也没有 ADB reverse：

1. 直接绘图：实际生成一只红猫，在小爱原生 Gallery 显示，点击放大后仍显示同一张实际图片。
2. 原厂图生图：在真实编辑器上传橙青棋盘格；请求走“图生图 / gpt-image-2”，返回蓝色棋盘格纹理的小猫。原始附件与生成结果同时出现在原厂对话页面，图像内容已目视核验。
3. 自动分流：订阅对话模型 `gpt-6.1-sol` 调用订阅绘图模型 `gpt-image-2`，图片回传后对话结束，原生图片与完成标记同时出现。

上述测试不是 fixture 固定图生成；仅图生图的输入是确定性测试附件。结果与界面在本地 `evidence/codex-native-image-*-result.json`、XML / PNG 留存，测试后恢复普通配置并保留登录。测试脚本为 `scripts/codex_image_device_test.py`。

## 自动化

51 项 core 测试通过，指令覆盖率 3864/4254（90.83%）。新增真实 loopback HTTP 测试核对订阅认证头、原生路径、JSON 图片引用与精确字节、Prompt / 模型、HTTP 401 和取消；保存订阅绘图配置也有测试。该覆盖率不包含 Android UI。
