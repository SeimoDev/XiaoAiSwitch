# 普通图片问答：7.11 与 8.2 的真实输入链及修复

## 范围与证据

- 7.11.20.3514 / 507011020：`artifacts/com.miui.voiceassist/VoiceAssistAndroidT.apk`，SHA-256 `ae216062a595e58b0ef6fe7969de2696e00e427d5fd9c2c5d2e4ac07acb18e06`。
- 8.2.70.8421 / 508002070：`artifacts/voiceassist-8.2.70/voiceassist.apk`，SHA-256 `ff1752a68b94eaa1654fa85737161c4d332b9bcaf3df8df606cd8138d2a71e4b`。主调查确认该摘要与当前设备活动 APK 一致；本分工另重算本地摘要一致。
- 源码根：`analysis/decompiled/full/sources`（7.11）、`analysis/decompiled/8.2.70/sources`（8.2）。以下相对路径分别位于对应根。
- 原始 DEX 符号证据：`analysis/vision-native-dex.json`，直接解析两份 APK 的 DEX class_data、method/proto/field 表，包含真正类名、方法签名、访问标志与 code_offset。字段/类名没有使用 JADX 的 fNN 别名。
- REA Android provider 缺少所需 JAR 的已知阻塞未重复配置；使用既有 JADX 输出和原始 DEX 验证。没有将静态推导写成设备运行结果。
- 本分工未调用 adb、未读设备图库、未读用户凭据、未上传 APK。代码仅修改 `ImageBridge.java`；主调查独立处理 vision 配置和事件路由。

## 1. 观察：此前图片报错的四个具体实现缺口

| 缺口 | 真实生产证据 | 此次修复 |
| --- | --- | --- |
| 未识别真正图片问答事件 | 两版普通上传均发送 `MultiModal.ImageUnderstand`，而桥接只识别 `ImageGeneration` | `inspect` 增加 ImageUnderstand |
| Optional / List 类型取错 | ImageUnderstand.image_ids 是必填裸 `List<String>`，query 是 Optional；ImageGeneration.image_ids 才是 Optional<List> | 按事件类型读取 image_ids；query 同时支持 required String 与 Optional |
| 第一条 context 早退 | ImageUnderstand 可含一个仅 upload_request_id 的 MultiModalState；另一条才含附件/生成状态 | 遍历所有 MultiModalState、合并去重 ID，不因首条无 ID 而丢附件 |
| 普通图片映射读错缓存 | 普通图片用独立 MMKV `image_cache_index`；绘图用 default MMKV | hook 普通上传的真实 chain 保存原图 ID→path；另读原厂 getImageCacheIndex fallback |

原错误并不证明模型不支持图片：在调用自选模型前，事件和本地字节已经可能被漏掉或因 query 强制转换抛异常。

## 2. 观察：普通附件/拍照的原生链

| 角色 | 7.11 | 8.2 |
| --- | --- | --- |
| UI 上传管理器 | `zj0.q1` | `dp1.i3` |
| 普通图片上传器 | `zj0.m1` | `dp1.d3` |
| chain | `wj0.j` | `ap1.k` |
| inputInfo / imageInputData | `wj0.p` / `wj0.n` | `ap1.q` / `ap1.o` |
| outputInfo / imageOutputData | `wj0.q` / `wj0.o` | `ap1.r` / `ap1.p` |
| 文件 ID 实体 | `com.miui.voiceassist.data.entities.MultimodalMediaEntity` | 同名 |
| 真正生成图片问答事件的方法 | `zj0.m1$a.sendImageUnderStandEvent` | `dp1.d3$a.sendImageUnderStandEvent` |
| 普通文件缓存 helper | `m20.z0$d0` | `com.xiaomi.voiceassist.baselibrary.utils.i1$f0` |

原生链的核心数据关联：

```text
图库/拍照 Uri
  → 原厂复制/压缩/上传 processor chain
  → chain.getMultiModalInputInfo().getInputData().getFilePath()  [本地输入]
  → chain.getMultiModalOutputInfo().getOutputData()
        .getMultimodalMediaEntity().getFileId()                [同图云 ID]
  → uploader.setMultiModalChain(chain)
  → 用户图文问答 / 发送
  → companion.sendImageUnderStandEvent(chain,query,...)
  → payload.image_ids = [fileId]
```

两版 `onProcessFinish`、`onProcessChainFinish` 都调用 `setMultiModalChain`，输出上传完成后可从同一 chain 读取 ID 和本地路径。原图路径不是 `MultimodalMediaEntity.filename`，也不是从云 ID 拼出来的 URL。`getAndClearImagePath()` 是有副作用的 UI 传递字段，此次修复未调用它。

源码：7.11 `zj0/m1.java:638-670, 1306-1326, 1350-1352, 1652-1654`；`zj0/q1.java:416-423, 468-481`；8.2 `dp1/d3.java:518-550, 1307-1311, 1336-1338, 1895-1897`；`dp1/i3.java:421-426, 474-484`。

### 拍照不是图生图

`TakePhotoActivity` 最终调用 MultiModalBridge 的 `uploadImageFile(Uri,boolean,boolean,from)`：7.11 `com/xiaomi/voiceassistant/lookout/TakePhotoActivity.java:1098-1104` → `vj0.t:514-517`；8.2 对应 `:2141-2147` → `zo1.a0:541-544`。这是普通图片上传，再以用户 query 理解图片，不应套用 `EDIT_IMG` 绘图路由。

## 3. 观察：真实事件与上下文

### 用户主动发送图片问答

7.11 `zj0/m1.java:210-296`；8.2 `dp1/d3.java:232-319`：

```json
{
  "header": {"namespace": "MultiModal", "name": "ImageUnderstand", "id": "REQUEST_ID"},
  "payload": {
    "image_ids": ["FILE_ID"],
    "query": "用户的图片问题"
  },
  "context": [
    {
      "header": {"namespace": "MultiModal", "name": "MultiModalState"},
      "payload": {"image_info": {"upload_request_id": "UPLOAD_REQUEST_ID"}}
    },
    {
      "header": {"namespace": "Application", "name": "State"},
      "payload": {"app_state": {"upload_mode": "MANUAL", "upload_sub_mode": "UNKNOWN"}}
    }
  ]
}
```

该形状只列相关字段，不表示所有 context 固定存在。General.RenewSession、UIController.ExternalState、Execution.RequestControl、Sys.EventRoute 等由原厂分支追加。用户 query 非空时才设置 payload.query；raw dialog ID 非空的推送/OCR 分支走 Sys.EventRoute，不保证有 query。

真正的 file ID 在 **payload.image_ids**。附带的 MultiModalState 可能只设置 upload_request_id，不能要求它再重复 image_id。

### 普通 Nlp/语音后续问题携带图片上下文

图片上传器的 `getMultiModalContextFileId` 以原厂当前会话、上传成功状态和 queryOrigin 决定是否返回 ID。状态管理器发现该值后先构造：

```json
{
  "header": {"namespace": "MultiModal", "name": "MultiModalState"},
  "payload": {
    "image_info": {
      "image_id": "FILE_ID",
      "image_type": "OTHER",
      "image_source": "SCREEN_RECOGNITION_PHOTO"
    }
  }
}
```

这是原厂普通附件上下文，没有 CREATE_IMG / EDIT_IMG。同一 Nlp.Request 或 SpeechRecognizer.Recognize 可带它。源码：7.11 `i30/k0.java:94-125`、`com/xiaomi/voiceassist/engine/context/b.java:961-970`；8.2 `com/xiaomi/voiceassist/engine/context/p0.java:100-142`、`j0.java:1070-1079`。

### 原始 DEX 类型，不混淆两代 Optional

```text
7.11 MultiModal$ImageUnderstand.getImageIds() : java.util.List
7.11 MultiModal$ImageUnderstand.getQuery()    : sx.a
8.2  MultiModal$ImageUnderstand.getImageIds() : java.util.List
8.2  MultiModal$ImageUnderstand.getQuery()    : com.xiaomi.common.Optional
```

两版 Optional 均支持 `isPresent()` / `get()`；桥接通过方法调用解包，不绑定某一 Optional 类。`ImageGeneration.getImageIds` 的 Optional 形状保持原有兼容。

## 4. 观察：原厂普通附件的持久缓存与原图优先级

7.11：`zj0.m1` 上传成功后 `sd0.e4$a.saveImageCacheIndex(id,picture)` → `m20.z0$d0.saveImageCacheIndex`。

8.2：`dp1.d3` 成功后 `zg1.a5$a.saveImageCacheIndex` → `i1$f0.saveImageCacheIndex`，重新显示/恢复时会用 `getImageCacheIndex` 找文件（`dp1/d3.java:1092-1098`）。

helper 行为：

```text
MMKV.mmkvWithID("image_cache_index", context.cacheDir + "/multimodal")
  key=id        → filePath
  key=id+"_time" → 保存时间
```

原厂保留/清理时间常量为 604800000 ms；不会把这些条目放进绘图模块的 default MMKV。

源码：7.11 `m20/z0.java:236-351`、`sd0/e4.java:42-51, 114-124`、`zj0/m1.java:1752-1765`；8.2 `com/xiaomi/voiceassist/baselibrary/utils/i1.java:794-909`、`zg1/a5.java:46-55, 115-125`、`dp1/d3.java:1143-1156`。

此次 `ImageBridge.load` 的选择顺序：

1. 同一完成 chain 捕获的 imageInputData.filePath（真正输入原图）；同 ID 的后续保存可更新。
2. 原有绘图 default preferences ID→path（不影响已有图生图）。
3. 普通附件的 `getImageCacheIndex(id)`（支持此前生成、历史恢复、模块内存映射不存在的情况）。

缓存 `picture` 可是处理后的缓存图，未把它宣称为与图库原文件逐字节一致。模块只按当前 event 指定的 ID 读取文件，没有枚举设备图库。

## 5. 本次实现、测试与状态

修改：`app/src/main/java/io/github/xiaoaiswitch/ImageBridge.java`。

- 保留生产绘图 `saveImageLocalPath` hook。
- 按版本 producer 分支安装 `zj0.m1.setMultiModalChain(wj0.j)` / `dp1.d3.setMultiModalChain(ap1.k)` after-hook；未改 AssistantVersion record，避免与其它版本工作冲突。
- 合并所有真实 MultiModalState；去重并忽略 null/空 image_ids。原厂 singletonList(null) 的构造分支确实存在，空 ID 不是可加载附件。
- 普通问答保持 `drawing=false`；只有 EDIT_IMG/CREATE_IMG 或 ImageGeneration 判为绘图。
- `query()` 支持必填 String 及两版 Optional，不再把 ImageUnderstand Optional 强转 String。
- 加原厂独立图片缓存 fallback。

TDD host fixtures：`analysis/vision-bridge-tests/`。它编译并运行**生产 ImageBridge.java**，仅用反射调用适配器替代 Android/Xposed host 边界；fixture event/chain 公开 getter 与实际 DEX 类型形状一致，没有在测试中重写 inspect/load 算法。

先写 10 用例：原实现 **6 失败**（事件漏识别、query cast、context 早退、两版 chain hook 缺失）；修复后 10 通过。再加两版既有缓存恢复用例：先 **2 失败**，加真实 helper fallback 后全部 **12 通过**。

从 `app` 目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ../analysis/vision-bridge-tests/run.ps1
```

输出：`cases=12 failures=0`。host 测试只创建自己的一像素 PNG 文件并清理，不联网、不读取私人图片。它不是完整 Android/Xposed E2E，也没有声称项目整体达到某个覆盖率；后续 nonce 真机验证由主调查执行。

另用本机 Android 36 `android.jar`、正式 Xposed `api-82.jar` 和本项目 `core.jar` 单独编译实际 `ImageBridge.java` / `AssistantVersion.java`，通过；没有只凭测试适配器判定生产 API 签名可编译。

## 6. 8.2 的纯自造图片原生链验证步骤

以下是供主调查 debug fixture 使用的**实际生产类型/方法**，不是替代事件 schema。

1. 生成小尺寸 Bitmap，并写到应用自己的 fixture 临时 PNG。设置 `FILE_ID="fixture-"+nonce`。
2. 用目标 ClassLoader 构造：

```text
inputData = new ap1.o(PATH, bitmap, fileByteCount, 100)
inputInfo = new ap1.q(inputData)
media = new com.miui.voiceassist.data.entities.MultimodalMediaEntity(FILE_ID, null)
outputData = new ap1.p(PATH, PATH, null, media, "")
outputInfo = new ap1.r(outputData)
state = new ap1.l(0, "")
chain = new ap1.k(inputInfo, outputInfo, state, null)
```

原始构造签名：

```text
ap1.o(String,android.graphics.Bitmap,int,int)
ap1.q(ap1.t)
MultimodalMediaEntity(String,String)
ap1.p(String,String,String,MultimodalMediaEntity,String)
ap1.r(ap1.u)
ap1.l(int,String)
ap1.k(ap1.q,ap1.r,ap1.l,String)
```

3. 在主线程取得 `UiManager.getInstance(context).getFloatManager()`。可构造一个新 `dp1.d3(floatManager)`；其构造仅设置 INIT、lazy UI、main Handler、flags，没有立刻上传或替换现有 manager。调用这个临时 uploader 的实际 `setMultiModalChain(chain)`，触发模块普通原图映射 hook。
4. 取得 `dp1.d3` 的**原始静态字段 `I`**（类型 dp1.d3$a）。在 companion 上调用：

```text
sendImageUnderStandEvent(
    chain,
    "描述这张自造图片的主色",
    null,
    Application.UploadMode.MANUAL,
    false,
    Application.UploadSubMode.UNKNOWN
) : String
```

注意 `UploadSubMode` 没有 DEFAULT，原厂默认就是 UNKNOWN。rawDialogId=null 不触发 OCR/截图分支。构造代码可以用 XposedHelpers.newInstance / callMethod，避免编译时依赖 OEM 类。

5. 该实际 companion 会构造 ImageUnderstand 并经 `com.xiaomi.voiceassistant.m2.sendEvent` 发到原生引擎；返回真实 event ID。模块应只在 nonce 限定 debug fixture 中接管，后端实际收到该 PNG 字节，回答应与自造图色块一致。不要仅发送人工 Nlp.Request 或直接调用模块 load，然后将其称作原厂链通过。
6. 验证时不需要读取私人图库，也不需要原厂云上传成功；本地 synthetic ID 只用于此已被模块接管的 fixture 事件。临时 fixture uploader 不替换现有用户 uploader；不要将该伪造 cloud ID 发到原厂云服务。

原始 DEX 已核对该方法完整描述符：

```text
Ldp1/d3$a;->sendImageUnderStandEvent(
  Lap1/k;Ljava/lang/String;Ljava/lang/String;
  Lcom/xiaomi/ai/api/Application$UploadMode;Z
  Lcom/xiaomi/ai/api/Application$UploadSubMode;
)Ljava/lang/String;
```

7.11 对应 companion 原始静态字段 `zj0.m1.E`、chain `wj0.j`，同方法参数结构和语义。

## 7. 已分开的未知边界

- 普通附件与拍照附件的**静态链、文件关联、缓存和真实 method descriptor 已确认**；host 修复回归已通过。
- 自动 OCR/屏幕/UIAgent 还可从内存 Bitmap 发 ImageUnderstand：7.11 `uq0.o.sendCommonEvent(ie0.b0,Integer)`，8.2 `mw1.o.sendCommonEvent(wh1.b0,Integer)`；通常 query 为空，不保证走普通附件 chain。
- 两版自动屏幕事件都设置 `check_access_result_code`，关联到原有请求的 OCR 带 `Sys.EventRoute`。两类事件保持原厂处理，不作为用户主动配置的附图问答。
- 原厂 `AIImageUploader` 另有 HTTP 直传路线，可把 base64 放入 `ImageUnderstand.image_contents`；7.11 `AIImageUploader.java:314-340` / `uq0.b`，8.2 同类 `:327-353` / `mw1.c`。HTTP helper 有可能绕过当前 engine outbound 拦截。此次未把这些自动工具调用擅自纳入普通问答附件修复，也未捕获用户屏幕 Bitmap。
- 真正需要覆盖这些自动/推送/HTTP 分支时，应先在用户要求的特定原生入口取得运行证据，再明确事件归属；不得仅靠 schema 存在就称作全部相机/屏幕链已经集成。

## 8. 测试

- 14 个本地桥接用例，覆盖两版附件关联、缓存读取、原生必填图片 ID、可选 query、保留原厂屏幕事件和图生图回归。构建 `:core:jar` 后可运行 `analysis/vision-bridge-tests/run.ps1`（需要 `ANDROID_HOME` 或 `-AndroidSdk`）。
- 8.2 真机用调试入口调用实际原生生产器，不读取私人相册。确认合成 PNG 的 SHA-256 不变，指定的识图模型和 System Prompt 生效，请求没有绘图／控制工具，回答回填至小爱浮窗。真机脚本为 `scripts/native_vision_test.py`，依赖本地 `scripts/fixture_provider.py` 调试服务。
- 7.11 本轮验证了原生符号和本地测试样本，未在旧设备上重新跑端到端。
