# 图生图原图、上传回调与 Hook 点

## 样本、证据与结论范围

- APK：`artifacts/com.miui.voiceassist/VoiceAssistAndroidT.apk`，7.11.20.3514 / 507011020。
- 本次重新计算 SHA-256：`ae216062a595e58b0ef6fe7969de2696e00e427d5fd9c2c5d2e4ac07acb18e06`。
- 静态正文：JADX 1.5.6 已有输出 `analysis/decompiled/full/sources`。下文源码路径均相对此目录。
- **I1：原始 DEX 符号证据**：`analysis/image-input-dex-symbols.json`。直接解析 APK 的 string/type/proto/field/method/class_data 表，包含原始类名、字段、方法描述符和 DEX 内 `code_offset`；没有把 JADX 的 `fNNN` 字段别名用于反射。
- **I2：Manifest 证据**：`analysis/image-input-manifest.txt`，由本机 SDK `aapt2 dump xmltree` 静态导出。不是设备运行结果。
- REA Android provider 缺少 `REA_JADX_MCP_JAR` 的阻塞已由主调查确认；未重复配置，也未伪造 REA Evidence ID。本次使用现有反编译与 APK 原始结构继续验证。
- 本次未运行 APK、未执行 adb/设备动作，未修改 `app`、`core`、`scripts`。既有真机已通过项目由主调查记录，本报告只补原图输入链。

## 1. 观察：最小可用结论

1. **只 hook `vf0.a.uploadImageFile` 会漏掉原生选择图和 Web 路径。** 三条已查看生产路径最终均经过 `wf0.e.uploadImageFile(Context,String,p20.b)`；原生 `AiImg2ImgActivity.C1` 和 Web `vf0.a.handleWebAiImageGen` 直接调用后者。
2. 原图对照已由原厂实现：`vf0.a.saveImageLocalPath(fileId,path)` → `m20.z0.putString(fileId,path)`。读取是 `m20.z0.getString(fileId, "")`，不是从云 `file_id` 推导下载 URL，也不是一个内存图片池。
3. 原生图生图 context 是 **`generate_image_scene = EDIT_IMG`，原图 ID 在 `image_info.image_id`**。顶层 `MultiModalState.image_id` 虽存在，但此消费者没有设置它。
4. 文生图 context 是 **`generate_image_scene = CREATE_IMG` + `generate_image_params`**，不设置 `image_info`。
5. 原生上传成功可在 `wf0.e.uploadImageFile` 的原有 `p20.b` 上交还非空本地 UUID，让原厂后续代码保存映射、持久化原图并更新 UI/context。此结论来自静态消费链；模块应仅对已确定接管的图生图路径启用，保持普通图片上传原样。

## 2. 观察：原图取得、裁剪、上传、持久化

### 原生选图入口

`com.xiaomi.voiceassistant.mainui.ai_img.img2img.AiImg2ImgActivity` 的 `H1()` 打开选图页，`mf0.q.launchImage()` 发起 `android.intent.action.PICK` / 图片 MIME，`mf0.q.n(Intent)` 指定原厂图库；其 ActivityResult 回调最终进入 Activity 的 `e1` / `f1`。

链：

```text
图库 Uri
  → AiImg2ImgActivity.f1(..., Uri)
  → l0.getMultiModalBridge().processSelectImageUri(uri, true [默认参数])
  → vj0.t.processSelectImageUri
  → ak0.o.processUri
  → ContentResolver.openInputStream(uri) 复制到 getMultiModalDir()/文件名
  → file:// 本地 Uri
  → K1 / j1 / T0 / S0 [按界面尺寸可裁剪]
  → N1(croppedUri)
  → C1(Context,Uri,p20.b)
  → wf0.e.uploadImageFile(Context,path,new AiImg2ImgActivity$n(path,callback))
```

源码：Activity `:1346-1358, 2464-2472, 2551-2553, 2721-2756, 3353-3377`；`ak0/o.java:59-109`；`mf0/q.java:88-128, 271-281, 356-381`；`vj0/t.java:341-343`。`ak0.o.processUri` 当前还检查网络和 15 MiB 文件有效性；仅替换上传不改变这些上游行为。

`C1` (`:2057-2079`) 使用 **`uri.getPath()` + `new File(path)`** 检查真实文件，再上传。这不是把任意 `content://` 的 `getPath()` 当可读文件；正常选图之前已通过上述复制落地。

### 原厂上传链与返回语义

```text
wf0.e.uploadImageFile(Context,path,callback) : void
  → c(Context,path, new wf0.e$d(startTime,callback), true)
  → IO coroutine: s70.a.compressBitmap(path,1048576,80)
  → main: wf0.e$d.onSuccess(Bitmap)
  → wf0.e.b(Bitmap,callback)
  → wf0.e.e(new uq0.g$a(bitmap,80),new wf0.e$a(callback))
  → uq0.r.upload(...,new wf0.e$c(...))
  → uq0.g.uploadImageForUpload [独立图片上传引擎，ImageStreamStarted/upload_only]
  → MultiModal.FileUploadResult
  → uq0.r$c.onResult(List<FileUploadInfo>) [先检查 UPLOAD_SUCCESS]
  → wf0.e$c.onResult(FileUploadInfo)
  → wf0.e$a.onSuccess(String) [postOnUiThread]
  → 调用者 callback.onSuccess(fileId)
```

源码：`wf0/e.java:76-124, 172-191, 211-237, 253-299, 314-330, 373-380, 400-403, 511-516`；`uq0/r.java:102-143, 191-208`；`uq0/g.java:163-189, 244-265, 411-441`。

真实云上传字节还会由 Bitmap JPEG quality=80 压缩、分帧；`saveImageLocalPath` 保存的是上传前的本地输入路径。因此模块读取本地文件拿到的是所选/可能裁剪后的输入图，未必与原厂云端 JPEG 字节逐位一致；不要把它描述为生成结果图，也不要假定一定保留未裁剪的图库原图。

### 成功和失败

| 实现 | 成功行为 | 失败行为 |
| --- | --- | --- |
| `wf0.e$d` | Bitmap 有效后转交上传 | 读图失败向上回调 `ErrorResult` |
| `wf0.e$c` | `FileUploadInfo.getFileId()` 非空才向上 `onSuccess` | 空 ID → `ErrorResult("fileId is empty")`；上传错误字符串 → `ErrorResult` |
| `wf0.e$a` | 再次通过 `o70.e.postOnUiThread` 交还原调用者 | 同样 post 主线程再交还 `onError` |
| `AiImg2ImgActivity$n` | **先 `saveImageLocalPath(id,原path)`，再调用外层成功** | 记日志并调用外层失败 |
| `AiImg2ImgActivity$k` [Intent 初始图] | `J1` 持久化，再次保存映射；`C2(id)`，显示选图 | 提示编辑失败，`c2(null)`，显示空图态 `m2()` |
| `AiImg2ImgActivity$p` [选图/裁剪] | `J1` 持久化，再次保存映射；`c2`、`C2(id)`、预览，可继续执行选中功能 | 提示上传失败，清选图并回空图态 |
| `vf0.a$e` | 保存映射后调用传入 callback | 原样交还 callback.onError |
| `vf0.a$c` [Web] | 保存映射后带 query/id/params/path 开主界面 | 日志，不进入生成 |

源码：Activity `:545-593, 662-696, 718-770`，`vf0/a.java:288-326, 364-400`。

### 原图的第二次持久化

`AiImg2ImgActivity.J1(String fileId,String localPath)` (`:2406-2444`) 将现有文件复制到：

```text
context.getFilesDir()/aiimg_upload/{fileId}.jpg
```

它是流复制，不是按扩展名重编码。`k.onSuccess` 和 `p.onSuccess` 拿到返回路径后再次调用 `vf0.a.saveImageLocalPath` 覆盖映射，再设置所选 Uri。故模块缓存应允许同一 ID 的路径更新；不要仅保留第一次临时路径。

### 映射是否覆盖所有成功路径

**在本次已追踪的三个生产上传入口中，成功都会保存映射：**

- 原生预上传：`Activity.C1 → wf0.e → Activity$n.onSuccess → saveImageLocalPath`，`k/p` 还会覆盖持久路径。
- 原生/provider 延迟上传：`vf0.a.a → vf0.a.uploadImageFile → wf0.e → vf0.a$e.onSuccess → saveImageLocalPath → vf0.a$a.onSuccess → m`。
- Web：`handleWebAiImageGen → wf0.e → vf0.a$c.onSuccess → saveImageLocalPath`。
- 已有 imageFileId 的缓存路径：`vf0.a.a` (`:410-419`) 不再上传，但仍 `saveImageLocalPath(existingId,path)`。

有界例外：`com.xiaomi.voiceassistant.g1.n()` (`:577-626`) 接收主 Activity 的 Web extras 时还 **直接** `m20.z0.putString(id,path)`，没有经过保存方法；这是上面 Web 正常成功后的冗余写入，亦说明人为仅直调该主界面 extras 路径时，`saveImageLocalPath` hook 未必命中。读取原厂 `m20.z0.getString` 可覆盖这种已写入持久映射的情况。没有声称整个 APK 所有其它普通多模态上传均使用该保存方法。

`m20.z0` 实际为 MMKV `mmkvWithID("default",2)` 的 wrapper (`:2643-2653, 2675-2677`)；不是普通 XML SharedPreferences 文件。原厂输入卡 `wf0.b.addImageCardImmediately` (`:325-347`) 也以 `z0.getString(id, "")` 读取路径并检查文件存在。

## 3. 观察：准确的原始 DEX Hook 符号

原始成员名已用 I1 校对。`$` 是运行时内部类分隔符。泛型在反射签名中擦除。

| 原始类 | 方法 / 字段 | 用途 |
| --- | --- | --- |
| `wf0.e` | `uploadImageFile(android.content.Context,java.lang.String,p20.b):void` | 三条已查绘图输入路径的共同上传边界 |
| `vf0.a` | `uploadImageFile(Context,String,p20.b):void` | provider 包装层；不是所有绘图输入的共同点 |
| `vf0.a` | `saveImageLocalPath(String,String):void` | ID→原图路径保存/更新 |
| `vf0.a` | `sendQueryFromImg2Img(Context,String,String,String):void` | native 文字生成 query，第四参是 ID；上下文由前面状态管理器提供 |
| `vf0.a` | `sendQueryFromTxt2Img(Context,String,String):void` | 文生图 query |
| `vf0.a` | `sendWebImageGenEvent(String,String,String):String` | Web 生成 Event；返回新 event/dialog ID，不是图片 ID |
| `vf0.a` | `handleAiImg2Img(String,String,String,String,String,String):void` | from/inputQuery/showQuery/imageLocalPath/imageStyle/imageFileId |
| `vf0.a` | `handleWebAiImageGen(String,String,org.json.JSONArray):void` | Web query/localPath/queryParams |
| `m20.z0` | `getString(String,String):String` / `putString(String,String):void` | 原厂持久映射读取/写入 |
| `AiImg2ImgActivity` [完整包名见上] | `C1(Context,android.net.Uri,p20.b):void` | Uri→path 检查并传入 Activity$n |
| 同上 | `C2(String):void` | 更新 AiImageFileId 和图生图状态 |
| 同上 | `J1(String,String):String` | 复制输入图到 files/aiimg_upload |
| `i30.k0` | `updateStateContext(i30.i0):void` / `buildMultiModalContext():com.xiaomi.ai.api.common.Context` | 状态保存/一次性构建 |
| `k30.b` | `buildContext(com.xiaomi.voiceassist.engine.context.multimodal.ImageAiImageInfo):Context` | native EDIT_IMG context 构造 |
| `k30.d` | `buildContext(com.xiaomi.voiceassist.engine.context.multimodal.TextAiImageInfo):Context` | native CREATE_IMG context 构造 |

### p20.b 的回调精确签名

```text
p20.c:
  onSuccess(java.lang.Object):void
  onError(java.lang.Object):void
p20.b extends p20.c:
  onSuccess(java.lang.Object):void
  onError(com.xiaomi.voiceassist.baselibrary.utils.callback.ErrorResult):void
```

`p20.b` 是接口，不应把抽象接口方法当成有实际方法体的全局 Xposed 拦截点。取上传参数第 3 个真实 callback 对象进行包装或调用。

已确认 `vf0.a$a`（JADX 的 `C1966a`）、`vf0.a$c`、`vf0.a$e`、`wf0.e$a`、`AiImg2ImgActivity$n`、`AiImg2ImgActivity$p` 的实际方法包含：

```text
onSuccess(java.lang.String):void       // 真实实现
onSuccess(java.lang.Object):void       // bridge, access 0x1041
onError(ErrorResult):void              // 真实实现
onError(java.lang.Object):void         // bridge
```

`wf0.e$c` 是另一层 `l20.c`：`onResult(MultiModal$FileUploadInfo)`、`onResult(Object)` bridge、`onStart(FileUploadInfo)`、`onProcess(float)`、`onError(String)`。不要误把这一层称作 `p20.b.onResult`。

### 原始字段（不是 JADX 别名）

```text
vf0.a.a                         : vf0.a    // singleton；非 f123257a
wf0.e.a                         : wf0.e    // singleton；非 f129785a
com.xiaomi.voiceassist.engine.context.b.r : i30.k0 // 非 f29954r
ImageAiImageInfo.a               : String  // fileId；getFileId()
ImageAiImageInfo.b               : String  // paramNodeJson；getParamNodeJson()
TextAiImageInfo.a                : String  // sceneName；getSceneName()
TextAiImageInfo.b                : String  // paramNodeJson
AiImg2ImgActivity.X2             : Uri     // selected image Uri
AiImg2ImgActivity$n.a            : String  // initial upload path
AiImg2ImgActivity$n.b            : p20.b    // downstream callback
vf0.a$e.b                       : String  // upload path
vf0.a$e.c                       : p20.b    // downstream callback
```

优先调用上述已存在 getter，避免反射字段别名和耦合。`MultiModal$MultiModalState` 的原始字段为 `image_info`、`generate_image_scene`、`generate_image_params`、`image_id`；各自是 `sx.a` Optional 包装，不是裸 String/裸 ImageInfo。

## 4. 观察与推断分开：native context 和 Web event

### 文生图 native context

**观察**：`kg0.c.triggerTxt2ImgGen` → `u()` → `TextAiImageInfo(CREATE_IMG, params)` → `context.b.r.updateStateContext`。`k30.d.buildContext` 实际写入的 scene 固定为 CREATE_IMG，不读取 `TextAiImageInfo.getScene()` 决定分支。params 为 UI 选择的 `style` / `ratio`，空值可省略。

**静态推导的序列化形状**：

```json
{
  "header": {"namespace": "MultiModal", "name": "MultiModalState"},
  "payload": {
    "generate_image_scene": "CREATE_IMG",
    "generate_image_params": {"style": "STYLE", "ratio": "RATIO"}
  }
}
```

### 图生图 native context

**观察**：`Activity.C2(id)` 或 `vf0.a.m(...)` 设置 UiManager 的 AiImageFileId，并更新 `ImageAiImageInfo(id, {style})`；`k30.b.buildContext` 设置 `image_info.image_id=id`、`image_info.image_type=qs.b$a.IMAGE.getSpecType()`（其常量是 **`OTHER`**）、scene EDIT_IMG、params。没有设置 image_info.base64Data、image_source 或顶层 image_id。

```json
{
  "header": {"namespace": "MultiModal", "name": "MultiModalState"},
  "payload": {
    "image_info": {"image_id": "FILE_ID", "image_type": "OTHER"},
    "generate_image_scene": "EDIT_IMG",
    "generate_image_params": {"style": "STYLE"}
  }
}
```

注意：`MultiModalContentScene.getId()` 的语义编号为 `EDIT_IMG=0`、`UNDERSTAND_IMG=1`、`CREATE_IMG=2` (`MultiModal.java:3784-3799`)；这不等于已观测到网络 JSON 使用数字。`APIUtils.buildObjectMapper` (`:49-66`) 未启用枚举 index 序列化，`i9.f0.WRITE_ENUMS_USING_INDEX` 默认为 false，以上名称字符串形状为静态推导，仍应以主调查真实事件日志作最后确认。路由读取对象可直接取 enum.name()，同时兼容已有数字 ID 输入。

`Activity.C2("")` 会落入 TextAiImageInfo(CREATE_IMG,params) 的 fallback，而非无原图 EDIT_IMG。`ImageAiImageInfo.isValid()` 仅检查 fileId 非空，`TextAiImageInfo.isValid()` 仅检查 params JSON 非空（`{}` 是有效）；不是验证云文件存在。

`i30.k0.buildMultiModalContext()` 会构建后 `clearAllState()`，状态是一次性消费。它还优先检查 UiManager 的 `getMultiModalContextFileId()`：该普通图片输入上下文路径可能先于绘图状态生效。因此生成路由应解析即将发送的真实 event context，而不是在普通文本时刻重复读取已清掉的状态管理器。

### Web 图生图

`UtilAcceptJsModule.lambda$sendAiImageGenEvent$4` (`:293-313`) 将 Web base64 输入落为 `Xiaoai_AiImg_Ori_<time>.jpg`，调用 `wf0.a.handleAiImageGenFromWeb` → `vf0.a.handleWebAiImageGen`。

之后 `vf0.a$c.onSuccess` 使用 queryParams **第 0 项**，保存映射并打开 TabHostMainActivity。`g1.n` 最后调用 `sendWebImageGenEvent`：

```json
{
  "header": {"namespace": "MultiModal", "name": "ImageGeneration", "id": "REQUEST_ID"},
  "payload": {
    "query": "PROMPT",
    "image_ids": ["FILE_ID"],
    "biz": "BIZ",
    "params": {}
  },
  "context": ["Execution.RequestControl 禁用 TTS 的实际 Context 对象"]
}
```

这里 `biz` 来自 param JSON 的 `biz`（空则不设置），`params` 来自 param JSON 的 **嵌套** `params` 对象；不是把整个 param 当 generate_image_params。此方法没有设置 native MultiModalState 的 `generate_image_scene`；应通过 ImageGeneration + image_ids 识别 Web 图生图，不强求 CREATE_IMG/EDIT_IMG。

## 5. 推断：适合当前模块的接法

### 保留普通上传，解析原图映射

1. 在 `vf0.a.saveImageLocalPath` after-hook 记录非空 ID→path 更新；同一 ID 允许覆盖。
2. 图生图生成时：native 从 context `image_info.image_id` 取 ID；Web 从 payload `image_ids` 取 ID。
3. 首选 `m20.z0.getString(id, "")` 或模块已有映射，读取目标 app 进程内可访问的真实文件。原厂主界面普通输入卡也这样取原图。
4. 将文件字节交给自选 image-edit 后端；不是把小米云 ID 原样当自选供应商 image 输入。

### 明确原生图生图时用本地 UUID 替代云上传

当前静态消费链支持这条设计：

```text
before wf0.e.uploadImageFile(Context,path,callback)
  条件：启用自选图片源 && 已确定接管此原生图生图
  具体生产 callback 名：
    com.xiaomi.voiceassistant.mainui.ai_img.img2img.AiImg2ImgActivity$n
  path 是真实可读文件
  生成不含 / 或 \\ 的 UUID String
  省略原上传方法；它的正常返回是 void
  post 到 main Looper：callback.onSuccess(localId)
  由原厂 $n → $k/$p/$g 保存映射并继续 UI/context
```

`Activity$n` 的第三参外层 callback 区分初始化、裁剪和功能按钮。保留该真实 callback 能保留持久化、样式、预览、错误 UI 和可能继续触发选中功能的原厂行为；直接改 `C2(id)` 不等价。

**确认 opaque String 的静态边界**：本次已读代码只把 ID 用于非空/blank 判断、MMKV key、UiManager 字段、生成 context、文件名 `{id}.jpg`、输入卡引用/extra。没有观察到 UUID 形式以外的格式校验或本地查询云文件 ID 真伪。原厂 `FileUploadInfo` 成功状态属于已被替代的上传层，`p20.b.onSuccess` 的后续消费不接收该状态对象。因此本地 UUID 可满足这条消费链；后续生成事件必须由模块接管，不能把虚构云文件 ID 继续送回原厂生成服务。

时序约束来自生产实现而非理论防御：原上传回调是异步、主线程；同步在 before-hook 内直接执行可能在原厂调用栈尚未退出时触发界面跳转。使用 main Handler.post 保留这一实际异步边界。失败则回调实际 `ErrorResult`，不产生空成功 ID。单纯设置上传方法返回 String 不会触发原厂后续流程。

## 6. 原生 UI / Intent 测试步骤（未在本次执行）

I2 Manifest `:1735-1759`：Activity `exported=true`，`singleTop`，`VIEW` + `BROWSABLE`/`DEFAULT`，scheme `voiceassist` / host `aiimg` / path `/img2img`。`gc0.k.openImgToImgPage(Context,from,path)` (`:602-622`) 使用同一 URI，额外传 `image_path`。

### A. 正常图库选择，覆盖 Uri→本地文件→裁剪→回调

```sh
adb shell am start -a android.intent.action.VIEW -d 'voiceassist://aiimg/img2img?from=LOCAL_TEST' -p com.miui.voiceassist
```

1. 点击原生上传/选图区域，通过图库选一个已知图案；首次可遇登录/权限，沿原 UI 完成。
2. 验证日志：`C1` 的 Uri/path、`wf0.e.uploadImageFile` 的 callback class、成功后 `saveImageLocalPath` 两次路径（临时和 `files/aiimg_upload`）。
3. 输入明确编辑需求，再点击发送。验证 native query 的 MultiModalState 为 EDIT_IMG、image_info.image_id 与该次 UUID/原厂 ID 一致；自选后端收到真实图片字节及提示词。
4. 比较返回图不是输入图复显；再切换样式/功能、换图重复，验证新的 ID/path 覆盖，不串图。

### B. 通过已有本地路径进入，覆盖初始化 callback `$k`

```sh
adb shell am start -a android.intent.action.VIEW -d 'voiceassist://aiimg/img2img?from=LOCAL_TEST' -p com.miui.voiceassist --es image_path '/sdcard/Download/LOCAL_INPUT.jpg'
```

`image_path` 必须是目标 app 可读、存在的**裸文件绝对路径**。`o1()` (`:3513-3532`) 用 `new File(extra)` 检查，然后 `Uri.parse(extra)`；不是从 URI query 读路径，也不是接收 `content://` extra 自动复制。选用项目已准备、目标 app 可读的样本；若 Android 存储权限阻止该文件，则使用 A 的系统图库流程。

本 Activity 为 singleTop，本类未找到自己的 onNewIntent override；对已位于栈顶 Activity 再发送 Intent 是否刷新初始图，需验证父类行为。可靠的 A/B 独立测试可先从原 UI 退出该页面再进入，不将重复 start 成功误认为图已替换。

### C. 文生图对照和失败处理

1. 不选择图片或清掉图片，发送文生图需求：应为 CREATE_IMG，不能沿用上一张图的 image ID。
2. 普通聊天上传不在当前替代范围：确认仍走原上传路径、原厂成功/失败回调，非图生图内容不被错误接管。
3. 图生图路径传入缺失/空文件：应失败回调、回空图态，不生成 EDIT_IMG 空 ID 请求。
4. 人工/真实上传失败时对照 `$k` 编辑失败提示、`$p` 上传失败提示；替代后同一 callback 错误处理保持可达。

## 7. 剩余未知与任务检查

| 问题 | 状态 |
| --- | --- |
| 原图从哪里来，怎么转 file path | 已静态追踪图库 Uri 复制、可选裁剪与 Web base64 落地 |
| 上传实际共同 Hook、回调和返回值 | 已核对原始 DEX；void + p20.b success/error |
| ID→原图路径机制 | 已静态确认 MMKV key 和持久化覆盖 |
| 文生图 CREATE_IMG / 图生图 EDIT_IMG 结构 | 已静态确认对象构建；JSON 字符串形式尚待真实事件日志确认 |
| ID 是否 opaque String | 已确认上述 native 本地消费链；不推广到未查看的其它上传功能 |
| 本地 UUID 替代原厂上传 | 设计由静态链支撑，尚未在本次设备上执行 |
| 原生入口和 Intent | 已由 Manifest + 消费代码静态确认；本次未触发 |
| 自选后端收到的图、返回结果、取消/历史表现 | 交由后续真机图生图集成测试 |
