# RulePilot

[![CI](https://github.com/ZhenFan1996/rulepilot/actions/workflows/ci.yml/badge.svg)](https://github.com/ZhenFan1996/rulepilot/actions/workflows/ci.yml)

RulePilot 是一个证据优先的桌游助手：根据玩家偏好推荐游戏；玩家选中后绑定可信规则书；再生成带页码
依据的上桌讲解，并围绕当前规则书回答追问。推荐、规则书、讲解和答疑是连续体验，也是可分别成功与恢复
的四个结果。

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

载入项目自制的 CC0 演示规则书：

```sh
make demo-data
```

停止开发进程和本地基础设施：

```sh
make dev-stop
make compose-down
```

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
