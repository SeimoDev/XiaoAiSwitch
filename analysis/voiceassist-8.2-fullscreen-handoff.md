# 小爱 8.2 全屏控制交接

样本为 8.2.70.8421（`508002070`），身份见 [8.2 适配记录](voiceassist-8.2.md)。
这些是 DEX 和 JADX 静态结果，未核验本次用户截图对应的实时 Activity 或失败分支。

- 原厂 Plan B 的 `e.I()/J()` 只在悬浮窗位于前台时调用 `hideFloatView()`；若控制指令已给了
  其他明确应用且配置要求启动，`isNeedUIAgentLaunch` 会用普通应用启动路径让该应用进入前台。
  Plan B 的 `onExecuteEnded` 清理监听和遮罩，未找到自动恢复原全屏任务的步骤。
- 原厂 `Application.Operate` 的 `BACK_GROUND` 分支（`zg1.h2.r0 → b0`）在主线程区分
  全屏和悬浮：全屏用 `n0.getFloatManagerBridge(): w5` 的 `moveMainActivityToBack(): void`，
  悬浮走自己的状态迁移。整个 Operation 还会退出连续对话和结束文字流，不能当成模块的单纯退场调用。
- `a4.moveMainActivityToBack` 的具体动作只从 `UiManager` 取得原本的
  `TabHostMainActivity`，然后调用该 Activity 的
  [`moveTaskToBack(true)`](https://developer.android.com/reference/android/app/Activity#moveTaskToBack(boolean))。
  它不结束 Activity，也不指定下一次前台是桌面还是之前的应用。另一方法
  `UiManager.isMoveTaskToBack(): boolean` 虽名似查询，却还会切窗口状态、设置模拟退场标记，
  不应当作为探测或替换。
- DEX 核对全屏对象的访问链：`UiManager.getFullScreenManager(): com.xiaomi.voiceassistant.mainui.i1`
  → `getTabHostManager(): com.xiaomi.voiceassistant.yd` → `getTabHostMainActivity(): TabHostMainActivity`。
  `getTabHostMainActivityContext()` 在某些状态会回落到 Application Context。只按包名或 OEM
  “全屏”状态不足以确认对象，设置页面或其他 Activity 处于顶层时不移动其任务。
- `LaunchHomeRouterActivity.N()` 在用户重新打开时可在本应用的 `getAppTasks()` 里找历史任务，
  用 [`AppTask.moveToFront()`](https://developer.android.com/reference/android/app/ActivityManager.AppTask#moveToFront())
  恢复前台。它不是 Plan B 配对清理的恢复代码。模块如需显示结果，不应自动拉任意任务或绕过
  Android 后台启动限制；若恢复原页面，至少需确认仍是同一被移开的自身任务且不抢用户的新焦点。

可在自有测试页覆盖一轮“全屏聊天提交”与一轮“浮窗提交”，先记录进入请求时实际顶层
`ComponentName` 和 taskId。只允许对当时真正获得焦点且仍有效的 `TabHostMainActivity`
走本包退场；返回成功后等真实默认屏前台再检查内容和输入权限、保持严格目标 UID。
`TabHostMainActivity.onStop` 按下一前台条件可能清离原厂交互，落到桌面或屏幕熄灭时尤其如此；
若当前任务取消或不能确认正确前台，停止并提示，而不是屏蔽生命周期。还应独立核验模型传输：
生产代码在小爱包仍为截图目标时有明确的本地拒绝，但通用工具错误本身不足以锁定本分支。
## 模块实机对照

测试页真实有输入焦点后启动原厂全屏小爱。修复前，显式控制在首次模型请求前触发
“系统窗口不接受自定义控制”。修复后，只在精确的原厂主界面 Activity 确实持有焦点时，
按 OEM 同样的 `moveTaskToBack(true)` 退场，再通过原厂屏幕授权与 UID 定向派发。
同机实际点中 1 次，输入测试字符串，滚动 2240 像素。没有把历史里涉及外部应用的评论或发帖请求重放。
