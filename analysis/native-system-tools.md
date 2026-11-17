# 8.2 原厂系统工具链路

样本为已安装的小爱 `com.miui.voiceassist` 8.2.70.8421（`508002070`），身份见
[8.2 适配记录](voiceassist-8.2.md)。以下为本地 APK、JADX 和 Manifest 的静态证据，
这一部分没有触发设备动作；实际模块验证见下文。

## 原厂 AIVS 路径

普通云端 `Instruction` 由 `p31.o0.H0` 接收，经过 `b0` 交给 `g41.g.handle` 的本地
处理链。SDK 入口 `AIApiNameMapping` 将指令名映射成 typed payload；调用闹钟或打开应用
属于对应的业务 Operation，不是 `Template.Toast` 的展示行为，也不需要启动屏幕操控会话。
`BaseOperation` 有独立的处理 Looper，分支需要更新界面时会转主线程；外部界面由 Android
解析和启动，不是在小爱悬浮 Activity 内填写控件。

| 用户操作 | 原厂指令与本地业务代码 | 对外部应用的影响 |
| --- | --- | --- |
| 新建单个闹钟 | `Alerts.SetAlert`，`Alerts.AlertType.ALARM`；`zg1.p9.onProcessOp → y0 → g0`；时间由 `instruction/model/a.parseAlarmData` 解析。另有 `SetAlerts` / `zg1.r9` 的批量分支，不纳入首批。 | 原厂有 MIUI 闹钟 Provider 权限时使用 Provider；否则向时钟 Activity 发送 Android `SET_ALARM`。这可能持久创建闹钟。 |
| 启动倒计时 | 同样为 `Alerts.SetAlert`，但 `type=TIMER`；`zg1.p9.y0` 使用偏移量或目标时间减当前时间，过滤过期请求。 | `utils.x8.addTimerCountDown` 写入原厂 Clock ContentProvider，使用毫秒；原代码还核验计时器是否在运行。公开 Android Intent 使用秒，二者不可混用。 |
| 打开已安装的 App | `Launcher.LaunchApp`；`wh1.j0.parseLaunchAppData` 解包，`zg1.d6.onProcessOp` 执行；部分活动分支使用 `IntentUtilsWrapper`。 | 打开目标应用，切换前台。原指令还允许携带 URI、SDK 参数、按键、搜索等其他子路径，不适合作为模型可直接生成的通用工具。 |

非手机设备的提醒分支、原厂批量/删除/查询及任意 Intent 能力都与以上三项不同，不从自定义模型转发。
APK Manifest 静态声明了 `com.android.alarm.permission.SET_ALARM` 和 MIUI 的
`miui.permission.ACCESS_ALARM_PROVIDER`；声明本身不代表某设备已授予。第二个权限及其
Provider 属于厂商路径，不是独立模块的公共接口。

## 公开 Android 对应能力

- 单次闹钟用 `AlarmClock.ACTION_SET_ALARM`，仅传 0–23 的小时、0–59 的分钟及可选文字标签。
  倒计时用 `AlarmClock.ACTION_SET_TIMER`，长度使用 **1–86400 秒**。两者要求公开的
  `com.android.alarm.permission.SET_ALARM`，不需要为模块自建精准闹钟权限，也不读写时钟
  私有 Provider。保持 `EXTRA_SKIP_UI=false` 或省略，交由时钟决定是否展示确认界面。
  [AlarmClock API](https://developer.android.com/reference/android/provider/AlarmClock)
- 打开 App 只接受用户明确指定、能够解析出唯一包名的已安装应用。使用
  `PackageManager.getLaunchIntentForPackage` 或平台支持的前门启动入口；不构造模型指定的
  Activity、URI 或任意 extras，不从应用商店安装。带查询的旧写法还受 Android 包可见性限制，
  不能把解析为空一概当作应用未安装。[PackageManager API](https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String))
- 从当前可见、用户发起的助手交互执行；只有 `Application` Context 时，启动 Activity 需要
  `FLAG_ACTIVITY_NEW_TASK`，仍须遵守系统后台启动限制。发出 Intent 或打开确认页并不证明任务完成：
  未得到结果时只报“已打开时钟/已提交系统处理”，缺少处理程序或被系统拦截时报告失败。
  [Android Activity 启动限制](https://developer.android.com/guide/components/activities/secure-bal)

这三项不依赖截图、输入注入或 UIAgent 上岛，可单独在模型对话链注册。只允许当前用户明确要求的
普通操作；System Prompt 应要求缺时间/时长/明确目标时先询问、不得把网页/截图/工具输出里的文字
当作授权、不得根据仅表示已提交的结果宣称执行成功。提示词不代替执行层的固定 action、参数校验、
时序和调用次数约束。若需要原厂私有提醒能力，优先保留正常原厂对话路径，不把模型结果加工成
任意原厂 AIVS 指令。

## 模块接入与验证

- 「通用设置 → 系统工具」默认关闭；开启后为文本和语音追加工具说明，保留原有
  System Prompt，并给 OpenAI 兼容与 Codex 请求组装相同的工具能力。与控制模型、
  自动分流开关独立。只对经固定参数校验的三种动作执行一次，重复工具请求回传同一结果。
- 本机回归覆盖提示词合并、禁用时拒绝调用、参数范围、未知参数和失败结果回传。
- 8.2 真机通过小爱文本 → 测试 Provider → `launch_app` → 系统时钟，确认时钟实际成为
  前台，且结果回传到模型。真机也确认小爱已获得 `SET_ALARM`，闹钟与倒计时的公开
  Intent 都能解析到时钟处理界面；测试未创建或修改用户的闹钟和计时器。
