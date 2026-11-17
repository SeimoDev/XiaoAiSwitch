# Miuix UI 重写（0.6.0）

设置页与 Codex 账号页从 Android View / Java 迁移到 Kotlin / Compose，实际依赖 `top.yukonga.miuix.kmp:miuix-android:0.8.7`，不是用普通控件模拟样式。

## 页面与数据

- `SettingsActivity.kt`：Miuix 大标题、状态总开关、四通道切换、Provider 与模型选择、独立 Prompt、运行方式和 Hook 状态。保存按钮固定在底部并避让键盘；宽度达到 840dp 时使用双栏。
- `CodexLoginActivity.kt`：订阅状态、设备代码、复制并打开官方登录页、取消等待和退出确认。账号读写仍由原 `CodexAccounts` 完成。
- `AppUi.kt`：共享 Miuix 主题、卡片、表单、开关、选择弹窗与按钮，跟随系统深浅色，使用组件语义颜色。
- 草稿以 `rememberSaveable` 保存，切换通道、屏幕大小和主题时不会丢失未保存内容。API Token 使用密码遮罩；从 API Provider 切到订阅再切回时，原 API 配置仍保留。
- 新增 core `SettingsConfig` 在提交时统一校验四通道、图片 Provider、默认通道与 1–30 次预算，并保留额外配置字段。普通配置存储格式、revision、UID 边界、密钥库和 Hook 协议不变。

保留小爱自身的原生回答 / 图片卡片与控制停止浮窗；本轮重写的是模块设置和账号管理页面。

## 构建

JDK 17、compileSdk 36、targetSdk 35、Kotlin / Compose Compiler 2.3.20、AGP 8.13.2、Gradle 8.13。更新 AGP 后，初次迁移时旧 D8 对 Kotlin metadata 的警告消失。

从 `core` 执行：

```powershell
../gradlew.bat test jacocoTestCoverageVerification :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
```

47 项 core 测试通过；JaCoCo 指令覆盖率 90.25%，80% 门槛通过。lint 为 0 errors、19 warnings。该覆盖率不包含 Android UI。

## 真机验证

设备为此前同一台 Xiaomi Pad 5。`scripts/miuix_ui_test.py` 操作真实页面，未替换生产组件：

- 手机窄屏、平板竖屏与横屏双栏，浅色 / 深色均通过；截图像素另外验证主题表面颜色确实变化。
- 修改模型与 Prompt，来回切换文本 / 语音 / 绘图通道，草稿保持独立；旋转后仍保留，再保存并读取真实配置核对。
- 默认通道、控制预算、额外配置字段和总开关状态保存正确。
- 手机现有 Codex 账号显示正常；打开退出确认后取消，登录保留；实际获取订阅模型列表并选择成功。
- API / Codex 来回切换后原 endpoint 保留，Token 为密码语义；绘图不展示订阅 Provider。
- 完整八项生命周期回归通过，包括通过新 Miuix 表单修改 Prompt 后，实际后端立即收到新值，以及错误显示、故障恢复、取消旧响应和禁用回原厂。
- 新 APK 安装后，再次真实手机直连 Codex，原生卡片显示 `CODEX_CHAT_OK`。

证据保存在本地忽略目录 `evidence/miuix-*.png`、对应 XML、`miuix-regression-requests.jsonl` 与 `codex-live-chat-result.json`。UI 测试恢复原配置、主题与屏幕尺寸，不退出账号。

release manifest / DEX 检查确认实际包含 Miuix，且没有 debug 交互 / FIXTURE / 音频测试入口。最终 debug APK 已安装，签名验证通过，SHA-256：`98988fa89ef6e8b23c84b5d3742d6346c985d5c32b47b2cd00e1ffff931b5293`。

测试服务已停止，ADB reverse 为空；原 Codex / `gpt-6.1-sol` 配置与登录保留，模块总开关仍关闭。设备恢复 1600×2560 与原浅色模式。
