# 统一 AI Provider 与模型引用（0.10.0）

Provider 的名称、类型、Endpoint、Token 在设置中集中维护；文本、语音、绘图、控制及两套对话模式仅保存引用、模型名、提示词与启用状态。Codex Provider 使用手机当前登录，不迁移或复制 OAuth 凭据。

```json
{
  "providers": {
    "API": {"name": "My API", "provider": "openai_compatible", "endpoint": "https://HOST/v1", "token": "TOKEN"}
  },
  "chat": {
    "enabled": true, "providerId": "API", "model": "MODEL", "systemPrompt": "DEFAULT_PROMPT",
    "thinking": {"enabled": true, "providerId": "API", "model": "THINKING_MODEL", "systemPrompt": "THINKING_PROMPT"}
  }
}
```

## 实现

- `AiProviders` 管理稳定 ID、去重迁移、保存、删除和引用解析。旧内联配置仍由运行时兼容；设置页第一次读取时创建不改变有效值的迁移草稿，提交后以 Provider 注册表为唯一传输来源。
- 迁移移走模型页的旧地址 / 密钥字段，不在每个模型中重复存放凭据。旧 Codex 配置里未使用的 API 草稿密钥保留为独立 API Provider，避免丢失。
- 修改 Provider 的名称、地址或密钥立即影响引用它的配置，不改模型与 Prompt；Provider 保存不顺带提交其它模型页的未完成草稿。
- 活跃模型仍在引用时保护删除；删除未使用源时清除停用的引用，保留模型名称和提示词。已存在空注册表不会重新导入已删除的旧来源。
- `ModeProfiles` 与 `HookEntry` 在请求边界解析引用。默认 / 思考可引用不同 Provider，仍共享用户可见历史；API 兼容传输不发送 Codex 私有加密 reasoning 元数据。
- 模型选择读取兼容源 `/models` 的 `data[].id`，或手机订阅目录；保留手动模型名称输入。Codex 图片模型独立于语言模型，默认 `gpt-image-2`。

## 已取得的运行证据

`provider_ui_test.py` 操作真实 Miuix 页面，完成 Codex / API Provider 添加、账号保留、名称与密钥编辑、使用中删除保护、未使用源删除、两类模型目录选择、默认 / 思考分别绑定、草稿 / 旋转 / 主题、首页开关立即生效及成功 / 失败保存反馈。结果在 `evidence/providers-ui-result.json`。

Provider 引用式配置另外通过真实兼容 HTTP 问答，以及手机 Codex 对话 → 原生订阅图片 API → 原生 Gallery 的完整链路；后者未放置内联 Provider / Endpoint / Token，不依赖电脑转发或额外 API Key。

新旧配置迁移、凭据集中存储、独立模式引用、全局编辑、删除保护与模型目录 Authorization 另有 core 单元 / loopback HTTP 测试。最终构建与覆盖率记录见工程检查输出。
