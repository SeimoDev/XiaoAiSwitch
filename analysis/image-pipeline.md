# 绘图链路与原生图片回填：静态证据

## 样本和覆盖范围

样本为本机 `com.miui.voiceassist` 7.11.20.3514（versionCode 507011020），APK SHA-256：`ae216062a595e58b0ef6fe7969de2696e00e427d5fd9c2c5d2e4ac07acb18e06`。以下依据本地 JADX 1.5.6 输出 `analysis/decompiled/full/sources`；整体反编译有 193 项错误，以下结论只覆盖已查看的定义与消费者。没有修改设备、`app` 或 `core`。

下文代码路径均相对上述 `sources`。这里的类名以实际混淆包名为准，不用 Kotlin SMAP 原名当运行时类名。字段名必须结合 DEX 校验：例如 `b30.o0` 的 Handler 原始字段名为 `y`，`f9396y` 仅为 JADX 别名。

## 1. 初次生成与取结果是两段协议

### 文生图入口

- `kg0.c` 是 Txt2ImgInputManager；`triggerTxt2ImgGen(inputModuleView,inputQuery,showQuery)`（`:613-629`）先调用 `u()` 更新绘图上下文，再调用 `vf0.a.sendQueryFromTxt2Img(...)`。
- `kg0.c.u()`（`:632-647`）将 `c()` 产生的 `style` / `ratio` JSON 与 `MultiModal.MultiModalContentScene.CREATE_IMG` 包装成 `TextAiImageInfo`，交给 `com.xiaomi.voiceassist.engine.context.b` 的状态管理器。
- `vf0.a.sendQueryFromTxt2Img(Context,String,String)`（`:836-844`）停止旧引擎后调用 `a50.h0.startQuery(new a50.h0.d().setQuery(inputQuery).setQueryOrigin(...).build(),showQuery,false)`。所以该 UI 入口是带绘图上下文的 AIVS query，不是 `GET_MT_IMG` HTTP 请求。
- `wf0.f.buildTxt2ImgQuery(...)`（`:111` 起）可把“前缀 + 用户描述 + 风格 + 比例”拼成显示文本；应区分这里的 `showQuery` 与实际输入 `inputQuery`。

### Web 图像生成入口

`vf0.a.sendWebImageGenEvent(String query,String imageFileId,String param)`（`:847-878`）构造 `MultiModal.ImageGeneration`：

```json
{
  "query": "PAYLOAD",
  "image_ids": ["IMAGE_FILE_ID"],
  "biz": "BIZ",
  "params": {}
}
```

`query`、`image_ids`、`biz`、`params`、`retry` 在 `MultiModal.java:1584-1641` 的模型中均为 optional；但此消费者的方法参数非空，并实际设置 query、单个 image id，再按 JSON 内容设置 biz/params。它用 `APIUtils.buildEvent`，附加禁用 TTS 的 RequestControl context，最后 `b30.g.getInstance().sendEvent(event)`。这与文生图 query 入口是两个应覆盖的实际入口。

初次 AIVS 服务端返回 `Template.LLMImageGeneration` 后才有下面的 taskId 拉取流程；仅 hook `/llm/proxy` 会落在生成后的阶段。

## 2. 原厂生成卡片不是直接 URL 卡片

`Template.java:6135-6264` 的 `LLMImageGeneration` 明确 `@Required`：

- `task_ids: List<String>`
- `cp_name: String`
- `scenes_message: List<LLMImageScenesMessage>`

`model`、`status_code`、`name`、`image_business_type` 等是 optional。每个 `LLMImageScenesMessage`（`:6354`）必填 `code` 与 `message`。

消费者链：

1. `xb0.d` 的工厂匹配 `Template.LLMImageGeneration`（`:472`），创建 `sd0.nb`（`:783`）。
2. `sd0.nb.h0(int)`（`:150-177`）构造 `ie0.a2` 模型、`od0.a` 控制器和 `fc0.y8`（TemplateImageCreativeCard），并注册回调。
3. `ie0.a2.b(Instruction)`（`:103-142`）从 `header.dialog_id` 得 requestId，拷贝 `task_ids`，读取 `model` 和 `scenes_message`，为每个任务初始化结果码。
4. `sd0.nb.L()`（`:57-101`）在非历史模式要求 taskId 列表非空；如果全部任务为 QUERY_ILLEGAL 则失败；正常调用 `od0.a.pullAllImages()`，返回处理中状态。
5. `od0.a.pullAllImages()`（`:295` 左右）调用 `a(requestId,taskIds)`，有缓存 URL 时直接下载，未缓存任务才组成 `ImageCreativeEntity` 进入下载器。

因此，把自选绘图 API 的 `url` 塞进 `LLMImageGeneration` 的额外字段不会自动显示图片。该卡片期待 taskId、结果状态、拉取和缓存回调的完整配合。

## 3. GET_MT_IMG 的真实请求与响应

`od0.a` 的内部 companion 原始类位于 `od0.a$a`（JADX 显示 `C1414a`）：

- `a(String requestId)`（`:72-79`）生成 header：

```text
LLM-Service: ARES
LLM-Action: GET_MT_IMG
LLM-Method: GET
Request-Id: REQUEST_ID
```

- `b(String requestId,String taskId,String model)`（`:81-93`）从 app/device/queryInfo 获取字段，构造六个非空 String 参数的 `ImageCreativeEntity`：

```json
{
  "task_id": "TASK_ID",
  "app_id": "APP_ID",
  "device_id": "DEVICE_ID",
  "request_id": "REQUEST_ID",
  "model": "MODEL",
  "query": "PAYLOAD"
}
```

`ImageCreativeEntity.java:48-61` 的 Kotlin 构造器对六个值分别 `checkNotNullParameter`；`emptyEntity()` 虽允许空字符串，但真实取图构造器传入上述业务值。

两个 Retrofit 声明都使用实际 HTTP **PUT** `/api/access/oauth/llm/proxy`，而 header 内部语义是 GET：

- `rh.k.pullImageCreativeData(Map,ImageCreativeEntity,Continuation)`：本次找到实际消费者 `od0.e$g$a.invokeSuspend`（`od0/e.java:950-979`），得到 `r51.i0`（响应体）后 `.string()`、`.close()`，交给 `od0.e.j(...)`。
- `rh.j.requestImageCreativeData(Map,ImageCreativeEntity)`：Rx 风格声明也在 APK 中；本次未定位其有效调用者。其存在不足以证明当前卡片仍走该旧接口。

`od0.e.j(String taskId,String response,int index,int count)`（`:1262-1318`）的成功响应要求如下，而非 OpenAI-compatible 图片响应形状：

```json
{
  "code": 200,
  "data": {
    "tasks": [
      {
        "img_url": "https://HOST/PAYLOAD.png",
        "thumb_url": "https://HOST/PAYLOAD-thumb.png"
      }
    ]
  }
}
```

`od0.e.h(...)`（`:1227-1251`）逐个消费 tasks：`img_url` 非空才成功；`thumb_url` 为空时使用 `img_url` 代替缩略图。它更新模型的 thumb/image URL map，调用 `downloadThumbBitmap(...)`。

`od0.e.d(...)`（`:1101-1109`）通过 Glide 下载缩略图字节，回调后更新结果；`sd0.nb.onResult()` 据结果决定 operation 成功/失败。原厂历史存储（`sd0.nb.onProvideSaveToHistoryIns`）还写入 `thumb_Url`、`image_url`、`result_code` JSON maps。

该下载器包含并发、超时/取消/重试协程，但 `od0.e$g.invokeSuspend` 在本次完整反编译中没有恢复出可靠 Java 方法体。准确轮询间隔和重试次数保持未知；此处不把 GET_MT_IMG 的“取结果”泛化成已证明的固定频率轮询。

## 4. 可直接显示图片的最小原生回填

### 选择实际有消费者的 ImageContentGallery

原生执行工厂 `xb0.d` 匹配 `Template.ImageContentGallery`（`:547`）并创建 `sd0.ob`（`:874`）。`sd0.ob.g0(int)`（`:33-48`）构造 `fc0.l1`。`fc0.l1$b.init(Instruction)` 取 `payload.items`，逐项调用 `e(...)`；`e`（`:98-142`）直接读取 `item.img.sources[0].url`，执行 `gb0.a.with(context).load2(url).into(imageView)`，并把 `img.description` 作为标题。

类型约束：

- `Template.ImageContentGallery`（`Template.java:5296`）：title/items/more 均 optional；但要显示图片，items 需至少一项。
- `Template.ImageGalleryItem`（`:14051`）：`img` 必填。
- `Template.Image`（`:16256`）：`description`、`sources` 必填。
- `Template.ImageSource`（`:5595`）：`url` 必填；宽高等 optional。

最小**显示**指令：

```json
{
  "header": {
    "namespace": "Template",
    "name": "ImageContentGallery",
    "id": "INSTRUCTION_ID",
    "dialog_id": "REQUEST_ID"
  },
  "payload": {
    "title": "绘图结果",
    "items": [
      {
        "img": {
          "description": "PAYLOAD",
          "sources": [{"url": "https://HOST/PAYLOAD.png"}]
        }
      }
    ]
  }
}
```

该卡片布局最多直接呈现六项（`fc0.l1$b` 构造六个 item view，`init` 按索引判断范围），超过六项应分页或选择前六项。

**点击行为需要另行适配**：同一个 `e(...)` 无条件为图片安装点击监听；`fc0.l1$b.g(ImageLauncher)`（`:145-170`）会取 `launcher.intent.uri` 后调用 `Intent.parseUri(uri,1)`。仅显示用最小 schema 没有 launcher，应在实现时验证/处理点击，避免把“静态显示字段足够”误报成“点击交互已完整”。若提供 `ImageLauncher.intent`，其 `ImageAndroidIntent` 在 `Template.java:5110` 的必填字段是 `type`、`pkg_name`、`uri`；只给 launcher.url 也没有消除该消费者先解析 intent URI 的行为。

### base64 的支持链已定位

图片 URL 字符串可用 `data:image/png;base64,PAYLOAD`，而不是裸 base64：

1. Gallery 通过 `gb0.a.with(context)`（GlideProxy，`:69`）使用实际应用 Glide 实例。
2. `com/bumptech/glide/m.java:142` 为 String/InputStream 注册 `new g6.e.c()`（DataURL loader）。
3. `g6.e.handles(...)`（`:37-40`）匹配 `startsWith("data:image")`。
4. `g6.e$c$a.decode(String)`（`:116-128`）要求逗号存在、逗号前以 `;base64` 结束，再 `Base64.decode` 成 ByteArrayInputStream。

因此，上述 sources[0].url 可替换为合法 PNG/JPEG data URL，消费者具有完整静态解码链。真实大图的内存、布局、点击和历史表现仍需设备端验证。

### 两条容易误判的路径

- `Template.ImageCard` 的协议类型和 AIApiNameMapping 存在，但本次在 `xb0.d` 没找到对应 operation 创建分支；静态存在的 schema 不足以证明经 `engine.W` 注入后可显示。
- `Template.Toast.image` 会被 `sd0.od.q0(...)`（`:257-279`）当作 RichTextModel 的 sticker；`RichTextHandler.java:510-512` 使用 `asGif().load2(sticker.url)`。这不是普通 PNG 绘图结果的推荐路径。

## 5. 对模块实现的直接指引

1. 在实际生成入口路由，而不是只改 GET_MT_IMG：至少覆盖带 `CREATE_IMG` 上下文的 query，以及 `MultiModal.ImageGeneration` Event。语音“画图”是否被识别成此上下文仍需运行证据；关键字分类属于模块设计，不是这里观察到的原厂协议事实。
2. 自选后端完成生成后，把 URL / b64_json 转成上面的 Gallery 指令，以同一 REQUEST_ID 走主任务已验证的本地非流式回答收尾链。通过实际引擎 Handler 原始字段 `y` 串行提交；不要直接使用 JADX 的字段别名 `f9396y`。
3. 如果要保留原厂 `fc0.y8` 的专用绘图卡片、保存/历史/重试功能，就要同时适配 task_ids 模型和 GET_MT_IMG 响应消费者；Gallery 是另一张真实原生卡片，不等价于已经复刻专用绘图卡片的全部能力。
4. 生成 API 是否拥有独立 system role，应由具体后端适配器决定。图片提示词前缀、OpenAI-compatible 图片 prompt 与 chat System Prompt 是不同字段；设置界面应保留这种差别。

## 待验证项

- 新绘图 route 的实际 hook 命中、生成请求携带配置模型/提示词、原生 Gallery 显示真实返回图片。
- Gallery 点击、宽高适配、清理/取消与历史记录。
- `MultiModal.ImageGeneration.image_ids` 来自原厂文件服务；自选图生图后端如何取得对应原图，需要单独验证。
- 原厂云端生成任务的模型版本及系统提示词、GET_MT_IMG 精确重试/轮询策略仍未由本次证据证明。
