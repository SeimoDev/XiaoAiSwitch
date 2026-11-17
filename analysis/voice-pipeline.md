# 语音链路、LLM 与 TTS 的边界

样本和版本与 `progress.md` 相同。静态依据是本地 JADX 输出，以下运行结论只对应已经执行的设备测试。

## 原厂处理链

`b30.o0` 对外创建 `SpeechRecognizer.Recognize` AIVS Event，音频通过 SDK 二进制数据接口传输；输入不是直接交给通用 chat/completions 的 JSON 音频字段。客户端接收 `SpeechRecognizer.RecognizeResult`，payload 包含 `is_final` 和 `results[].text`。`c40.a` 在 final 时更新 query、会话与识别回调。

原厂识别、NLP、TTS 可由 `Execution.RequestControl.disabled` 分开控制。插件当前只保留原厂 ASR，并对该 Recognize 加入 `disabled=[NLP,TTS]`，在 final 原处理结束后，用“语音对话”配置的模型服务构造真正的 `messages` 请求。这样替换的是识别后的语言模型，不把 ASR 模型或 TTS 音色误称为同一个 LLM。

## 原厂 TTS 回接

有效入口为 `b30.z0` 的原始单例字段 **`a`**，调用 `speakTts(String,s10.b,boolean)`。它建立独立 TTS Engine，读取小爱设置中的 vendor、speaker 和语言，构造 `SpeechSynthesizer.Synthesize`，通过 AIVS 下发音频并播放。

`s10.b.onPlayFinish(boolean)` 的布尔值含义是 **isInterrupted**：`false` 表示正常结束，不是播放失败。静态消费者和设备日志 `onPlayFinish interrupted = false` 已互相印证。

直接调用底层 `b30.o0.speak(String)` 不是本版本的有效适配入口：真实测试曾触发旧路径 `M0(u0.d)` 读取空 `w40.f.h`，随后已改用原厂正在使用的独立 `b30.z0` 路径。当前代码没有继续调用该旧入口。

## 已实测与未实测

- `scripts/device_fixture_test.py --route voice --expect-tts` 实际经小爱文本请求入口调用语音通道。
- 合成后端收到 `FIXTURE_VOICE` 模型、`FIXTURE_VOICE_PROMPT` 的 system 消息与指定 query。
- 回答显示在原生卡片，原厂 TTS 触发播放并正常结束，模块实际收到 `onPlayFinish(isInterrupted=false)`。
- 本地证据是 `evidence/voice-tts.xml`、`evidence/voice-tts.png`、`evidence/voice-tts-status.xml` 与 `round2-requests.jsonl`。
- 以上证明“指定文本 → 自选语音通道 LLM → 原生显示 + 原厂 TTS”，**尚未证明真实麦克风音频 → 原厂 ASR → 自选 LLM 的完整闭环**。这项仍在后续验证清单。
- 实时语音模型的双向音频流不是当前 chat 文本适配器的工作方式；没有把音频端到端能力作为已经实现的结论。

System Prompt 由插件在自选 LLM 请求中设置为 `role:system`。原厂云端的内部系统模板与精确模型版本，仍不由这些客户端字段或播放回调证明。

## 第三轮追加：真实麦克风核验通过

前述“麦克风待验证”是第二轮的历史状态。本轮使用 `--manual-microphone` 调原厂 VoiceService 开始实际聆听，命令没有 `assist_query` 文本，也没有替换录音数据或注入识别结果。用户在设备旁说出测试短句，实际识别到 **`12+6等于多少`**。

该识别结果实际进入 `/v1/chat/completions`：`model=FIXTURE_VOICE`，system 消息为 `FIXTURE_VOICE_PROMPT`，Token 匹配；返回标记显示在原生卡片，原厂 TTS 正常结束（中断=false）。`scripts/device_fixture_test.py --manual-microphone --expect-tts` 通过。证据：`evidence/voice-microphone-manual-recognized.txt`、对应 UI / 状态 XML 与 `round3-requests.jsonl`。

此前本机扬声器播放合成短句的尝试未准确识别该短句，未作为短句识别正确的证据；随后改用真实人工发声完成核验。现在证明的是完整麦克风链路，不再仅以文本输入冒充语音测试。

本轮也修复了 final 为空时向自选后端发送空 query 的问题：空结果不发起 LLM 调用，解除该 dialog 接管，让原厂完成收尾。`asrStarted` 仅记录识别开始时间用于诊断，不存储录音内容。
