# copilot-mcp-server 独立模块实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个独立的 `copilot-mcp-server` Maven 子模块，提供符合 Anthropic MCP 2024-11-05 规范的标准协议支持、基于 WebFlux 的 SSE 响应式传输通道、现存金融量化工具自动注册绑定，以及基于 YAML 的动态外部工具扩展能力。

**Architecture:** 作为独立子模块运行在独立端口（默认 8085），基于 Spring Boot 3.3.3 + WebFlux (Netty) 实现，向下复用 `copilot-agent-tools` 权威计算，向上提供标准 `/sse` 与 `/messages` 端点，支持 AgentScope、Claude Desktop、Cursor 及外部微服务统一发现与调用。

**Tech Stack:** Java 21, Spring Boot 3.3.3, Spring WebFlux, Project Reactor, Jackson 2.17.2, Lombok.

---

## 全局开发约束与规范
- **代码规范**：严格遵循 `.agents/rules/coding-standards.md`，使用 Lombok，文件顶部规范 import，全类全方法中文 Javadoc。
- **端口隔离**：默认运行端口 `8085`，与主平台 `8080`、Codex Bridge `9090` 严格隔离。
- **非阻塞原则**：网络传输与消息派发全链路基于 Project Reactor (`Mono`/`Flux`)，严禁阻塞。

---

## 模块结构一览

```
copilot-mcp-server/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/financial/copilot/mcp/
    │   │   ├── McpServerApplication.java
    │   │   ├── config/
    │   │   │   └── McpServerProperties.java
    │   │   ├── protocol/
    │   │   │   ├── McpJsonRpcRequest.java
    │   │   │   ├── McpJsonRpcResponse.java
    │   │   │   ├── McpJsonRpcError.java
    │   │   │   ├── McpMethod.java
    │   │   │   ├── McpInitializeResult.java
    │   │   │   ├── McpToolDefinition.java
    │   │   │   ├── McpToolCallRequest.java
    │   │   │   ├── McpToolCallResult.java
    │   │   │   └── McpContent.java
    │   │   ├── registry/
    │   │   │   ├── McpToolHandler.java
    │   │   │   ├── McpToolRegistry.java
    │   │   │   ├── DynamicToolConfig.java
    │   │   │   └── DynamicHttpToolHandler.java
    │   │   ├── binding/
    │   │   │   └── FinancialToolsBinder.java
    │   │   ├── service/
    │   │   │   └── McpServerService.java
    │   │   └── transport/
    │   │       ├── McpSessionManager.java
    │   │       └── McpSseController.java
    │   └── resources/
    │       └── application.yml
    └── test/
        ├── java/com/financial/copilot/mcp/
        │   ├── protocol/McpProtocolSerializationTest.java
        │   ├── registry/McpToolRegistryTest.java
        │   └── transport/McpSseControllerTest.java
        └── resources/
            └── application-test.yml
```

---

### Task 1: 创建 `copilot-mcp-server` 模块与 Maven POM 依赖编排

**Files:**
- Modify: `pom.xml` (根 POM 注册 module 与 dependencyManagement)
- Create: `copilot-mcp-server/pom.xml`

**Interfaces:**
- 引入 `copilot-common`, `copilot-agent-tools`, `copilot-data-engine`, `spring-boot-starter-webflux`, `jackson-databind`, `lombok`, `reactor-test`.

- [x] **Step 1: 在根 `pom.xml` 注册 `copilot-mcp-server`**
- [x] **Step 2: 创建 `copilot-mcp-server/pom.xml`**
- [x] **Step 3: 运行 `mvn -s maven-settings.xml -pl copilot-mcp-server compile` 验证构建通过**

---

### Task 2: 实现 MCP 核心 JSON-RPC 2.0 与协议模型

**Files:**
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/protocol/McpJsonRpcRequest.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/protocol/McpJsonRpcResponse.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/protocol/McpJsonRpcError.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/protocol/McpMethod.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/protocol/McpInitializeResult.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/protocol/McpToolDefinition.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/protocol/McpToolCallRequest.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/protocol/McpToolCallResult.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/protocol/McpContent.java`
- Test: `copilot-mcp-server/src/test/java/com/financial/copilot/mcp/protocol/McpProtocolSerializationTest.java`

- [x] **Step 1: 编写序列化与反序列化测试用例**
- [x] **Step 2: 实现 JSON-RPC 2.0 基础请求、响应及错误模型**
- [x] **Step 3: 实现 MCP 工具定义（ToolDefinition）与工具调用模型（ToolCallResult）**
- [x] **Step 4: 运行协议模型测试并确保通过**

---

### Task 3: 实现工具注册中心与 YAML 动态工具加载器

**Files:**
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/registry/McpToolHandler.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/registry/McpToolRegistry.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/registry/DynamicToolConfig.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/registry/DynamicHttpToolHandler.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/config/McpServerProperties.java`
- Test: `copilot-mcp-server/src/test/java/com/financial/copilot/mcp/registry/McpToolRegistryTest.java`

- [x] **Step 1: 编写工具注册与动态 HTTP 转发调用测试用例**
- [x] **Step 2: 实现 `McpToolHandler` 抽象与线程安全 `McpToolRegistry`**
- [x] **Step 3: 实现 `DynamicHttpToolHandler`，支持在 YAML 中声明式配置第三方 HTTP 工具**
- [x] **Step 4: 实现 `McpServerProperties` 配置绑定与自动装配**
- [x] **Step 5: 运行工具注册中心测试并确保通过**

---

### Task 4: 现存金融量化工具自动绑定与 JSON Schema 生成

**Files:**
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/binding/FinancialToolsBinder.java`
- Test: `copilot-mcp-server/src/test/java/com/financial/copilot/mcp/binding/FinancialToolsBinderTest.java`

- [x] **Step 1: 编写金融工具绑定与 Schema 校验测试用例**
- [x] **Step 2: 实现 `FinancialToolsBinder`，挂载基金量化指标、基金筛选、持仓检索、股票技术分析等核心工具**
- [x] **Step 3: 为各内置工具生成标准的 JSON Schema（符合 Draft-07 规范）**
- [x] **Step 4: 运行绑定测试，验证入参校验与工具执行正确性**

---

### Task 5: 构建 WebFlux SSE 传输通道与独立微服务主程序

**Files:**
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/service/McpServerService.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/transport/McpSessionManager.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/transport/McpSseController.java`
- Create: `copilot-mcp-server/src/main/java/com/financial/copilot/mcp/McpServerApplication.java`
- Create: `copilot-mcp-server/src/main/resources/application.yml`
- Test: `copilot-mcp-server/src/test/java/com/financial/copilot/mcp/transport/McpSseControllerTest.java`

- [x] **Step 1: 编写 WebTestClient SSE 与 JSON-RPC 全链路端到端集成测试**
- [x] **Step 2: 实现 `McpServerService` 分发 JSON-RPC 请求 (`initialize`, `tools/list`, `tools/call`, `ping`)**
- [x] **Step 3: 实现 `McpSessionManager` 会话管理与 `McpSseController` 传输端点 (`/sse`, `/messages`)**
- [x] **Step 4: 配置 `application.yml`（默认端口 8085，包含动态工具示例）与 `McpServerApplication`**
- [x] **Step 5: 运行完整模块测试，验证端到端测试 100% 通过**
