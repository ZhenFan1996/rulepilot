# RulePilot：当前产品与系统模型

这份文档只描述当前生产路径。历史方案、事故流水、一次性试验和已经退休的合同不在仓库中继续充当产品真相。

## 一句话介绍

RulePilot 是一个证据优先的桌游助手：先根据玩家的自然需求推荐游戏，玩家选中后绑定规则书并生成可上桌的分章讲解，最后由同一个规则答疑 Agent 按需读取当前规则书证据回答追问。

推荐、规则书取得、讲解和答疑是连续体验中的四个独立结果。后一步、一个页面或一张配图失败，不得抹掉已经验证并持久化的前一步结果。

## 用户链路与最小成功单位

```text
玩家自然对话
  → 推荐 Agent 自主决定直接回复或使用目录/公开资料工具
  → 玩家选择一张已验证身份的游戏卡
  → 发现官方规则书，或由玩家上传
  → 绑定不可变文档版本并提取页面
  → 讲解 Agent 规划并逐章发布正文与可选配图
  → 答疑 Agent 按需搜索、读页并给出引用回答
```

| 结果 | 最小成功标准 | 后续失败时保留什么 |
| --- | --- | --- |
| 推荐 | 普通对话有模型自然回复；真正推荐时另有身份明确且证据绑定的游戏卡 | 对话、请求、已验证候选和最近一次成功发布结果 |
| 规则书取得 | 来源或上传已绑定到正确游戏和不可变文档版本 | 原文件、页面、身份和处理进度 |
| 讲解 | 至少一个有引用、可读、已持久化的章节 | 每个已发布章节及其可选图片；缺口作为 unresolved topic 留存 |
| 答疑 | 普通闲聊可无工具直接回复；规则答案的支持部分有同一 run 的精确页证据 | 讲解、规则书、已完成的工具 observation 和安全停止原因 |

公开讲解库是独立浏览入口，不参与推荐排序，也不是答疑是否可用的判断条件。

## 技术与边界

| 层级 | 技术 | 职责 |
| --- | --- | --- |
| Web | Vue 3、TypeScript strict、Vite、Tailwind、Vue Query、PWA | 对话、真实进度、失败语义、讲解阅读和答疑 |
| API | Java 21、Spring Boot 4、Spring Modulith、Spring AI | 用例、事务、Agent 工具协议和确定性发布边界 |
| 数据 | PostgreSQL、pgvector | 业务事实、证据、会话、任务和持久化快照 |
| 协调 | Redis、RabbitMQ、Transactional Outbox | 租约、并发、异步任务和可靠事件 |
| 文件 | MinIO / S3 兼容对象存储 | PDF、页面图片和局部裁剪 |
| 可观测性 | OpenTelemetry、Prometheus、Tempo、Grafana | 请求、模型、工具、停止原因和发布诊断 |
| 验证与交付 | JUnit、Testcontainers、Vitest、Playwright、GitHub Actions、Docker Compose | 确定性测试、不可变 SHA 部署、回滚和线上 canary |

系统是模块化单体。domain 保持纯 Java；application service 拥有用例和事务；模型、HTTP、数据库、消息和对象存储由 adapter 实现。模块不能读取另一个模块的 repository 或 persistence entity。

## 统一 Agent 运行原则

推荐、讲解规划和答疑都采用同一个基本循环：

```text
模型观察当前状态
  ├─ 返回一个或多个允许的 typed read/tool action
  │    → 应用验证参数与身份
  │    → 执行并把真实 observation 交回同一模型
  │    → 模型再次自行决定
  └─ 返回完整终态
       → 应用只校验发布边界
       → 通过则原样发布模型自然文案
```

系统不再单独调用“下一步动作模型”，也不规定一次请求必须经过几次模型、几次工具、几次修正或几个固定阶段。普通问候和不需要资料的闲聊可以一次模型调用、零工具结束；需要资料时，模型可在首轮直接选择搜索。

调用次数和延迟是审计事实，不是正确性合同。整轮只受真实资源边界控制：provider 上下文/输出、持久化 token 包络、active-work deadline、取消、账户额度、并发背压、安全尺寸和数据库查询能力。完全相同的已拒绝 action/observation 再次出现时，以 no-progress 停止，避免无意义循环；新的完整候选仍可继续修正。

自由文本只用于玩家展示，不参与意图、实体、偏好、数量、证据、工具或状态路由。应用不得从旧稿抽字段、套模板或拼接玩家回复。

## 推荐

推荐入口只有一个 ReAct Agent：

1. 模型收到自然对话、当前 turn 的显式证据和当前可用工具。
2. 它可以直接自然回复，或调用目录搜索、目录发现、公开资料研究和比较等 typed tools。
3. 相互独立的只读动作可以同 turn 并行；结果按原 call 顺序作为 observation 返回。
4. `recommend_games` 是完整终态：同时包含完整 `playerReply`、目标卡片数量以及每张卡的身份、证据绑定说明和可选取舍。
5. 应用不写 lead、不补卡片话术、不根据已有候选伪造成功回复。

搜索工具把肯定和排除条件分开表达；“不要扩展”不会再被保存成“偏好扩展”。候选选择只使用当前 turn 明确产生的 typed 约束，不把历史的错误 profile 隐式继承进本轮。请求数量由终态拥有，搜索池大小不再被误当成最终发布数量。

发布边界只验证：BGG 身份、玩家明确的硬条件与排除项、候选和证据归属、完整终态结构。additive unknown JSON 字段被忽略。终态不合格时，完整原参数、`code/path/reason`、当前 schema、允许候选 ID 和每个候选的允许证据 ID 一起交回同一 Agent，由它返回一份完整替代结果。

`NO_MATCH` 是有解释的成功结果；provider、协议、空响应、输出截断、deadline、取消、持久化或发布边界停止是 typed failure。失败 turn 不覆盖最近一次成功发布结果，也不把内部 checkpoint 冒充成玩家已收到的卡片。

## 规则书取得

玩家明确选择游戏后才开始来源发现。官方来源必须通过 URL、内容类型、可读性、游戏/版本身份与来源归属校验；无法确认时要求玩家确认或上传。

上传和官方导入最终形成同一种不可变文档版本。PDF/图片页处理按批次遍历全部输入；每批读取大小、文件字节、页面像素、总上传页数和网络响应大小属于内存、存储与安全资源边界，不是“只读前 N 页”的内容正确性门禁。

导入失败只影响来源/文档 owner，不会重新执行推荐，也不会删除已经成功取得的来源和页面。

## 讲解

### 规划

`SpringAiTeachingOutlineModel` 是一个单一 Agent，只有三个动作：

- `read_pages`：读取它认为当前需要的可用页；不可用页作为局部 observation 返回。
- `publish_chapter`：发布一个章节计划，引用已读页面，可声明对前序章节的依赖以及是否建议配图。
- `complete`：提交游戏标题、自然导语、已覆盖章节 ID 和具体 unresolved topics。

Agent 每次都看到可用页、已读页原文、已发布章节、未读可用页、已读但未用于章节的页和最新 observation。系统没有固定章节模板、slot ledger、canonical shard、global ordering、完整页覆盖门禁、固定 outline turn 或固定修正次数。

必要合同只有：动作类型和必需字段正确；页面、章节和依赖身份来自当前状态；章节至少引用一页已读证据。未读页、已读未引用页、未被终态确认的已发布章节和模型声明的缺口会进入 durable unresolved topics，而不是让可读计划整体失败。

非法 JSON 或 action 会把完整候选、`code/path/reason/schema/allowedPageIds/allowedChapterIds` 交回同一 Agent。additive unknown 字段被接受；应用不 patch 字段，不从旧对象拼新对象。

### 章节与配图

`GroundedTeachingAgent` 以章节为最小工作单元：

1. 独立章节在虚拟线程中并行；有依赖的章节等待 prerequisite future。
2. 章节读取 Agent 选中的原始 source pages，并生成完整自然正文候选。
3. 发布边界只验证必需结构、当前文档/页面/证据身份和引用。
4. 同一个章节任务继续准备可选配图；图片读取、模型选择或 crop 失败只省略当前图片。
5. 正文与可选图片作为一次完整章节快照原子发布，不向玩家暴露半拼接章节。

生成主路径没有 critic 或 localizer 模型阶段。localization 只在玩家显式请求时作为发布后投影，失败不修改中文源课程；质量 evaluator 是只读管理报告，不阻止章节发布。

只要有一个可读章节，结果就保留。缺章、不可读页、未引用页、unresolved topic 或局部图像失败使结果成为 `DRAFT_READY/DEGRADED`，而不是抹掉全部章节。只有来源整体不可用、零可发布章节、身份/版本/引用越界、不可恢复的持久化失败、全局取消或资源停止才结束整个 owner。

## 答疑

同步答疑入口只有：

```text
NativeRuleAnswerAgent
  → BoundedNativeToolAgent
  → allow-listed read tools
  → CHAT | RULE_ANSWER | CLARIFICATION terminal
```

旧的 interpretation、预检索、admission、refiner、reviewer、critic、ModelDraft、answer aids 和 presenter 模板拼接已经退出同步路径。

- `CHAT`：普通对话可一次调用、零工具，无引用。
- `CLARIFICATION`：模型提出一个完整、自然的澄清问题，无伪造引用。
- `RULE_ANSWER`：模型按需搜索 relationship/context/page/visual/image/crop 工具；发布时只能引用同一 run 内成功 `read_rule_pages` 返回的 canonical evidence identity。

同一 turn 中互不依赖的 read actions 使用虚拟线程并行。单个 sibling 的 provider/tool Runtime failure 只形成 correlated `TOOL_EXECUTION_FAILED` observation；成功 sibling 保留。只有取消、deadline、持久化资源控制或 owner boundary 才中断整批。

发布边界验证当前文档版本、精确页快照、引用身份和硬数字。硬数字由模型在 `numericClaims` 中逐 literal 指向一个已引用证据 ID；自然解释、结论、例外和澄清全部由模型生成并原样发布。

非法工具参数或终态将完整原 payload、`code/path/reason/currentSchema`、允许工具名或 evidence IDs 返回同一 Agent。additive unknown 字段被忽略。应用不要求玩家因为 `INVALID_MODEL_OUTPUT` 改写问题，也不以本地模板修补答案。

## 玩家可见的失败语义

所有入口使用四类一致语义，并显示真实 backend code/record 与 owner：

| 类别 | 何时发生 | 产品处理 |
| --- | --- | --- |
| `local-degradation` | 单页、单章、单张配图或一个可选读取不可用 | 保留正文和成功 sibling，继续其余工作；终态列出具体缺口 |
| `retry-preserved` | provider、队列、transport、deadline、账户暂不可用或取消 | 当前 owner 停止；持久化进度保留，可用原输入启动新 run |
| `repair-required` | 认证、输入、来源、所有权、版本、持久化、身份或引用硬边界不成立 | 先修复前置事实；无变化重试既不安全也不会成功 |
| `internal-correction` | typed JSON、工具参数、协议或计划候选不合法 | 不是玩家请求失败；完整候选和精确诊断回到同一 Agent 整包重生 |

`internal-correction` 只有在完全相同的拒绝重复、资源停止或 provider 无法继续时才转成最终停止。前端不解析自由文本来判断分类，不展示已退休的 `nextAction`，也不承诺“自动重试一次”或固定分钟数。

## 持久化、并发与恢复

- PostgreSQL durable state 是 conversation、document、assistant run、teaching plan 和 lesson 的权威来源。
- SSE 展示实时活动，轮询负责断线恢复；UI 不模拟进度或从终态倒推不存在的阶段。
- revision、lease、activation token、fencing、幂等键和 transactional outbox 防止重复消费和旧 worker 覆盖新结果。
- 每个章节通过边界后形成 durable snapshot；局部图片或后续章节失败不会回滚它。
- 同一个任务的终态写入失败由独立 reconciliation owner 处理；它的调度容量和指数退避属于进程资源保护，不是内容修正次数。
- 手动重试创建明确的新 attempt，并复用规则书、页面、证据和已发布章节；系统不在 adapter 内隐藏重放真实模型调用。

## 测试、CI、部署与线上验证

PR CI 只运行可重复、无付费模型的确定性检查；真实模型 canary 是 opt-in 运行证据，不进入普通 CI。测试保留领域不变量、一个用例的发布/恢复语义、基础设施真实边界和独立用户旅程，不维护已删除流程的 mock choreography、prompt 句子或固定调用次数。

合并到 `main` 后，部署 workflow 只接受该 SHA 已成功的 CI。源码、控制面和运行产物先在无生产权限 job 中封存并绑定 SHA，再交给隔离的生产 runner；release guard 在部署锁内验证镜像、数据库迁移、API、worker、前端、健康状态和 exact release identity，失败时回滚到上一个已提交 release。

推荐 canary 与规则书→讲解→答疑 canary 分开运行。它们记录完整结果、模型/工具调用图、各段延迟、typed failure、部署 SHA 和清理结果，但不以固定模型调用数、固定页数、固定章节数或固定延迟充当产品正确性合同。sanitizer 删除凭据、模型私有 reasoning、用户上传和受版权保护的原始规则书内容；有 artifact 不等于旅程成功。

2026-08-30/31 的 Qwen3.8 Flash 真实基线保存在仓库外 `.local/agent-evaluation`：

- 推荐普通问候/非游戏闲聊/轻桌游闲聊均为一次模型、零工具；复杂推荐为模型自主搜索、研究后提交完整终态。
- Q&A 普通问候为一次模型、零工具；SETI raw 轨迹自主并行读页并得到正确规则答案。一次新 endpoint 的终态合成独占约 60 秒后 provider failure，证明这是外部调用停止而不是本地固定流水线等待。
- Captain is Dead 新讲解 Agent 自主完成 5 次读页、7 次章节发布和一次 complete，7/7 章发布、无 activity failure；具体未覆盖主题保留为 unresolved，未冒充完整课程。

这些样本证明接线、合同和失败归属，不代表总体失败率或 provider SLO。

## AI 安全与质量边界

模型输出在发布前不可信，但“边界”不等于替模型重写内容。系统只严格拥有：

- schema 的必需字段、类型和协议解码；
- typed tool allow-list 及参数的身份、范围和只读性；
- 游戏、文档版本、页面、章节、候选和证据的所有权；
- 引用与硬数字事实；
- 数据库事务、幂等、并发、资源、安全和取消。

其余自然语言由模型拥有。支持的部分应发布；不支持的部分局部说明或形成 unresolved topic。任何 optional visual、localization、evaluation 或 audit 能力失败，都不能推翻已经通过确定性边界的内容。

新增生产限制必须能说明外部 owner 和测量依据。禁止手写固定字符、章节、页面、候选、模型调用、action 或 retry 次数作为内容正确性门禁；优先使用遍历式批处理、并发背压、durable snapshot 和局部降级。

## 当前限制

- 外部模型、BGG 和官方来源仍受网络、限流、provider 协议和账户状态影响，因此玩家必须看到准确 owner 和停止原因。
- 单次真实 canary 只能证明一个输入、一个模型版本和一个时间点，不能推导长期失败率。
- 复杂跨页规则、版本冲突和视觉图表仍需要更多真实语料评测，但修复必须发生在最早的通用责任边界，不能把游戏名、页码或同义词写成 production special case。
- 讲解 Activity API 尚未为每个局部降级单独暴露 `failureCode/failureOwner`；当前 UI 显示真实 `operation · summary` 和模块 owner，终态显示 typed `lastErrorCode` 与 `unresolvedTopics`，且不解析 summary 做业务路由。
- 数据库中少数已退休字段仍作为存量 schema 兼容列存在；live domain 和 Agent 路径不读取其语义。只有在生产存量有证据为零并具备独立 migration 时才删除物理列。
