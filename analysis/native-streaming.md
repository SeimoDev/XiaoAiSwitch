# 小爱原生文字流：7.11 / 8.2

下列为本地 APK 的协议核对；模块真机回填见文末。样本版本见
[适配记录](voiceassist-8.2.md)和[原生 Markdown 记录](native-markdown.md)。

| 协议 | 8.2.70.8421 | 7.11.20.3514 |
| --- | --- | --- |
| 开始 | `Nlp.StartStream`，空 payload；`g51.a.buildStartStream` | `w30.i.generate` 同名空 payload |
| 文本增量 | `Template.ToastStream`，唯一必填字段 `markdown_text`；`bf1.d` 创建 `qh1.c0` | `z30.p` 生成同名 payload，`ce0.s` 消费 |
| 结束 | `Nlp.FinishStream`，空 payload；`ff1.h → ff1.j → ff1.g.M` 在流队列追加本地结束标记 | `w30.d.generate`；`bc0.f.L` 同样追加结束标记 |

8.2 的 `qh1.c0.B0` 对每个普通文本包调用原厂 Markdown 预处理，然后
`StringBuilder.append(delta)` / `stream.g.updateText(delta)`。旧版 `ce0.s` 也一样。
按顺序发**新增内容**，不能把累计全文当作每包内容。每个 `header.id` 独立且唯一
（8.2 `g51.a.b` 用随机 UUID），`dialog_id` 始终等于同一个真实请求的对话 ID；
`ff1.h` 根据对话及当前流操作把后续包并入同一张卡，不要求每包重用同一个 ID。

`<FINAL>` 是原厂队列生成的保留结束哨兵，不是可见文字。不把模型输出中正好等于该标记的
文本原样作为单个包交给原厂，也不把其他原厂前端结构标签解释成动作或指令。普通 Markdown
流卡能由 `ToastStream` 独立创建，未发现必须引入 MiClaw 的 `Template.FrontendPage`；
原厂 MiClaw 的本地 mapper 同时发网页前端、结果操作按钮等指令，那是另一条链路。

## 最小接入候选

在已有原厂 Handler 上，对一次模块拥有的回答只开放一次 `Nlp.StartStream`，将真实文本回调
转换为 `Template.ToastStream`，全部模型/工具阶段结束后只发一次 `Nlp.FinishStream`。
结合现有 `Nlp.EventACK` / `Dialog.Finish` 提供与原链一致的对话生命周期；直接
`b30.o0.W` / `p31.o0.b0` 回填是否完整经过原厂流启动及结束，须实机核对。
不要混发 `FinishAnswer` 和 `FinishStream` 来尝试解决同一个收尾。原生流操作自带
Markdown 与累积历史存储，不需要 `ReplyMarkdown` 的普通 Toast 补丁，也不要额外发送
一份最终全文 Toast。如果有绘图工具结果，可单独接原生图库卡；不重复显示已经流过的文字。

8.2 `qh1.c0.B0` 还可能调用 `yf1.y1.onTextReceived`，具体是否发声取决于已开启的
原厂流式发声状态。语音路由继续使用独立完整 TTS 时，要单独真机验证没有双播；普通文本
是合适的首个验证入口。取消或换请求后，不把旧的文本或收尾发送到新对话。

## 传输与工具边界

- 普通兼容接口以 `stream:true` 请求 SSE；Codex 直接把已使用的 SSE 中
  `response.output_text.delta` 传给可见文字回调。两路都保留完整结果用于历史和工具解析。
  普通 JSON 回退保留原卡片，不伪造增量。不向卡片发送思考事件、工具参数或工具结果 JSON。
- 工具的 ID、名称、分片参数必须在同一轮聚合，并等待完整终止和校验后，才能由现有
  `DialogueLoop` 执行；每个工具结果仍按原调用 ID 回补给下一轮。工具轮不是用户回答的结束。
  若同一轮在工具之前已经发送公开文字，原厂流是追加式，无法在得知该轮还有工具后回收前言；
  可以保留前言并继续追加真正的后续回复。若要求绝不展示工具轮文字，就需要明确分开
  规划与最终回复，不能先缓冲带工具的整轮，再把最终文本切字冒充流。
- 验收用本机 SSE fixture：首个真实数据事件 flush 后暂停服务器，在最后事件尚未发送时
  检查首句已在原生卡片显示；释放后核对同卡全文、不重字、不重复卡、正常收尾。
  另外核对工具参数多片聚合、取消以及语音收尾。

## 模块验证

- 真机 8.2 文本和语音通道都已通过 `scripts/native_stream_test.py` 的终包闸门验收。
  在服务器仍未释放终包时，原生卡片已出现首段；释放后，末段追加在同卡上，没有重复全文卡。
  语音通道另观察到原厂 TTS 正常播完。该测试从文本指令指定语音通道，不假称已验证新的麦克风路径。
- 本机真实 HTTP SSE 回归覆盖 OpenAI 兼容和 Codex 格式的早到增量、工具参数分片与聚合、
  JSON 回退、流取消等。旧 7.11 有原厂协议与本地回归，本轮未用旧设备重测。
