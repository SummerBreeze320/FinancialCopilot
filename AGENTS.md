# FinancialCopilot agent guide

## Scope

FinancialCopilot is a Java 21, Maven multi-module financial-research platform. The production application is Spring Boot 3.3.3 / WebFlux; the current deep domain is public funds, with stock, futures, and wealth-management extension points.

Keep each change within the narrowest layer that owns the behavior:

- `copilot-common`: cross-module DTOs, enums, events, and result types.
- `copilot-domain`: business models, ports, and asset-domain strategies; no framework or persistence implementation.
- `copilot-math-core`: deterministic, pure-Java financial calculations.
- `copilot-data-engine`: persistence adapters, MyBatis-Plus mappers/POs, and database integration.
- `copilot-agent-tools`: tool-facing adapters and tool implementations.
- `copilot-agent-core`: workflow, DAG runtime, planning, agent coordination, and platform services.
- `copilot-app`: Spring Boot wiring, controllers, WebFlux/SSE endpoints, and configuration.
- `copilot-mcp-service`: separately runnable Python FastMCP 2024-11-05 tool discovery and execution service (port 8085). Use its `pyproject.toml` and tests when changing it.
- `copilot-ner-service`: separately runnable Python entity-recognition service (port 8800). Use its `pyproject.toml` and tests when changing it.
- `copilot-memory-service`: separately runnable Python Graphiti temporal knowledge-graph and memory service (port 8700).

Preserve the dependency direction: application and adapters depend inward on domain/common; domain and math remain independent of Spring, MyBatis, and transport concerns.

## Mandatory project rules

Before editing Java or Python application code, read and follow [.agents/rules/coding-standards.md](.agents/rules/coding-standards.md). It is the authoritative source for Lombok, MyBatis-Plus, Chinese Javadoc, import hygiene, and asset-domain package rules.

For financial metrics, use the existing deterministic calculation and data-tool path. Treat tool output as the source of truth; do not add model-derived arithmetic for returns, drawdown, Sharpe, Calmar, or similar quantitative values.

Keep user and conversation data isolated by the established authenticated-user ownership checks. Do not weaken 404/503 behavior around unavailable or unauthorized resources.

## Validation

Run the narrowest relevant test first, then expand when the change crosses modules:

```powershell
mvn -s maven-settings.xml -pl <module> test
mvn -s maven-settings.xml test "-Dcopilot.neo4j.enabled=false"
```

The full integration suite requires local PostgreSQL and Redis and is opt-in:

```powershell
mvn -s maven-settings.xml test "-Dcopilot.integration=true"
```

Do not run service-dependent integration tests unless their required services are available. For database changes, update the applicable Docker initialization SQL and persistence tests together. For API or SSE changes, preserve the single `FinancialResearchWorkflow.run(...)` entry path.

## Working-tree discipline

This repository may contain active, unrelated changes. Inspect `git status` before editing, preserve those changes, and limit modifications to files needed for the requested work. Do not commit, reset, or discard work unless explicitly requested.

---

## FinancialStrategy AI 策略平台前后端启动命令指南

项目目录位于 `d:\BaiduSyncdisk\IdeaProjects\FinancialStrategy`。包含模型驱动服务 (Codex Bridge)、Java 策略研报与回测后端、React 前端。

### 1. 一键全链路启动 (推荐)

在 PowerShell 中执行项目根目录提供的一键启动脚本：

```powershell
cd d:\BaiduSyncdisk\IdeaProjects\FinancialStrategy
.\start-dev.ps1
```

该脚本会自动并行拉起 `codex-bridge` (8000)、`Spring Boot 后端` (8080) 与 `Vite 前端` (5173)，并自动检测端口健康就绪状态。

### 2. 手动/独立终端分别启动命令

如果需要在独立终端中分别调试运行，可执行以下命令：

#### 终端 1：本地 Agy 模型驱动服务 (Codex Bridge)
```powershell
cd d:\BaiduSyncdisk\IdeaProjects\FinancialStrategy\codex-bridge
$env:CODEX_BRIDGE_BACKEND = "agy"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```
- **监听端口**：`http://127.0.0.1:8000`
- **健康检查**：`curl http://127.0.0.1:8000/health`
- **驱动说明**：桥接本地安装的 `agy.exe` (Gemini Flash/Pro)，后端已实现离线安全降级。

#### 终端 2：Java 策略研报与回测后端 (Spring Boot 3.4.5)
```powershell
cd d:\BaiduSyncdisk\IdeaProjects\FinancialStrategy
mvn spring-boot:run
```
- **监听端口**：`http://127.0.0.1:8080`
- **API 健康检查**：`curl http://127.0.0.1:8080/api/research/sessions`
- **底层特性**：AgentScope ReAct Agent、结构化回测代码生成与进度轮询、本地 PMS 模拟网关、Alert 告警补偿。

#### 终端 3：前端交互界面 (React + Vite)
```powershell
cd d:\BaiduSyncdisk\IdeaProjects\FinancialStrategy\frontend
npm run dev
```
- **访问地址**：`http://localhost:5173`
- **代理配置**：已自动将 `/api` 请求反向代理至 `http://127.0.0.1:8080`。

### 3. 服务拓扑与端口对照

| 服务组件 | 运行目录 | 默认端口 | 职责与功能 |
| :--- | :--- | :--- | :--- |
| **Frontend** | `FinancialStrategy/frontend` | `5173` | React 策略投研交互台、多轮对话、回测进度监控、绩效报告展示 |
| **Backend** | `FinancialStrategy` | `8080` | Spring Boot 核心服务、A2A 协议编排、Python回测脚本模板生成、PMS 持仓模拟 |
| **Codex Bridge** | `FinancialStrategy/codex-bridge` | `8000` | 本地 `agy` LLM OpenAI 兼容协议适配桥 |

