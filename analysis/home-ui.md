# 小爱 Switch 首页与配置（0.10.0）

首页使用真实 Miuix 组件，包含激活卡片、版本、立即生效的接管开关和模型 / 设置二级菜单。

## 状态卡片

布局参考 [SukiSU-Nova 经典 Miuix 首页状态卡](https://github.com/SukiSU-Nova/SukiSU-Nova/blob/main/manager/app/src/main/java/com/sukisu/nova/ui/screen/home/HomeMiuix.kt)：顶部小标签、下方大状态字 / 等宽版本行、右下角局部裁切的圆形状态图标，以及 Miuix 按压反馈。使用本项目自己的 `ActivationCard` / Canvas 图形，没有引入其图片素材或内核功能。

- 当前版本已收到小爱 UID 的 Hook 上报：淡绿 / 深绿卡片和大号绿色圆圈对勾。
- 当前版本尚无上报：淡红 / 深红卡片和红色圆圈叉。
- 上报带模块编译版本；旧版加载记录不作为新版确认。点击卡片查看具体状态、时间和适配版本。
- 显示的是目标进程最近加载回报，不通过改写 LSPosed 设置或伪造框架返回值实现。

接管开关与激活状态独立：前者开关自选模型路由，不代表启停 Xposed 框架。首页切换立即保存该开关，不提交其它页面未完成的模型草稿。

## 配置与反馈

“AI Provider”统一添加 / 编辑传输信息和订阅登录，模型页面选择引用。文本、语音提供默认 / 思考切换，各自有独立模型、Provider、Prompt；运行时跟随真实原生状态。Provider 保存不覆盖模型字段，正在使用的引用受删除保护。

保存成功显示高对比绿色 Miuix Snackbar（含“知道了”按钮），保存按钮显示“✓ 已保存”；失败显示红色提示并保留草稿。提示提供可访问性 live-region 语义。主题跟随系统，配置页适配手机和宽屏。

## 验证入口

- `scripts/provider_ui_test.py`：真实 Provider CRUD、订阅账号保留、两类模型目录、模型引用、两套模式配置、草稿保护、保存反馈、开关、旋转和主题。
- `scripts/activation_card_device_test.py`：通过本地上报缓存 fixture 验证未确认状态的红色呈现；再通过真实小爱加载确认绿色状态，不停用 LSPosed 本身。
- 真实运行的光效、Markdown、模式切换分别见 `native-control-effect.md`、`native-markdown.md`、`thinking-modes.md`。
