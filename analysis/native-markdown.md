# 原厂 Markdown 排版接入

## 样本与静态证据

本次复用已导出的本机 APK JADX 源码：小爱 7.11.20.3514 / versionCode 507011020，原 APK SHA-256 `ae216062a595e58b0ef6fe7969de2696e00e427d5fd9c2c5d2e4ac07acb18e06`。以下是本地静态分析，不把它称为新增 REA 方法 Evidence；原有反编译覆盖限制与 `progress.md` 一致。

- `sd0/od.java:g0/h0` 将 `Template.Toast.text` 放入 `com.xiaomi.voiceassistant.card.i` / `com.xiaomi.voiceassistant.instruction.card.stream.b`。
- 两种卡片的 `bindView` 直接 `TextView.setText(String)`，因此目前模块输出的 Markdown 被原样显示。
- `nj0/c.java:getMarkwon(Context,boolean,boolean)` 是原厂 `MarkdownManager`。`false,false` 与原厂 `ce0.s.n0` 的默认文本视图模式一致，返回 `ew0.e`。
- `ew0/e.java` 提供 `setMarkdown(TextView,String)`、`toMarkdown` 与 `setParsedMarkdown`。
- `nj0.c` / `j50.f` 配置标题字体、列表、引用、代码块、表格、公式插件与原厂深浅色资源；原生 `createMarkdownListener` 已有代码复制、链接处理等交互。
- 实际 DEX `classes2.dex` field table 核对：`Lnj0/c;->a:Lnj0/c;` 为单例字段，`getMarkwon` 为真实方法名；没有使用 JADX 显示别名 `f88809a` 反射。

## 归属与历史

仅模块构造的 `Template.Toast` 的 `header.id` 增加 `xiaoaiswitch-markdown-` 前缀，`dialog_id` 和回答原文不变。其他生命周期 / 图片指令仍使用普通 UUID。

有界核验了 `InstructionHeader`、`BaseOperation`、`instruction/base` 和两种 Toast 卡片：这些路径把 instruction ID 当 String 使用，检索未见 `UUID.fromString` / `parseUUID`。`BaseOperation.getId()` 直接返回指令 ID，`BaseCardOperation` 将其写入卡片；原保存历史路径保存原指令并仅按需要改 dialog ID，所以标记不依赖进程内对话缓存。

`ReplyMarkdown` 只给带此前缀的 ToastOperation 卡片启用原厂排版，在卡片绑定 / 更新时调用原厂 renderer。没有全局 hook `TextView.setText`，没有新增 Markdown 库，也不改模型输入历史或 TTS 文本。ViewHolder 复用时恢复本次排版引入的 movement method；更新回调检查当前绑定的卡片归属，避免旧卡片渲染到复用后的 View。

单行、没有 Markdown 特征的回答继续走原厂 plain text，因此现有文本 fixture 标记保持原样。渲染失败保留原绑定的纯文本回答；诊断日志只记录指令 ID、span 数量和类型，不记录回答内容或凭据。

## 检查与真机入口

- 新增 `AivsProtocolTest.markdownOwnershipPersistsInToastIdWithoutChangingContentOrOtherInstructions`，先观察红测试，再实现前缀；focused `AivsProtocolTest` 运行通过。
- 新增 `scripts/markdown_device_test.py`，通过真实本地 HTTP 返回标题、粗斜体、列表、引用、代码块、表格和链接；检查真实原厂标题、列表、引用、代码、表格及链接 span、原生显示去除 Markdown 语法，以及模型 / token。
- 同进程继续发送普通问答，核对后续请求的 assistant 历史仍精确等于原始 Markdown，并检查普通回答不变。
- 该脚本需要重启主任务现有 fixture 服务以加载新增 `MARKDOWN_REPLY` 分支，完成后再在 `app` 目录运行。脚本保留手机 Codex 凭据，只备份 / 恢复普通配置。

```powershell
python -X utf8 ../scripts/markdown_device_test.py --serial SERIAL --records ../evidence/switch-home-regression.jsonl
```

## 真机结果（2026-11-18）

统一开发 APK 上实际通过。`XiaoAiSwitch` tag 日志记录 26 个 span，包括：

- `b60.a`：原厂标题字体；
- `hw0.b`：列表项 / bullet；
- `hw0.a`：引用竖线；
- `hw0.c`：代码块；
- `h60.c`：原厂表格；
- `io.noties.markwon.core.spans.LinkSpan`：链接。

已查看真实屏幕截图：标题、粗斜体、inline code、Python 语法高亮、两列表格、引用、蓝色链接均正确显示，未出现裸 `#`、`**`、代码围栏。结果在 `evidence/markdown-native-result.json`、`markdown-native.xml` / `png`，包含对应指令 ID 和 span 记录。

同一进程的第二次普通问答显示原样 `XASWITCH_FIXTURE_OK`；后端实际收到的 assistant 历史仍是未变的 `MARKDOWN_REPLY`。另用原 `device_fixture_test.py` 的独立 plain text 流程也通过，证据为 `markdown-plain-rebind.*` 与 `markdown-plain-regression.*`。

此轮没有主动点击外站链接，也没有把完整 DB 历史重载描述为运行已验证；历史识别依据仍是前述持久指令标记与保存路径静态证据。

测试结束核对：原配置 `enabled=true`、四通道 `codex_subscription` 均恢复，ADB reverse 为空。Codex 账号存储未读取、改写或清除，设备交互权已归还主任务。
