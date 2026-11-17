# 小爱 8.2.70.8421 适配

静态样本：`com.miui.voiceassist`，versionCode `508002070`；APK SHA-256
`ff1752a68b94eaa1654fa85737161c4d332b9bcaf3df8df606cd8138d2a71e4b`。
这是设备当前安装版本，不是系统分区的 `8.2.3.1619`。APK 与反编译产物仅保存在本地。

| 链路 | 7.11.20.3514 | 8.2.70.8421 |
| --- | --- | --- |
| Event 下发、Instruction 接收与派发 | `b30.o0.u0/y0/W` | `p31.o0.D0/H0/b0`（`classes10.dex`） |
| 原生引擎消息线程 | `b30.o0.y` | `p31.o0.y`，类型仍为 `Handler` |
| 清空会话 | `b30.g.clearSession` | `p31.g.clearSession` |
| 原厂 TTS | `b30.z0.a.speakTts`，回调 `s10.b` | `p31.e1.a.speakTts`，回调 `g21.a` |
| 思考模式 | `engine.context.b.generateDialogStateContext` | `engine.context.j0.generateDialogStateContext` |
| 附件路径保存、读取 | `vf0.a` / `m20.z0` | `sj1.b.saveImageLocalPath` / `baselibrary.utils.i1.getString` |
| 绘图预览 | `fc0.l1$b.g/init` | `jf1.n1$b.g/init`，Glide 入口为 `je1.a.with` |
| Markdown | `sd0.od.g0/h0`、`card.i`、`nj0.c` | `zg1.jf.i0/k0`、`card.k`、`wn1.d`；流式 Toast 卡片类不变 |

用本地 DEX 成员表核对真实签名与字段名，并在 JADX 1.5.6 输出中对照具体调用：
`p31/o0.java` 的 `D0` 调用 SDK `postEvent`，`H0` 调用本地派发 `b0`；
`engine/context/j0.java` 的 `isUseDeepThink` 仍包含原生“思考重答”和“直接回答”覆盖。
JADX 存在未成功反编译的其他方法；这些静态依据不代替真机验证。

先按版本号选择已知映射，注册前核验一条 `boolean(Event)`、两条
`void(Instruction)` 及实例 `Handler` 字段。新版本若匹配已知结构，只在状态中给出
诊断，不启用接管或猜测混淆名称。图片、预览与 Markdown Hook 独立安装；单项变化不阻断
对话 Hook。8.2 不注册自定义控制模型工具，文本显式控制路由交给原厂；7.11 的原有行为不变。

8.2 原厂分别检查屏幕内容与操控权限。原厂截图所用的执行实例由其会话管理，且截图前
检查屏幕内容授权；截图或输入流程还需要区分授权、拒绝、待确认与设备不支持，不能直接
把同步返回值中合并的“已授权或不适用”当作实际授权。原厂光效资源仍存在，窗口类型工具类改为
`com.xiaomi.voiceassistant.utils.ba`。这些变化需要独立的屏幕授权与停止流程核验。

本地签名回归测试：

```powershell
cd scripts
python -m unittest test_assistant_version
```
