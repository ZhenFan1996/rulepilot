# RulePilot

[![CI](https://github.com/ZhenFan1996/rulepilot/actions/workflows/ci.yml/badge.svg)](https://github.com/ZhenFan1996/rulepilot/actions/workflows/ci.yml)

RulePilot 是一个证据优先的桌游助手：根据玩家偏好推荐游戏；玩家选中后绑定可信规则书；再生成带页码
依据的上桌讲解，并围绕当前规则书回答追问。推荐、规则书、讲解和答疑是连续体验，也是可分别成功与恢复
的四个结果。

讲解先读取规则，章节 Agent 直接查看原页和版面提取的插图候选，在同一次生成中编排有序段落、图片组和可选图注。前端按编排呈现并适配屏幕，已有讲解继续可读。图表中需要推断的图形、数值对应关系保留为原图，
不作为确定规则转述；讲解会保留可读的文字规则并明确需要核对的局部细节。识图或配图失败不应丢失
已生成的有效章节。未完成的讲解仅供本人继续或恢复，完成后才进入公开讲解。

项目逻辑、生产架构、技术选型、失败边界和面试讲法见
[PROJECT_MODEL.md](PROJECT_MODEL.md)。工程修改约束见 [AGENTS.md](AGENTS.md)。

## 技术栈

Java 21、Spring Boot 4、Spring Modulith、Spring AI、Vue 3、TypeScript、Vite、PostgreSQL/pgvector、
Redis、RabbitMQ、MinIO、OpenTelemetry、Prometheus、Tempo、Grafana、JUnit/Testcontainers、Vitest 和
Playwright。

## 本地启动

需要 Java 21、Node.js 24、Docker、Docker Compose 和 GNU Make。

```sh
git clone git@github.com:ZhenFan1996/rulepilot.git
cd rulepilot
cp .env.example .env
make compose-up
make dev
```

访问 Web：<http://127.0.0.1:5173>；后端健康检查：<http://127.0.0.1:8080/actuator/health>；Grafana：
<http://127.0.0.1:3000>。默认使用确定性 Fake Provider，不调用付费模型。启用真实 Provider 时，本地启动器会在
旧 `.env` 尚未声明 `RULEPILOT_MODELS_STARTUP_ALLOWED_USERS` 的情况下，仅向已配置的本地玩家与演示管理员账号
开放启动凭证；显式设置（包括留空）始终优先。真实凭证只放 `.env`，不得提交。
部署环境另为公开答疑的 `public-reader` 系统身份启用启动模型配置。该身份禁止登录、没有用户权限，
模型用量仍由独立账户配额约束；本地需要验证公开答疑时，将它显式加入上述名单。
同步答疑的累计 token 估算仅用于统计，不截断已读证据后的回答；请求统一时限、账户配额、Provider 限制、
取消和无进展检测共同约束运行。答疑可按已观察的引用读取原始规则页，核对图表关系；无法确认的部分
保留原图并明确不确定性。发布校验确认来源身份和引用范围，不以数字是否出现在文字摘要中判定答案。
答疑检索使用当前模型输入容量扣除已占用上下文后的空间，不沿用固定的工具默认窗口；已确认的
Qwen 3.8 Max 输入容量来自提供方文档，其他自定义模型由提供方执行上下文边界。累计用量遥测
不充当回答完成门槛。同批部分证据不可用时仍交回已取得的证据；实际资源耗尽与请求超时分别报告。

部署镜像内置 Apache-2.0 的 Docling Heron 版面模型及本地 ONNX runtime，不向布局服务上传规则书。
首次配图时建立版本绑定的图片索引，后续章节复用；候选边界由布局模型提供，章节 Agent 只选择固定图片 ID。
编号同时标在模型输入图片上，读者裁图保持原样；Qwen 3.8 章节生成使用低强度思考以联合理解规则和选图。
`MODEL_REQUEST_TIMEOUT` 默认 4 分钟，容纳多页图文调用；各 Agent 的整体时限、取消与账户配额仍独立生效。
本机直接运行 Java 时，可用 Python 3.10+ 创建虚拟环境，安装 `backend/requirements-layout.txt`，再运行
`backend/install-layout-model.py <模型目录>`，通过 `DOCLING_PYTHON` 和 `DOCLING_MODEL_DIRECTORY` 指向它们。
未配置布局运行时仍可读取原页和生成有依据的正文；原页不会自动作为插图填入每个步骤。

载入项目自制的 CC0 演示规则书：

```sh
make demo-data
```

停止开发进程和本地基础设施：

```sh
make dev-stop
make compose-down
```

MinIO 与 `mc` 从固定的官方提交构建，版本由 `infra/minio/Dockerfile` 声明；镜像内保留对应源码归档与
许可证。Compose 保留原镜像别名，但使用本地构建，不再拉取已不可用的上游镜像。生产应用部署只检查
现有存储，不会替换它；更新存储镜像须单独维护，先验证对象读写并保留原镜像和数据卷。

## 常用命令

| 命令 | 用途 |
| --- | --- |
| `make help` | 查看全部命令 |
| `make bootstrap` | 检查仓库基础结构 |
| `make backend-test` | 后端单元、应用与集成契约 |
| `make frontend-test` | TypeScript、lint、Vitest 与 build |
| `make e2e` | Playwright 用户旅程 |
| `make verify` | 合并前统一验证 |

```text
backend/    Spring Modulith 模块化单体
frontend/   Vue 3 Web / PWA
infra/      Compose 与可观测性配置
scripts/    开发、验证、评测和生产操作
examples/   自制演示与评测数据
```

不要提交凭证、`.env`、用户上传内容、真实商业规则书或生成模型原始数据。
