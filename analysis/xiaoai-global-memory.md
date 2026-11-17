# 超级小爱原厂记忆：8.2 路径与接入边界

样本：`com.miui.voiceassist` 8.2.70.8421（508002070），APK SHA-256
`ff1752a68b94eaa1654fa85737161c4d332b9bcaf3df8df606cd8138d2a71e4b`。
以下为本地 APK Manifest 和已有 JADX 的静态证据；未连接记忆服务、未读取真实条目或账号数据。

## 原厂实际路径

| 场景 | 入口和行为 |
| --- | --- |
| 原厂对话中的记忆开关 | `engine.context.j0.generateApplicationState`、`engine.context.b0` 把 `SwitchFeature.MEMORY` 和 `i1.q0.isUseAiMemory()` 放入 `Application.State.switch_status`。这里传的是开关，不是整份记忆。 |
| 对话里新增 | 云端可返回 `Application.LocalMemory`；`bf1.a` 选中 `ah1.e`，原厂在 IO 线程调用 `hh1.z0.saveByLocalMemoryOp`，分开处理人物关系、隐私和普通记录。这个指令是预处理后的写入结果，不是让模型自行构造的通用“记住”命令。 |
| 对话里展示／使用 | `Template.AIMemory → zg1.yd → jf1.fa` 构建原厂记忆卡；`Template.AiMemoryEmbeddedCard → ah1.k` 附加到原厂回答卡。卡片渲染和存储不是同一步。`Template.MemoryUse`／`MemoryUpdate` 在 API 映射中存在，但没有据此证明它们可以调用写入或检索。 |
| 原厂记忆管理界面 | 可见的原厂设置入口是 Manifest 导出的 `com.xiaomi.voiceassistant.settings.MemorySettingActivity`；记忆首页由 `gh1.d0.startMemoryHomePage` 调原厂 UI bridge，最终进入 `ReactNativeActivity.loadMemoryPage`。原生管理页的 JS bridge `MemoryAcceptJsModule` 提供 `getAllList`、`insertData`、`updateData`、`deleteMemoriesByIds`、`syncServerData`，委托 `hh1.z0` 与原厂同步。单条删除和清空都不是普通检索工具。 |
| 原厂专家模式工具 | 随包实现的 `oq.d`（XiaoAiMemoryClient）正常绑定 `UIAgentServiceForOSBot`；`read_memory` 调 `lh1.b`，将本机关系、隐私及普通记录与云收藏合并；`write_memory` 调 `lh1.c → lh1.a.extractMemory`，提交原始记忆请求给原厂提取服务，解析回包，再走同一个 `saveByLocalMemoryOp`。专家模式自身的记忆索引并不等同于超级小爱记忆。 |

工具服务虽导出，Manifest 仍要求 `com.aios.osbot.permission.BIND_APP_TOOL`（签名权限），
`AppToolProviderService` 还验证调用方；没有把它当作普通第三方 API。
原厂记忆开关和 `MemoryAuthHelper.needAuthCheck` 是不同条件；原厂管理页保留密码验证和
原账号同步，不能拿 XP 模块已激活来替代。如果宿主调用也遇到签名、登录或权限失败，
按原厂结果失败，不替换判定、不读取底层数据库或网络凭据。

## 模块可落地的接入顺序

1. **先恢复明确的原厂记忆请求。** 文本请求仅对用户明确的新增、查看/检索及管理命令，
   将本次原生 NLP event 原样交给小爱，不把它的记忆结果转发到所选模型。普通聊天仍由
   模块处理，不以任意出现“记忆”一词判定。因为语音用了 `onlyAsr`，语音最终文本命中
   后需要发起真正的原厂文本查询；不能仅在最终结果处 return。原厂记忆开关卡自身使用
   `x51.m0.startQuery(x51.m0.e.setQuery(...).build())` 重发当前请求（`ah1.o0`），
   可优先验证该路径。核对双工新查询的 dialog ID，避免模块误吞原厂存储/结果指令，
   或出现同一条语音记忆被写两次。删除和清空仍留在原厂管理页。
2. **自定义模型引用记忆需要单独开关与明确选择。** “打开/使用原厂记忆”不等于
   “允许把整份个人记忆发给已配置 Provider”。如要支持自定义问答引用，另行授权
   指定 Provider 和被用户确认的普通条目；在宿主侧限制结果范围。原厂
   `read_memory` 的原样结果含隐私、关系、云收藏；不得整体注入提示词或写日志。
   输入语义不明确时，不自行写入；确认成功以实际原厂查询/写入结果为准。
3. **验收要区分两种路径。** 用唯一测试条目，从模块接管开启的状态走文本新增、
   语音新增和跨会话取回，在原厂管理页核对实际条目和去重，再只删除本次测试条目。
   另测关闭原厂记忆、原厂认证未过、普通聊天保持自定义、Provider 网络记录未包含
   原厂记忆读出结果；能展示卡片并不代表写进同一个仓库。

## 模块接入

8.2 在「通用设置」支持关闭或开启原厂记忆命令分流，并可直接打开原厂管理设置。
明确的记忆文字请求原样走原厂 NLP（若含 `/memory` 则只移除本地路由前缀）。
语音最终转写命中记忆命令时，结束原有纯 ASR 流并使用原厂查询入口重发；普通聊天不变。
模块不读原厂条目、不把条目加入自选模型的 System Prompt，也不绕过原厂记忆开关和验证。
真机确认原厂设置可打开，并使用不读取、不新增的合成文本核验原厂问答路由。
用户随后用语音查询一个合成名称，确认收到原厂回复；设备日志也对应识别末段、
原厂 NLP 查询和随后应答。这次没有新增、修改或导出实际记忆条目。

### 三指上滑收藏

三指上滑不是附图问答。`VoiceService` 对 `three_gesture_up` 先走原厂屏幕权限
和截图，再让 `co1.a.executeAction` 将图与本次原厂会话关联；随后 QuerySender
只发固定文本「记一下」。原厂会用同一识屏会话处理后续
`MultiModal.UploadMultiModal(SCREEN_COLLECT)`，由 `zg1.eh → yb1.g.collectScreenShot`
使用原截图保存收藏。把第一条文本当普通聊天拦下，会让模型只看到「记一下」。

模块现在按该事件自己的 `General.RequestState.origin` 保留原厂链路。
三指上滑的精确来源是 `com.miui.voiceassist.query&&three_gesture_up`；
同样保留原厂已知的侧键、自动收藏与专家收藏来源。
这不依赖“原厂全局记忆”对话分流开关，不改原厂权限，不读取或转发截图；
普通文本「记一下」不因这次修复被误分流。

真机在自有控件测试页做三指上滑，用户确认出现收藏成功提示；日志也依次出现
原厂授权截图、`three_gesture_up sendNlpRequest`、`SCREEN_COLLECT` 与原厂收藏处理。
