# 原厂手机控制全局光效复用

## 样本与证据类型

- 目标：`com.miui.voiceassist` 7.11.20.3514 / 507011020，APK SHA-256 `ae216062a595e58b0ef6fe7969de2696e00e427d5fd9c2c5d2e4ac07acb18e06`。
- 本轮复用既有 JADX 导出 `analysis/decompiled/full/sources`，没有重复全量反编译，也没有把普通 JADX 结果描述成新的 REA 方法 Evidence。
- 下列静态映射与运行观察分别列出；设备观察对应 Xiaomi Pad 5 / Android 16。

## 原始实现定位

1. `j20.c` 的原名为 `UIAgent_PagViewHelper`。其 `initMaskAnimView(ViewGroup, boolean)` 创建真正的 `org.libpag.PAGImageView`。全局模式 `false` 加载 `assets://PAG/uiagent_progressing.pag`，设置 repeatCount=-1、scaleMode=1，再 `play()`；`release()` 移除该 View。见 `j20/c.java:201-245`。
2. 消费者为 `com.xiaomi.voiceassist.baselibrary.uiagent.plana.ui.ScreenMaskWindowImpl.b()`，见 `:655-700`。高层窗口还管理 UIAgent 状态、胶囊和原始触摸拦截，因此没有直接启动整个高层控制器。
3. `i10.c$d` 是原厂 PhoneModel；其非触摸 flags 为 `0x01010798`，并使用 `miuiFlags |= 32 | 64` 与 `LayoutParams.setTrustedOverlay()`。原厂 `k8.getWindowType()` 在本机返回 type=2017。见 `i10/c.java:197-288`、`com/xiaomi/voiceassistant/utils/k8.java:549`。
4. 原厂截图方法 `d20.w.takeScreenshotAndExcludeSelf(...)` 通过 `setExcludeOrIncludeLayerNames` 排除 `UIAgentScreenMask`；该名字来自 `y00.i.getScreenMaskTitle()` → `i10.e` 常量。见 `d20/w.java:319-327`、`y00/i.java:760`。
5. 原 APK 确实包含 `assets/PAG/uiagent_progressing.pag`，548204 bytes，SHA-256 `f2ff34c7b88de65178cabdd1a41e3ffb5a31c648b6e0ac3e620d9d957278c878`。

## 模块接入

- `StockControlEffect` 在目标进程通过其 classloader 创建原厂 PAGImageView，使用目标 Context 加载上述原始素材；没有复制素材进模块，也没有重画一套渐变。
- 只持有本次控制任务的 View，不占用 `j20.c` 的原厂全局单例，也不重放 Application.UIAgent 指令。
- 使用原厂允许的系统面板类型、非触摸 flags、MIUI flags 与 trusted-overlay 属性，保留 `UIAgentScreenMask` 图层角色以沿用原厂截图排除。没有修改系统权限、数据库或原厂门控。
- `PhoneControl.begin()` 启动光效；`hideWindows()` 跳过本次光效 View，避免把它当小爱自身浮窗隐藏；现有停止按钮保持可点击。
- `PhoneControl.close()` 先暂停 PAG 并同步移除窗口，再在 finally 中恢复原窗口与清理停止按钮。正常、异常、停止均走现有控制 finally。视觉启动异常只回报其异常类型，不替换或跳过原生输入权限检查。

## 真机结果

### 显示与窗口

`evidence/stock-effect-inflight.png` 与 `stock-effect-gesture-inflight.png` 实际显示蓝 / 黄 / 粉色原厂全屏边缘光效。后者同时显示真实 `Count: 1`，证明不是只在输入前展示样式。

`evidence/stock-effect-windows.txt` 中，光效层实际为 `UIAgentScreenMask`，owner 为小爱 UID，type=2017，flags 含 NOT_TOUCHABLE / NOT_FOCUSABLE，private flags 含 TRUSTED_OVERLAY。未使用 alpha=1 的普通 application overlay 遮住底层应用。

### 交互、截图与收尾

- 真实交互 fixture 通过：count=1、中文 `控制测试XASWITCH`、scroll>0（本轮观测 1841）。断言来自 View 监听器写入值，不把 inject=true 当成动作成功证据。
- 在光效持续显示期间，模型收到的实际 JPEG 边缘为纯背景，三个边缘采样列的最大 RGB 通道差为 0；物理屏幕截图则有明显彩色边框。证明该层被原厂截图路径排除。记录为 `evidence/stock-effect-screenshot-proof.json` 与对应 control-screen JPEG。
- 正常完成后，原厂光效与停止窗口均不存在。
- 网络响应延迟期间点击停止，通过等待超过延迟的无后续动作检查；光效与停止窗口均移除。
- 模型调用上限为 1 时，实际触发 StepLimit 异常并显示原生错误卡片，两个控制窗口均移除。该项是本轮失败收尾的具体测试，不将其描述为全部错误类型的穷举验证。

测试入口：从 `app` 目录运行 `scripts/stock_control_effect_test.py --serial SERIAL --records RECORDS [--gesture]`，依赖已运行的本地合成服务。该包装测试只做窗口 / 截图观测，实际动作仍由生产组件与既有 fixture 脚本完成。

对既有 fixture 进行了两项运行修正：启动原生 FloatActivity 使用 `am start -W`，避免冷启动时请求先于页面初始化；停止前不再等待持续 PAG 动画下的 UIAutomator idle，仅保存 screencap 与窗口证据后立即点击停止，不放宽停止断言。

所有测试恢复普通配置、移除 ADB reverse，并保留手机订阅账号。最终全套构建与其他 UI 回归由主任务执行。
