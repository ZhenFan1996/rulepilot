# RulePilot：项目逻辑、生产架构与面试说明

这份文档只描述当前系统。它既是开发时的总地图，也是求职面试时的项目讲解稿；事故流水、历史方案、
任务日志和一次性测量不继续堆在仓库里。

## 一句话介绍

RulePilot 是一个证据优先的桌游助手：先根据玩家偏好推荐游戏，玩家明确选中后再取得并绑定规则书，
把规则书生成可上桌的分章讲解，最后只依据当前规则书证据回答追问并给出页码引用。

它最重要的产品约束不是“模型能写多少内容”，而是：推荐、规则书、讲解和答疑分别成功、分别恢复；
后一步或可选增强失败，不能抹掉前一步已经有用的结果。

## 用户链路

```text
玩家描述偏好
  → 推荐 Agent 返回自然回复和可选择的游戏卡片
  → 玩家选中一款游戏
  → 发现官方来源，或由玩家上传规则书
  → 绑定游戏身份与规则书版本并提取页面证据
  → 生成有引用的分章讲解
  → 玩家围绕当前规则书继续答疑
```

这是一条连续体验，但不是一个原子事务。系统实际维护四个独立结果：

| 结果 | 必要输入 | 成功标准 | 不依赖什么 |
| --- | --- | --- | --- |
| 推荐 | 玩家请求、会话、已验证的桌游目录 | 普通对话有自然回复；真正推荐时另有身份明确的游戏卡片 | 公开讲解库、规则书是否现成、讲解状态 |
| 规则书取得 | 玩家明确选中的游戏 | 可读取且身份已绑定的规则书版本 | 推荐模型、公开讲解是否存在 |
| 讲解 | 已选规则书版本及其页面证据 | 至少有可读、有引用、已持久化的章节 | 可选图示、OCR、公开发布 |
| 规则答疑 | 当前规则书或讲解证据 | 支持的部分带引用发布；不足处明确说明 | 推荐链路、公开库收录状态 |

公开讲解库只是独立的浏览入口和可选捷径。推荐不会查询它，也不会因为某款游戏已有公开讲解而改变排序；
答疑依赖当前绑定的证据，而不是“是否公开”。

## 技术栈与生产拓扑

| 层级 | 技术 | 主要职责 |
| --- | --- | --- |
| Web | Vue 3、TypeScript strict、Vite、Tailwind CSS、Vue Query、PWA | 对话、规则书接力、真实进度、讲解阅读与答疑 |
| API | Java 21、Spring Boot 4、Spring Modulith、Spring AI | 用例、事务、Agent 工具协议、发布边界 |
| 数据 | PostgreSQL、pgvector | 业务事实、规则证据、向量检索、持久任务状态 |
| 协调 | Redis、RabbitMQ、Transactional Outbox | 缓存、租约、跨进程任务、可靠事件投递 |
| 文件 | MinIO / S3 兼容对象存储 | PDF、页面图片与局部裁剪 |
| 可观测性 | OpenTelemetry、Prometheus、Tempo、Grafana | 指标、链路、失败边界和发布诊断 |
| 验证 | JUnit、Testcontainers、ArchUnit、Vitest、Playwright | 领域、用例、基础设施和独立用户结果 |
| 交付 | GitHub Actions、Docker Compose、不可变 release SHA、回滚 | CI、部署、健康检查和线上 canary |

后端采用模块化单体，而不是微服务。领域对象保持纯 Java；application service 拥有用例和事务；数据库、
模型、HTTP、消息与对象存储都在 adapter；模块不能直接读取另一个模块的 repository 或 persistence entity。
这样保留单体的部署效率，又能用业务边界控制耦合。

生产环境把推荐决策和其公开资料搜索都显式固定为 `qwen3.8-flash`，避免继承通用 Qwen 角色的较慢默认模型；
公开资料 adapter 让模型在内置 web search 后直接提交 typed function result，并记录内部搜索次数、source ownership
和总耗时。公网 Caddy 明确协商 X25519 / P-256，避免仅支持传统 TLS group 的 LibreSSL 客户端在 Caddy 默认混合
后量子 group 上于 HTTP 前断连；部署后同时用 OpenSSL group 探测、普通 curl 和真实浏览器验收。

## 模块职责

| 模块 | 负责 | 明确不负责 |
| --- | --- | --- |
| `catalog` | BGG 游戏身份、结构化资料、展示归属 | 玩家意图、规则书和讲解状态 |
| `recommendation` | 会话、typed action、候选选择、证据校验、推荐发布 | 公开讲解查询、文档导入、讲解、规则答疑 |
| `document` / `ingestion` | 来源发现、身份确认、上传、导入、PDF 与页面提取 | 推荐排序、讲解文案 |
| `teaching` | 规则证据整理、教学计划、章节生成、可选局部图示、公开课浏览 | 推荐是否成功、答疑发布 |
| `assistant` | 证据检索、受预算约束的规则回答、引用发布 | 文档所有权、推荐选择 |
| `identity` | 注册、登录、会话与资源所有权 | 业务内容判断 |

## 四条运行链路

### 1. 推荐

1. 前端为一轮对话提交 `conversationId`、revision 和 `clientTurnId`，避免重复或过期结果覆盖新一轮。
2. SSE 在任务进入后台 executor 前先发出真实的 `understanding_request` 活动；之后传 `progress/result/error`。
   provider 只有在整轮确认没有选择工具后，才把自然回答作为一个完整 `answer_part` 发布；模型先写一句前导语、
   随后又选择工具时，该前导语只留在 Agent transcript，不会在玩家界面闪现后撤回。
3. 一个有界 ReAct Agent 自主选择 allow-list 内的 typed tools。每次工具 observation 都回到同一个模型；模型可以
   继续选择真正有用的下一步，也可以直接用自然文字结束。应用不规定“必须两次”、不强制自然回复后再调用一次，
   也不在某次读取后把能力表收缩成固定流水线；模型调用和工具调用上限只承担防无限循环的安全职责。
4. catalog tool 提供身份已验证的游戏事实。公开资料发现把候选线索与有来源的公开事实作为一个原子结果返回；
   坏的候选、公开事实、研究 observation 或附带偏好 patch 只丢当前 item，合法 sibling 继续。公开搜索不能代替
   BGG 身份校验，重复同一 typed read 会被阻止；是否继续查目录、研究体验或结束，由 Agent 根据 observation 决定。
5. `recommend_games` 一次返回完整自然 `playerReply`，以及每张卡的 evidence-bound `why` 和可选 `tradeoff`。
   应用不再用 80 字 lead、12 字卡片说明或 exact selection count 充当安全边界：generic lead 可以没有 evidence ID，
   只要非空即可；候选身份、排除/硬条件、证据 allow-list 和同候选归属仍严格。坏的可选 tradeoff 只省略该字段，
   坏的单张卡只省略该卡并形成真实 shortfall，合法卡片和模型原文逐字发布。
6. provider、协议、输出长度、空响应、重复无效动作、预算、发布边界或服务失败都不会触发应用拼写成功回复，
   也不会只凭“已核验候选”生成卡片。本轮返回 typed `failureReason` 和玩家安全的具体说明；请求、偏好与已核验
   会话状态保留供重试。前端从持久会话恢复真实模型输出，选中真实发布的卡片后才创建规则书接力。
7. 会话把“最近完成回合”与“最近已发布回合”分开持久化：前者保留失败回合的精确幂等重放，后者负责刷新
   后恢复完整卡片、比较结构、原 locale 和 turn identity。`UNAVAILABLE` 不进入对话 transcript，也不覆盖
   上一次成功发布；第一轮就不可用时则保持没有已发布结果，不能根据 known games 猜造卡片。

推荐的“没有结果”不都叫失败：

| 条件 | 对玩家的结果 | 是否会丢失已确认会话 |
| --- | --- | --- |
| 目录中没有满足硬条件的候选 | 成功返回 `no_match`，说明最小可行调整 | 否 |
| 模型未配置、provider 连接失败、协议无法解析、输出截断或空响应 | 本轮不发布临时/模板成功结果；显示具体失败原因并保留请求 | 否 |
| 已有合法候选，但完整终态回复仍无法取得 | 同样返回 `UNAVAILABLE`；候选留在内部 checkpoint，不能冒充已发布卡片 | 否 |
| 六次 action 预算或整轮 deadline 用尽，仍没有终态 | 以明确的 action/time failure boundary 停止 | 否 |
| 一组并行动作没有逐步观察，或完全相同的无效动作再次出现 | 第一次作为 typed observation 交回模型；完全相同的重复才停止，避免无意义循环 | 否 |
| 单张卡、可选 tradeoff 或附带偏好 patch 无效 | 只丢局部坏 item；其余合法结果继续，卡片不足时显示 shortfall | 否 |
| 所有卡都选错身份、违反排除/硬条件，或引用未知/别的候选证据 | 当前 action 收到结构化拒绝；模型可根据 observation 另选动作或完整重交 | 否 |
| revision 冲突、并发 turn、checkpoint/最终持久化或任务排队失败 | 不把未提交结果显示成成功；从服务端会话恢复 | 否 |

`UNAVAILABLE` 的对外原因不是一条笼统“生成失败”，而是稳定区分为 `time_limit`、`model_not_configured`、
`provider_call_failed`、`provider_protocol_invalid`、`provider_output_truncated`、`empty_model_response`、
`repeated_incompatible_actions`、`repeated_invalid_action`、`action_budget_exhausted`、`publication_rejected` 和
`service_failure`。前端按这个精确原因解释发生了什么，同时把未知新原因回退到较宽的安全边界；任何一种都
不会把内部 checkpoint 或应用模板冒充成一次成功推荐。

read tool 的一次临时失败先作为 observation 交还 Agent，Agent 可以换工具、缩小目标或诚实结束；只有预算内仍
无法形成合法终态才使本轮失败。调用数只是实际决策路径的观测值，不是测试合同；验收关注是否重复相同读取、
是否在总预算内、是否发布有用且有归属的结果。公开库为空、规则书导入失败或讲解失败从来不是推荐失败条件。

### 2. 规则书取得与绑定

1. 玩家选中游戏后，系统才开始来源发现。
2. 官方来源必须通过 URL、内容类型、PDF 可读性、游戏/版本身份与来源归属校验；不能验证时要求玩家确认或上传。
3. 上传和官方导入最终都形成同一种文档版本，后台按页提取文字与图片，并持久化真实进度。
4. 已成功的来源、页面和版本不会因后续讲解失败而删除。

这里的失败主要是：没有可信来源、来源需要浏览器人工处理、下载/格式无效、版本身份不清、存储失败，
或任务被取消。恢复动作只针对来源或导入，不会重新运行推荐。

### 3. 讲解

1. 图片页直接用原图生成带页码身份的 V6 typed rule groups；文字页直接读取原文。系统不再先做一次 OCR、
   再把 OCR 文本喂给第二次语义模型。
2. V6 parser 只要求当前合同的必需字段、类型、页码身份和组间关系；模型多返回无关字段时直接忽略。真正的
   JSON、类型、必需字段、重复规则组或页码绑定错误只用同一张原图修正当前页一次。
3. preparation 先形成 typed canonical source ledger。普通体量由一次 compact outline 调用组织；密集长规则书在
   完整 slot 边界上分片，每片只分配 source ownership，最后一次 global ordering 只看到 unit ID、角色、页码、
   可用性和 source identifier，不再重读整本规则事实。每个 slot 和 teaching unit 都必须恰好归属一次。
4. preparation 的 model-call admission 按真实页数和合同结构最坏上界动态计算，不再用固定调用数卡死长书；
   动态预算只是上界，不会主动产生调用。纯图片规则书每页最多两次页面理解；page-owned canonical shard、
   global ordering 和一次 global ownership refinement 各自都包含 transport replay 与一次完整 replacement，
   所以 `P` 页 preparation 的完整调用上界是 `6P + 8`（20 页为 128，500 页为 3,008）。10 个并行 shard 只缩短
   独立页面的串行关键路径，不放宽该上界。不可读页面作为 typed unavailable catalog state 留痕并从 ownership
   排除；只要还有一个可验证 anchor 就继续，只有零 anchor 才在规划前停止。每次调用前还会以真实消息和工具
   schema 计算本地及全局上下文容量；若整页事实无论怎样分片都装不进 provider 合同，就在任何付费调用前拒绝。
   Teaching run 另外按实际章节、检索、review 和可选配图的完整有界调用图取得自己的执行预算。
5. outline 只描述章节结构；它不能凭空补规则，每个主题必须能回到当前文档版本。章节初稿若未通过确定性
   发布校验，Agent 会收到具体诊断和同一份证据，最多返回一次完整章节替换；应用不从旧稿拣字段拼进新稿。
6. 每章带引用正文一通过确定性边界就先写入 durable snapshot，随后才做可选图示增强；因此图示超时、预算停止、
   provider 或 crop 失败都不能擦除已经可读的正文。视觉 Agent 只能从 opaque crop candidate ID 中选择或返回
   `NO_VISUAL`；格式错误、越权选择或 provider 失败最多触发一次完整重选。crop 的临时 503 只自动重试一次，
   永久 502 或重试仍失败只省略该图，并在 UI 说明是容量、原页、传输还是浏览器解码边界。
7. 已通过引用与结构校验的章节可先读，后续章节继续后台完成。可选整课 reviewer 不可用时保留完整 cited
   draft；只有 confirmed defect 才把诊断和同一证据交给 Agent 生成一次完整章节替换，再做一次独立验收。
   confirmed defect 会先从可读 snapshot 中扣留，再开始 replacement；所以替换期间取消或预算中止也不会把
   已知有错的旧章继续标成可读。替换通过后只为新章节重新选图；再次不合格只扣留该章，不抹掉其他章节。
8. UI 展示后端真实 activity，不模拟百分比；关闭弹窗后任务继续，并能从“我的讲解/任务进度”找回。

讲解结果按最小可用单位判定：

| 发生什么 | 最终状态 | 已有内容 |
| --- | --- | --- |
| 某次页面/章节/视觉响应未通过合同，或某次 provider 调用失败 | 进入唯一 owner 的有限完整替换或 transient replay；这次活动不是整轮失败 | 全部保留 |
| 某页 V6 修正后仍失败、页面图片无法读取、某章证据不足 | 只把该页/章标为 unavailable；其他页和章节继续 | 全部保留 |
| 视觉 Agent 返回 `NO_VISUAL` | 合法的局部完成；该章无图发布 | 已校验正文保持不变 |
| 视觉完整重选仍失败、crop 无法生成、超时或容量满 | 只省略当前章图片 | 已校验正文保持不变 |
| 部分页不可用或 source catalog 不完整，但至少一章可读 | `DRAFT_READY` | 可读章节立即持久化并交付 |
| 来源整体不可用、零可验证 anchor 或最终没有任何章可发布 | `INCOMPLETE` / `INSUFFICIENT_EVIDENCE`，整轮停止 | 规则书、页面与已有章节仍保留 |
| preparation 在普通队列 2 分钟、纯图片长书队列 30 分钟内没有 worker 接手 | `TEACHING_PREPARATION_QUEUE_TIMEOUT`；尚未开始模型调用，可直接重试 | 规则书与之前的 durable 内容保留 |
| 已有 plan 的独立 Teaching 入口在 2 分钟内没有 worker 接手 | `TEACHING_QUEUE_TIMEOUT`；迟到 runnable 被抑制，尚未开始模型调用 | 规则书、plan 与之前的 durable 内容保留 |
| worker 已到达但 durable claim 在有限同 token 重放后仍失败 | `*_WORKER_ADMISSION_FAILED`；尚未开始模型调用，可直接重试 | 其他 worker 已有的 claim 不会被覆盖；规则书、plan 与 durable 内容保留 |
| 第一段带引用讲解已发布，但其余章节在 continuation 队列 30 分钟内没有 worker 或接管失败 | `TEACHING_CONTINUATION_QUEUE_TIMEOUT` / `TEACHING_CONTINUATION_ADMISSION_FAILED` | 第一段保持可读，玩家可以明确重试剩余工作 |
| plan/source 读取失败 | `TEACHING_PLAN_RETRIEVAL_FAILED` | 从原规则书发起新 run；已有 durable 章节保留 |
| 首章或旧式生成所需证据读取失败 | `TEACHING_EVIDENCE_RETRIEVAL_FAILED` | 复用已有章节后重新生成缺失内容 |
| 教学模型配置、provider 或模型合同失败 | `TEACHING_MODEL_PROVIDER_FAILED` | 复用已有章节后发起新 run；不把 provider 私有错误暴露给玩家 |
| lesson/progress/run state 持久化失败 | `TEACHING_PERSISTENCE_FAILED` | 从最后 durable snapshot 发起新 run |
| 后续章节失败 | `TEACHING_CONTINUATION_FAILED` | 已发布章节继续可读，只重启剩余工作 |
| 输入缺失、越权、过期或结构无效 | `TEACHING_PLAN_INVALID` | 不自动重试；玩家修复输入或重新选择规则书 |
| preparation 的精确本地/全局上下文预检不满足 provider 容量 | 在任何付费模型调用前停止并说明容量 owner | 规则书与之前的 durable 内容保留 |
| 用户取消/会话失效，或全局 step/tool/model/token/deadline 用尽 | 当前 durable run 停止 | 已确认页面和已发布章节保留 |
| 最终持久化、队列或服务在有限恢复后仍失败 | 当前 durable run 停止并显示具体 owner | 上游推荐、规则书和最后一次 durable snapshot 不回滚 |

所以“失败”分三层：一次 attempt 被拒只表示正在有限修正；修正用尽通常只产生局部缺页、缺章或缺图；只有
来源/anchor/最终可发布性、取消/身份、总预算/时限，以及持久化或服务恢复这些 whole-run owner 才能把整轮
标为停止。前端按真实 `run.state + lastErrorCode` 说明是哪一层，并明确已发布章节是否仍可读。

### 4. 规则答疑

1. 当前问题先进入绑定文档版本或公开讲解自己的确定性 retrieval。普通第二问、`previousQuestion`、
   `priorTurnReference` 和显式 learning intent 都不会无条件先调用 interpretation 模型；只有本轮检索确实为空、
   有前文且 provider 支持时，才用一次可选 interpretation 形成恢复候选，二次检索成功后再原子替换当前路径。
2. retrieval 返回带稳定 evidence ID 的片段；模型通过 typed tool 指明使用哪些证据和回答范围。ADVICE 或
   COMPLETE_LIST 的可选认证失败时保留已经验证的部分证据和未完成义务；CALCULATION 数值审计与精确前文页
   复核仍是硬边界。
3. 最终 provider JSON 的核心回答保持严格，教学辅助只使用一个 `aid: {type, payload}` discriminated union，
   不再同时要求 12 个互斥数组。非计算 aid 的 discriminator、payload 或旧字段损坏时只丢 aid，保留 core；
   计算 aid 不完整仍硬失败并走定向完整 replacement。
4. 发布前校验证据归属、页码、引用和硬数值；free-form prose 只用于玩家可见表达，不参与业务路由。初稿未通过
   发布边界时，应用把具体拒绝原因与同一份 typed evidence 交回回答 Agent，最多请求一次完整 replacement；
   禁止按字段打补丁，也禁止应用组合新旧文本。
5. 有支持的部分先回答；不支持的部分局部说明不确定，最多追问一个真正有用的问题。运行进度来自实际到达的
   execution phase，不再由终态倒推不存在的 retrieval/composition/critic；失败记录同时给出安全错误码和实际阶段。

答疑会在证据不足时局部弃答；引用不属于当前版本、完整 replacement 仍无效、服务预算耗尽或持久化失败时
本轮整体停止。已有讲解和推荐卡片都不会因此消失。

## 长任务、并发与恢复

- PostgreSQL 中的 durable state 是 import、assistant run、teaching plan、lesson 和 conversation 的权威来源。
- SSE 用于及时展示活动，轮询负责断线恢复；同一个 lifecycle 只能有一个 mutation/polling owner。讲解正文先写
  `TEACHING` durable snapshot，可选配图随后在同一个 run 内写更新 snapshot；历史 `VISUAL_ENRICHMENT` 只保留
  存量读取/删除兼容。
- revision、lease、fencing token、幂等键和 transactional outbox 防止重复消费、旧 worker 覆盖新状态或事务提交后丢事件。
- `RECEIVED` 只表示排队；普通 preparation 或已有 plan 的独立 Teaching 必须在 2 分钟、纯图片长书必须在独立
  有界队列的 30 分钟内被唯一 worker 原子领取，否则以可重试的 queue timeout 结束。后台公共课候选也有
  30 分钟领取边界。执行 deadline 从领取后开始，章节 continuation
  再次排队的等待时间不计入 active-work 预算；竞争失败的 worker 复用胜者，不会把“已有另一个 Teaching owner”
  误报为 preparation 失败。
- 首次 worker 领取把随机 `activation_id` 与 `activated_at` 一起写入 budget row。同一个 delivery 在事务响应
  丢失后用相同 token 重放时直接确认已有 claim，且不会再次延长 deadline；不同 token 必须退出。若数据库
  持续不可用，token-aware terminal write 只能结束未领取或属于自己的 RECEIVED run，不能杀掉另一个 worker。
  终态写入的临时失败由一个有容量和尝试次数上限、独立 scheduler 的 reconciliation owner 重试；耗尽后
  释放内存槽位并告警，进程重启后再由启动 recovery 统一结束遗留 non-terminal run，避免每个任务各自递归
  创建无限 timer。
- 普通 30 分钟/30 万 token 基线按已验证完整调用图等比例扩展，最终以 16 小时和 1,600 万 token 为 active-work
  硬上限。它们不是完成承诺，也不代替 provider 上下文限制、账户额度、并发和管理员 entitlement。
- 后端以 `FAILED + AGENT_CANCELLED` 保存取消事实时，玩家界面按 typed error code 映射成 `CANCELLED`，不会在
  同一条提示里同时说“生成失败”和“已取消”。
- 重试只重做最早失败的 owner，并复用已持久化的页面、证据与章节；停止任务不会顺带删除规则书。
- 可选 OCR、图示、review、localization 或公开可用性失败，必须 fail-open，不能擦除已通过确定性边界的内容。
  真正需要模型修正时只允许一份 complete replacement，并由原来的确定性边界重新验收。

## CI、部署与线上验证

交付也按失败 owner 分层，避免把所有红灯都叫成“部署失败”：

1. PR CI 只运行可重复、无付费模型的确定性检查。后端任务同时验证 Java 契约、最终 Docker runtime 中的
   OpenCV native 依赖，以及 `api` profile 下必须 eager 创建的真实生产组件；不能让 `test` profile 的组件排除
   或 `worker` profile 的 lazy initialization 掩盖 API 专属依赖图错误。前端、基础设施集成和 Playwright 用户
   旅程各自拥有独立 job。
2. 合并后部署先在无生产权限的 job 封存精确 SHA 源码，再在隔离 job 构建不可变镜像，最后才把校验过的
   release 和临时凭据交给部署 job。部署事务在切换前保存环境和旧 release，活跃 watchdog 覆盖整个激活窗口；
   新 checkpoint 持有同一部署锁时，至多恢复一个已经 armed 且 lease 过期的前序事务，fresh lease 即使带有
   watchdog failure 也拒绝接管，unarmed 状态则保持 fail closed。回滚守卫独占一个共享的六分钟恢复期限，读取
   Compose 已求值的唯一 loopback published endpoint，重新校验旧 API/worker/frontend 镜像、worker health、
   回环 Caddy 路由和 `current` 指针；它不再次解释 `.env` 端口，也不让 DNS、CDN、防火墙或备案边缘决定主机内
   回滚是否完成。失败诊断只输出容器状态、重启/OOM、退出码、错误与时间戳，不读取日志或环境变量。Compose、
   migration、候选容器健康、公开 availability 和 `Cache-Control: no-store` release identity 任一不满足都触发
   回滚；公网仍不可用是独立的边缘结果，不能覆盖已验证的旧 topology 恢复事实。`current` 只在 commit checkpoint
   后成为新 release。
3. 线上 canary 不再组成一个全有或全无的总门禁。推荐 canary 验证一次登录用户的新目录推荐、完整回复、
   每卡证据文案、持久化和页面逐字呈现，同时记录实际模型/工具调用图但不规定 exact 次数；门禁只要求没有
   完全相同的重复读取、没有超过 Agent 总安全预算，并在页面 SLO 内形成正确结果。provider 修复后只要仍满足
   硬边界就不应被调用次数误判为失败。随后选择其中一张卡，证明相同 BGG 身份被绑定到
   game/edition，并且只恢复
   该 edition 的既有旅程或在同 edition discovery 边界停下。首个进度、持久化终态与页面渲染分别记录延迟。
   普通用户 canary 使用私有上传
   验证规则书、动态 preparation、正文先持久化后在同一 `TEACHING` run 内可选追加的局部图示、局部
   unavailable 页面和引用答疑，并在结束
   后清理测试数据。
4. canary 的 sanitizer 总会先删除原始输出和凭据并生成有界诊断 artifact；独立 final success gate 再要求
   `completed`/`SUCCEEDED` 的完整验收合同。测试被 skip、报告不完整或 sanitizer 只成功保存失败诊断时，workflow
   仍然必须红，不能把“有诊断可下载”误当成产品链路成功。生产 SSH 只出现在固定 bootstrap 边界；repo-owned
   canary 代码运行前必须删掉 key 并通过 `env -i` 只接收其最小玩家或管理员权限。

如果 GitHub 没有送达 `main` 的 push CI 事件，恢复动作是手工触发同一份 `CI` workflow；只有该 main SHA
的 CI 成功，`workflow_run` 才能进入部署。部署 workflow 本身没有直接手工入口，因而恢复不会绕过测试。

因此失败含义是可定位的：CI 红表示代码或运行镜像不满足确定性合同；deploy 红表示该 SHA 未能安全激活；
推荐 canary 红只说明推荐结果；规则书 canary 红只说明其最后到达的 acquisition、preparation、lesson 或
Q&A 阶段，章节视觉作为 lesson 内部的可选局部结果单列计数。provider 变慢会单独记录为延迟超标，不再把
已经生成的正确结果改写成“功能失败”。

## AI 安全与质量边界

模型输出默认不可信。自然语言只作为展示结果；意图、实体、偏好、数量、证据、工具和流程状态必须通过
typed JSON 参数返回，并校验 schema、范围、实体身份、证据归属和所有权。任何规则结论没有证据就不能发布。
外部目录的 `playingtime`、`minplaytime`、`maxplaytime` 非正值在 catalog 边界统一归为未知；selector 对历史
遗留的非正时长也只能生成 `UNKNOWN` claim，不能用“0 分钟”证明满足玩家的硬时长上限。

普通路径让同一个 Agent 在每个真实 observation 后判断继续还是结束；测试不把 exact 模型调用数或固定阶段顺序
写成产品合同。调用、工具、token 与 deadline 仍有安全上限，重复完全相同的读取或无效动作会被阻止。新增阶段
必须拥有独立、可测量的责任；critic、视觉或本地化失败时不得销毁已验证内容。任何 correction 都返回完整对象，
禁止字段级 patch 或应用拼接。真实语料事故只转成脱敏评测，不把游戏名、页码、同义词或某次输出写成生产
special case。

推荐 Agent 的设计参考了 [ReAct](https://arxiv.org/abs/2210.03629)、
[OpenAI Agents](https://openai.github.io/openai-agents-python/running_agents/)、
[LangGraph](https://docs.langchain.com/oss/python/langgraph/event-streaming) 和
[PydanticAI](https://pydantic.dev/docs/ai/core-concepts/agent/) 的当前运行模型：它们的共同核心
都是“模型选择动作 → typed tool observation → 在预算内继续或结束”，并把流式事件和最终输出分开。RulePilot
已经用 Spring AI、typed tools、durable conversation 和确定性发布边界实现这个核心，因此没有再引入一套
Agent framework；新框架会复制状态、重试和观测 owner，却不会自动提高推荐质量。这里保留的是 Agent 的
自主工具选择，框死的是工具参数、身份、证据、预算和最终提交，而不是自然语言或固定游戏名。

## 这次重构解决了什么

### 问题一：推荐被公开讲解库绑架

旧实现会在推荐阶段查询某款游戏有没有公开讲解，再维护 continuation、learning goal、超时和排序。
这把“挑游戏”和“有没有现成教程”变成同一个失败面。重构删除整套协议：推荐只返回游戏；玩家选中后，
规则书接力独立决定使用公开来源还是上传。结果是更少一次外部查找、更少状态字段，也消除了公开库故障
导致推荐降级的可能。

### 问题二：同一个视觉任务有多个 owner

规则书接力和讲解弹窗曾同时轮询、settle、恢复同一 `VISUAL_ENRICHMENT` run，页面会出现互相矛盾的
状态。现在独立视觉 workflow 已退休：每章正文校验后先写入教学 snapshot，再在同一个 `TEACHING` run 内
可选选图并更新该 snapshot；弹窗和阅读器不再发现、轮询或猜测第二个视觉任务。历史枚举和存量删除路径只为
数据库兼容存在，不能创建新任务。

### 问题三：旧协议删除不完整

历史 progressive start、prompt 版本、DTO、截图开关和 paid canary 继续被测试维持，即使
生产已经没有调用者。重构按“发出者、消费者、状态、配置、prompt、文案、fixture、canary 一起退休”
删除完整责任，而不是再加 feature flag。

### 问题四：可选能力变成主链门禁

旧复合 canary 把推荐、公开规则书、讲解、答疑和首屏文案串成一次 pass/fail，无法说明是哪条产品能力
坏了。现在推荐 canary 只验证推荐持久化和卡片；规则书→讲解→答疑由普通用户 canary 独立验证。
20 秒交互 SLO 与“有没有功能结果”使用独立断言和分段耗时，虽然任一不满足都会让 canary 变红，artifact
仍能明确区分是慢、调用图漂移还是根本没有功能结果。

### 问题五：测试数量替代了测试理论

旧测试大量重复 mock choreography、内部 transition、prompt 文本形状和已退休协议。现在只保留四类风险：

1. 领域不变量：纯值和规则。
2. 应用契约：一个用例的发布与恢复语义。
3. adapter / integration：HTTP、数据库、并发、provider 或基础设施边界。
4. journey / canary：一个可独立恢复的用户结果和生产接线。

一个风险只在最早稳定边界拥有一组主测试；更高层只验证接线，不重复所有排列。测试如果说不出独立的
生产失败，或只服务已经删除的协议，就一起退休。Testcontainers 仍保留，因为它们真实覆盖 PostgreSQL、
Redis、migration、lease、fencing、并发与幂等，而不是为了数字好看。

### 问题六：文档和规则本身也变成负担

过去的 roadmap、learning card、矩阵和事件记录把历史判断伪装成当前产品事实。现在仓库只保留三个入口：
`README.md`（如何运行）、`AGENTS.md`（如何安全修改）和本文件（系统为何这样工作）。旧资料移到仓库外
可恢复归档，不再参与构建或质量门禁。

### 问题七：OpenCV 到底有没有帮助从未被验证

OpenCV 现在只有一个职责：从本地页面像素中提出有限个矩形候选，不做 OCR、不返回规则文本，也不决定
哪张图可以发布。真实规则书页面实验覆盖了组件清单、规则示例、正文、词汇表和署名页：组件页与图解规则页
能稳定提出有用候选，纯署名页能够放弃，但正文页仍会产生标题、段落和重复小图，说明它是高召回、低精度
候选器，不能单独生成可用讲解。

随后把四张真实 OpenCV crop 交给当前视觉模型，只允许选择 opaque candidate ID。一次模型调用在 8.224 秒
内正确选中两张能支持讲解 claim 的候选，排除了两张干扰候选，并对图片无法证明的精确总分返回
`NO_VISUAL`；响应通过六字段 schema，且没有输出坐标。这个结果支持保留 OpenCV，但边界是“OpenCV 提候选，
模型按 claim 选择，应用校验证据后发布”；任一步失败都只是不加图，不影响已发布的引用文字讲解。

### 问题八：桌游库封面全部消失

目录 API 一直返回了正确 `thumbnailUrl`，原图端点也正常；真正的线上根因是 Spring Security 只匿名放行了
`/image`，缩略图 `/thumbnail` 被统一登录规则拦成 `401`。修复只精确放行匿名目录缩略图，没有扩大到文档、
上传、推荐、导入或其他私有接口；同一个 security integration test 同时验证缩略图 `200` 和私有文档
GET/POST 仍为 `401`。这类故障应先比较 API 数据、两个资源端点和鉴权状态，不能用前端占位图掩盖。

## 测试与生产健康基线

2026-08-29 的统一发布基线如下。这里同时区分声明方法和参数化展开后的用例，不把数字本身当成绩：

| 检查 | 精简前 | 上轮基线 | 当前 |
| --- | ---: | ---: | ---: |
| 后端展开用例 | 1,937 | 1,689 | 1,728 |
| 后端声明方法 / JUnit 类 | 未记录 | 1,676 / 306 | 1,703 / 305 |
| 后端测试源码文件 / 行 | 349 / 87,776 | 312 / 67,153 | 311 / 67,686 |
| Vitest 文件 / 展开用例 | 103 / 791 | 92 / 731 | 87 / 674 |
| Playwright 文件 / 用例 | 27 / 92 | 18 / 64 | 18 / 64 |
| 默认脚本测试 | 22 文件 | 4 文件 / 32 条 | 3 文件 / 62 条 |
| Prompt 资源 | 217 | 72 | 70 |
| `make verify` 顶层命令 | 29 | 7 | 7 |
| 工作流 | 6 个 / 2,549 行 | 5 个 / 1,440 行 | 5 个 / 3,439 行 |

当前后端 1,728 条是 1,695 个普通 `@Test` 加 8 个参数化方法展开的 33 次 invocation。后端没有永久
`@Disabled`：70 个 Testcontainers 用例只在当前本机门禁的外部依赖条件不满足时跳过、在 Docker CI 执行；
4 个付费模型 canary 和 1 个真实来源评测只由命名的 opt-in 命令触发；3 个 PDF 测试在本机检测到 Poppler 后
正常执行。
默认脚本测试当前为 49 pass、13 个 Linux-only 发布事务语义 skip、0 fail。

本轮在补齐队列接管、终态恢复、总截止时间、生产验收合同和前端失败语义后，相对上轮仍净删 6 个测试文件，
展开用例净减 18 条；后端测试源码增加 533 行，用于覆盖新持久化与恢复边界。只计算有完整历史计数的后端、
Vitest 和 Playwright，展开用例从 2,820 降到 2,466，累计减少 354 条；测试文件从 479 个降到 416 个，
累计减少 63 个。

重构前最近 20 条 GitHub 记录显示：核心 CI 为 17 成功、0 失败、1 取消、2 条旧排队；部署为 17 成功、
1 失败、1 取消、1 条旧排队。真正的高红灯来自把外部 provider 和多段产品结果串联的手工生产巡检：旧普通
用户复合巡检只有 3/20 成功，旧“推荐”巡检 0/20 成功，但后者同时包含规则书、讲解和答疑，不能证明推荐
本身失败。现在推荐和私有规则书链路分别出结果、分别报告最后成功阶段和可恢复动作，新发布以两条独立
canary 重新建立生产基线。

从精简开始前的主线版本到本基线，整体净删除超过 5 万行；删除量主要来自历史 prompt、重复测试、旧媒体
能力、渐进式协议、复合巡检和不再参与构建的文档，而不是把断言换成更宽松的断言。保留测试仍覆盖身份、
所有权、证据与引用、预算、持久化、事务、并发、幂等、恢复、发布和部署回滚这些独立生产风险。

## 面试时可以怎样讲

推荐的两分钟结构是：

1. 先说产品：这是一个“有证据的桌游推荐、讲解和答疑助手”，不是普通聊天机器人。
2. 再说难点：PDF/图片证据、长任务恢复、模型不可信、并发 worker，以及多个结果不能互相拖死。
3. 再说设计：Spring Modulith 划业务边界，typed tool + deterministic publication boundary 管模型，
   durable run + SSE/轮询管恢复，outbox/lease/fencing 管可靠异步。
4. 用一次失败重构证明工程判断：发现推荐错误依赖公开库和复合 canary，于是删掉跨域 continuation、
   重复视觉 owner、历史协议和重复测试，把线上验证拆成独立结果。
5. 最后主动讲权衡：模块化单体减少部署成本；视觉增强 fail-open；不是所有 provider canary 都放进 CI；
   历史数据库兼容只有在确认线上存量为零后才删除。

如果面试官追问“为什么不微服务”，答案是：当前团队和负载更需要短反馈、强事务和容易重构；模块公开接口、
ArchUnit/Modulith 边界和 outbox 已经提供足够隔离，拆服务只会提前引入分布式事务与运维成本。

如果追问“怎么防幻觉”，答案不是“再加一个模型审稿人”，而是：检索结果有稳定 evidence ID；模型只能
通过 typed tool 选择证据；发布时校验版本归属、页码、引用和硬事实；支持部分可以发布，不支持部分局部
弃答；可选 reviewer 不能推翻已通过的确定性边界。

## 当前限制

- 外部来源和模型仍会受网络、限流与 provider 协议漂移影响，所以真实 canary 与普通 CI 分开运行。
- 推荐 adapter 在 wire 层使用 `tool_choice=auto`：模型可以选择 typed tool，也可以用自然文字直接结束；应用
  不为零工具自然回复强制追加一次模型调用。空回复、未知工具、非法 schema 和重复不兼容动作仍会按具体
  `failureReason` 停止；多种互不兼容的并行动作第一次作为 observation 交回 Agent，只有完全相同的重复才终止。
  推荐与其公开资料搜索独立使用 `qwen3.8-flash`，其他共享 Qwen 角色继续使用 `qwen3.7-plus`；模型配置
  以一份原子 release 环境发布，避免为推荐提速时无意改变讲解或答疑。单次线上 canary 只能证明一个样本及其
  延迟，不能推导总体失败率。
- 页面视觉理解是可选增强，复杂图表可能只保留原页而没有可靠 crop；系统选择诚实降级，不伪造图示。
- 跨页、跨版本的规则冲突仍需要更强的证据关联评测；不能靠生产词表或样例 special case 修补。
- 历史兼容分支只有在生产存量有测量证据并设定退出事件时才允许存在，不能用“可能还有数据”永久保留双路径。
