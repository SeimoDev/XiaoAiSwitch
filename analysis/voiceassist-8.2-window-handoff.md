# 小爱 8.2 浮窗交接

以下为安装包 8.2.70.8421（`508002070`）的本地 DEX 与 JADX 静态核对。
样本身份及既有输入失败见 [8.2 适配记录](voiceassist-8.2.md)。

| 入口 | DEX 签名与用途 |
| --- | --- |
| 原厂 Plan B 退浮窗 | `d21.c.getAppBackBridge(): com.xiaomi.voiceassistant.s5`；`s5.hideFloatView(): void`。`com.xiaomi.voiceassistant.s` 仅在浮窗状态可见时经 FloatManager 桥切换状态。没有带回调或对应恢复的同名重载。 |
| 成对临时让出界面 | `wx1.c.a.getPrivacyBridge(): com.xiaomi.voiceassistant.a6` 提供 `hideFloatBoardTemporarily(): void` 和 `showFloatBoardTemporarily(): void`（实现为 `ca`）。`wx1.d` 在原厂的交互界面出现前暂时隐藏浮窗，完成或异常后恢复。 |
| 直接检查浮窗 | `UiManager.getInstance(Context): UiManager` → `getFloatManager(): com.xiaomi.voiceassistant.widget.s1`；`s1.getRootLayout(): mainui.board.FloatViewRootLayout`。另外 `UiManager.isFloatScreenInForeground(): boolean` 和 `isFloatScreenInVisible(): boolean` 是 OEM 逻辑观察，不能代替实际的输入窗信号。 |

临时隐藏和恢复分别等价于主线程调用同一 `s1` 的
`setRootViewVisible(false, false)` 和 `setRootViewVisible(true, false)`。第二个参数关闭
可见性动画；`s1.O2` 更新根 View 的 `GONE/VISIBLE`，还通过原厂接口同步浮窗可见状态给
手势/桌面组件。不单独写入底层状态，不调用原厂全局退出、停止或创建原厂控制会话。

`a6.waitForWakeAnimationComplete(Runnable)` 等待的是**唤醒动画**；`ca` 会设置并释放
原厂的唤醒监听器。它不是临时隐藏的完成通知，也不证明系统输入已交给测试页。

## 权限与最小验证

新版原厂权限查询是 `com.xiaomi.voiceassistant.utils.o6` 的实例方法
`isSupport(Context)`、`hasAccessScreenContentPermission(Context, ws1.b$f, List, boolean, boolean, u62.g)`
及 `hasAccessScreenControlPermission(Context, List, boolean, boolean, u62.g)`。内容检查用
原厂可见窗口范围，两项都必须同步确认实际授权；普通异步回调肯定值可能仅表示总开关已开启。
不把“不适用”或异步输入排队当成授权与交付，不因窗口不可见而忽略现有保护。

1. 用自有测试 Activity，记录原浮窗根 View、窗口可见及测试页焦点。仅在本次请求拥有该浮窗
   且原本可见时，用成对入口临时隐藏。
2. 在动作前分别观察根 View 可见性、默认显示焦点与测试页边界；状态变化用可观测的窗口
   事件核对，而不是把方法返回、虚化或固定延时当作交接完成。若仍由系统窗口接收，结束测试，
   不改目标 UID。
3. 屏幕捕获前保留屏幕内容和操控检查；实际动作使用已有的目标 UID 校验，以测试页收到
   手势及实际状态变化为准。完成/拒绝/取消均只恢复本次隐藏且仍由本次请求拥有的同一浮窗；
   用户已退出或开始其他交互则不重新弹出。
