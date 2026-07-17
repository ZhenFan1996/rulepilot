# RulePilot

RulePilot 是一个面向桌游规则书的规则讲解与实时答疑应用。它以游戏版本、扩展范围和规则页码为上下文，帮助玩家快速获得可核对的规则答案。

## 技术栈

- 后端：Java 21、Spring Boot 4.1、Maven Wrapper
- 前端：Vue 3、TypeScript、Vite、Tailwind CSS
- 测试：JUnit、Spring MVC Test、Vitest、Vue Test Utils

## 本地运行

准备本地配置：

```sh
cp .env.example .env
```

同时启动后端和前端：

```sh
make dev
```

也可以分别启动：

```sh
cd backend
./mvnw spring-boot:run
```

```sh
cd frontend
npm install
npm run dev
```

默认入口：

- 前端：http://127.0.0.1:5173
- 后端健康检查：http://127.0.0.1:8080/actuator/health

## 常用命令

```sh
make help
make bootstrap
make backend-test
make frontend-test
make verify
```

`make verify` 会检查仓库结构，并执行后端与前端的完整验证流程。

## 仓库结构

```text
backend/    Spring Boot 后端
frontend/   Vue 前端
infra/      本地基础设施配置
scripts/    仓库验证脚本
```

请勿提交真实凭证、用户上传内容、构建产物或未经授权的商业桌游规则书。
