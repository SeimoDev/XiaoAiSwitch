# 手机控制：实际执行与验证

目标仍是当前 Pad / 小爱 7.11.20.3514。原厂 UIAgent 的 Pad 门控未修改；模块改用独立控制模型、自己的工具协议和当前应用已有的平台能力，保持原厂的屏幕控制权限检查。

## 实现

- `ControlProtocol` 构造配置模型、用户 System Prompt、屏幕图片和六种工具定义；只在入口解析一次未知参数，再向内传入强类型动作。
- `ControlLoop` 每次实际 HTTP 调用后解析工具，执行动作，追加 assistant/tool 消息及新屏幕，再调用模型。默认最多 12 次，可配置 1–30。异常结果明确回传给模型，达到上限返回明确状态。
- `PhoneControl` 调原厂截图接口，使用归一化坐标和同步 `InputManager.injectInputEvent(..., WAIT_FOR_FINISH)`；中文文字走原厂 AIInputTextManager。
- 控制期间仅暂存并隐藏小爱进程自身窗口的可见性和触摸标志；右上角显示停止按钮。结束、失败或取消均恢复存活窗口；需要时以原厂 idle 状态重新显示结果卡片。
- 停止会解除界面占用并取消 HTTP 连接，代次检查阻止旧任务继续执行动作或显示回复。新请求也取消上一请求。

## 本机权限发现

最初低层原厂输入 helper 返回 true，但 `InputDispatcher` 记录“异步注入失败”：`INJECT_EVENTS` 已获准不等于 MIUI 对目标应用的 AI 控制权限已开放。因此改为同步注入，避免把“排队成功”当成“动作执行成功”。

模块通过原厂 `security.accessAppsControlEnable` 和原厂权限申请入口检查目标。首次测试出现“开启 AI智控”系统界面，已从该界面正常开启；没有直接重写权限数据库或修改系统检查函数。待确认时暂停动作，未取得权限时回报错误。

## 设备验证

| 样本 | 实际证据 |
| --- | --- |
| 控制模型 / Prompt / Token | fixture 服务收到 `FIXTURE_CONTROL`、`FIXTURE_CONTROL_PROMPT`、六工具 schema 和真实 JPEG 截图；Token 匹配 |
| 应用 / 导航 / 截图 / 循环 | 4 次模型请求：打开设置、取屏、Home、文字结束；各步 tool_call_id 对应，截图摘要随状态变化，原生卡片显示 `CONTROL_FIXTURE_OK` |
| 点击、中文输入、滑动 | debug 本地交互样本实际 `count=1`、`text=控制测试XASWITCH`、`scroll>0`；实际滚动数值见状态文件，由真实 View 监听器写入，非输入 helper 的自报成功 |
| 停止 | 后端故意延迟 8 秒时点击停止；按钮消失，状态明确为已停止；等待超出延迟后没有第二次请求，也未执行后续打开设置动作 |
| 界面恢复 | 完成后重新显示原生结果卡片；取消后恢复正常手机界面，不保留不可触摸的隐藏占位窗口 |
| 步数上限 | 真机设为 1 后仅发送一次模型调用，原生卡片显示达到上限，停止按钮移除；不会追加第二次调用 |

数据保存在本地 `evidence/control-basic*`、`control-gesture*`、`control-cancel*` 和 `round3-requests.jsonl`。控制请求日志中的截图内容用 SHA-256 摘要替代，实际 JPEG 保留在本地，不上传或提交。

`ControlFixtureActivity` 仅存在 debug 源集中。已实际构建 release APK 并检查其 manifest 与全部 DEX 字符串，未包含该 Activity、`play_audio` 或测试音频文件名。release 输出为未签名构建产物；本机安装的是开发 APK。

以上验证使用可控的兼容后端产生工具调用，并证明这些调用在真机实际执行；没有用固定后端的语义规划能力冒充任意真实商业模型都已通过。自选控制模型应支持图像输入与相同工具调用协议。
