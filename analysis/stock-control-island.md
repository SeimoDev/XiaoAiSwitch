# 原厂控制胶囊 / 上岛与手动停止：版本对照

本轮仅做既有 APK / JADX / DEX 的静态核验，没有操作设备，也没有把未验证胶囊代码接进稳定控制链。

## 7.11.20.3514

- `d20.g` 是 `UIAgentAliveIslandUtils`，上岛入口 `onBackground()` 检查 `c20.b.canUsePLanA()` 和原厂任务状态；完成 / 停止入口还调用 `moveTask()`。
- `d20.t` 是原厂 MIUI Focus 通知构造工具，`supportIsLand` 读取 `persist.sys.feature.island`，`hasFocusPermission` 查询 SystemUI 的 `canShowFocus`。通知使用固定 ID 12345，并创建共享 PendingIntent。
- 真正的手动停止入口是 `i10.a.onStopClick()` → `y00.i.onClientStop(...)`，不是独立于会话的 UI 回调。
- `ScreenMaskWindowImpl.p()/z()` 创建原生 `SmartCapsuleView` 与 `EXIT_CUSTOM_CALLBACK` 参数，但该 callback 在胶囊收起动画结束时触发。
- `SmartCapsuleView.setCapsuleClickListener` 处理的是可选跳转链接；`goHideCapsule()` 是退出动画。二者没有构成“原厂停止当前自定义任务”的独立接口。给原生 View 另绑 stop 点击也不等于复用原厂手动停止逻辑。

因此，稳定的 7.11 自定义控制保留已验证的任务专属浮动停止按钮；没有启动原厂虚拟任务容器或复用其全局停止入口来伪装完成。

## 8.2.70

- 上岛组件为 `com.xiaomi.voiceassist.baselibrary.uiagent.common.a`，原生通知工具为同包 `b`。
- `q21.g` 初始化原厂执行器时将 `common.a` 注册为 `dynamicIslandWindow`；它的上岛状态入口检查 `isVirtualUIAgentExecuting()`。
- 原厂停止动作由 `UIAgentJumpService` 接收 `com.miui.voiceassist.UIAGENT.JUMP`，读取 `intent_action=stop_uiagent`，再调用原厂 `stopUIAgentIfNeeded(...)`。
- `common.b.showNotificationForPlanA` 会清理固定通知 ID 12345、修改全局缓存，并生成 requestCode 1001 / 1002 / 1003 的共享 PendingIntent。直接复用这些入口会耦合或覆盖原厂任务状态。
- 已在原 DEX 核验 raw 类型 `common.b$b`（通知样式）、`common.b$c`（执行状态），singleton 字段 `a`，以及纯参数 / JSON / 图标构造方法。

本次静态调查时 8.2 仍由主任务保留原厂回退，未提交试验通知类。后续 0.10.6 已验证 [自定义动作与停止按钮](voiceassist-8.2.md#浮窗交接后的实机结果)；没有把它接到原厂全局上岛或停止入口。原厂自己的任务继续由原版小爱负责上岛与停止。

## 后续候选边界

具备对应硬件的设备可进一步验证“原厂 Focus JSON / 图标纯构造器 + 独立通知 tag/id + 当前任务私有 PendingIntent”的组合，避免触碰原厂固定通知和控制会话；通知投递成功仍不等于 SystemUI 已展示岛，须以实机截图和实际停止回调验证。

本轮未把原生顶层 SmartCapsuleView、真正 SystemUI Alive Island 和固定浮动停止按钮混为同一能力，也未隐藏当前可用的停止回退。

## 0.10.11 独立控制通知

8.2 路径在实际小爱进程内，使用当前包的通知权限、Focus 协议版本以及原厂
`supportIsLand` / `hasFocusPermission` 检查。卡片使用 [小米公开超级岛通知格式](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)：
摘要态只显示「控制中」，不自动展开；展开态 `param_v2.actions` 的 `type:2` 显示
「停止控制」，链接到 `miui.focus.actions` 中同名的平台 Action。

模块只发布自己的通知：独立频道、每次任务独立 tag、不可变单次 PendingIntent 和非导出
当前任务接收器。不调用 OEM 全局上岛、通知清理或停止任务入口。取消、完成和失败时清除
本任务通知及动作。普通通知仍提供停止动作；连通知都发不出时保留原来的停止悬浮窗。

真机回归脚本：`python scripts/device_fixture_test.py --registry --gesture --island-stop --serial SERIAL --route control --query CONTROL_ISLAND_CANCEL`。
与已启动的本机 `scripts/fixture_provider.py` 配合，等测试任务暂停在唯一的模型请求时，
实际展开超级岛并点右侧停止。断言没有应用上的停止悬浮窗、取消状态成立、通知移除且
即使服务端延后给出下一步也不会再有模型调用。脚本不读取或改写原厂的任务会话。

已在 8.2 真机验证：顶部摘要显示「控制中」；展开显示「停止控制」。点击后状态变为
「已停止自定义手机控制」，系统随后撤销本次通知，测试服务没有收到第二次请求。
