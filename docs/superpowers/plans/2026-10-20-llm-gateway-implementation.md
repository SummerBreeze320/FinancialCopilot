# 统一大模型网关 (copilot-llm-gateway) 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建独立的 Maven 模块 `copilot-llm-gateway`，抽取并统管模型多厂商接入、凭据安全、多级场景路由、故障自愈降级 (Failover)、响应式并发限流与全链路审计，向业务侧提供单一门面入口 `LlmGatewayClient`。

**Architecture:** 
1. 模块分级：`copilot-common` -> `copilot-llm-gateway` -> `copilot-agent-core` -> `copilot-app`；
2. 进程内零网络损耗调用，默认且优先使用本地独立运行的 `codex-bridge` (`http://127.0.0.1:9090/v1`, `gemini-3.8-flash-low`)；
3. 基于 `LlmRouter` 与 `LlmFailoverExecutor` 实现多场景路由 (`FAST`, `STANDARD`, `REASONING`, `QUANT`, `FALLBACK`) 与无缝故障转移；
4. 提供 `GatewayAgentScopeModelAdapter`，使得 AgentScope ReActAgent 透明接入全链路治理网关。

**Tech Stack:** Java 21, Spring Boot 3.3.3 WebFlux, Project Reactor, AgentScope 2.0 Core, Lombok, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-20-llm-gateway-design.md`

## Global Constraints

- 严禁硬编码 OpenAI 官方 API 地址或联网 Key，默认使用本地 `http://127.0.0.1:9090/v1` (`local-agy`)；
- 遵循依赖单向流动原则，`copilot-llm-gateway` 不得反向依赖 `copilot-agent-core` 或 `copilot-domain`；
- 所有日志与错误报告中 API Key 必须自动掩码；
- 保留 `copilot-agent-core` 历史代码向后兼容性；
- 全量单元测试必须保持 100% 通过率。

---

### Task 1: 模块脚手架搭建与根 POM 继承配置

**Files:**
- Create: `copilot-llm-gateway/pom.xml`
- Modify: `pom.xml:16-26, 60-75`
- Modify: `copilot-agent-core/pom.xml:30-40`

**Interfaces:**
- Produces: Maven artifact `com.financial.copilot:copilot-llm-gateway:1.0.0-SNAPSHOT`

- [x] **Step 1: 创建 `copilot-llm-gateway/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.financial.copilot</groupId>
        <artifactId>financial-copilot</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>copilot-llm-gateway</artifactId>
    <name>copilot-llm-gateway</name>
    <description>统一大模型网关 (协议适配、路由调度、密钥管理、并发限流与全链路审计)</description>

    <dependencies>
        <dependency>
            <groupId>com.financial.copilot</groupId>
            <artifactId>copilot-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-core</artifactId>
            <version>${agentscope.version}</version>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [x] **Step 2: 在根 `pom.xml` 中注册子模块并添加 dependencyManagement**

修改 `pom.xml`，添加 `<module>copilot-llm-gateway</module>` 和 dependencyManagement 项。

- [x] **Step 3: 在 `copilot-agent-core/pom.xml` 中引入 `copilot-llm-gateway` 依赖**

添加依赖：
```xml
<dependency>
    <groupId>com.financial.copilot</groupId>
    <artifactId>copilot-llm-gateway</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [x] **Step 4: 编译验证**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway compile`
预期: BUILD SUCCESS

- [x] **Step 5: 提交 Task 1**

```powershell
git add pom.xml copilot-llm-gateway/pom.xml copilot-agent-core/pom.xml ; git commit -m "chore(gateway): scaffold copilot-llm-gateway module and configure maven dependencies"
```

---

### Task 2: 核心领域模型与审计 DTO (`LlmRoutingScene`, `LlmAuditRecord` 等)

**Files:**
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmRoutingScene.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmAuditRecord.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmGatewayRequest.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmGatewayResponse.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmHealthReport.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/model/LlmGatewayModelSerializationTest.java`

**Interfaces:**
- Produces: `LlmRoutingScene`, `LlmAuditRecord`, `LlmGatewayRequest`, `LlmGatewayResponse`, `LlmHealthReport`

- [x] **Step 1: 编写单元测试 `LlmGatewayModelSerializationTest.java`**

验证 DTO 序列化反序列化、不可变性与各字段完整性。

- [x] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmGatewayModelSerializationTest"`
预期: 编译失败或测试找不到类

- [x] **Step 3: 编写核心枚举与模型定义**

实现 `LlmRoutingScene.java`、`LlmAuditRecord.java`、`LlmGatewayRequest.java`、`LlmGatewayResponse.java`、`LlmHealthReport.java`。

- [x] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmGatewayModelSerializationTest"`
预期: PASS

- [x] **Step 5: 提交 Task 2**

```powershell
git add copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/ copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/model/ ; git commit -m "feat(gateway): define LlmRoutingScene and gateway core DTOs"
```

---

### Task 3: 凭据管理与安全脱敏 (`GatewayCredentialManager`)

**Files:**
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/credential/GatewayCredentialProperties.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/credential/GatewayCredentialManager.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/credential/GatewayCredentialManagerTest.java`

**Interfaces:**
- Produces:
  ```java
  public class GatewayCredentialManager {
      public String getBaseUrl(String provider);
      public String getApiKey(String provider);
      public String getMaskedApiKey(String provider);
  }
  ```

- [x] **Step 1: 编写单元测试 `GatewayCredentialManagerTest.java`**

测试环境变理解析优先级、本地 Codex-Bridge 缺省回退 (`http://127.0.0.1:9090/v1` + `local-agy`) 以及 Key 掩码输出 (`sk-proj-****1234`)。

- [x] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=GatewayCredentialManagerTest"`

- [x] **Step 3: 实现 `GatewayCredentialProperties` 与 `GatewayCredentialManager`**

- [x] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=GatewayCredentialManagerTest"`
预期: PASS

- [x] **Step 5: 提交 Task 3**

```powershell
git add copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/credential/ copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/credential/ ; git commit -m "feat(gateway): implement GatewayCredentialManager with secure masking and codex-bridge defaults"
```

---

### Task 4: 响应式并发限流与全链路审计 (`GatewayCapacityLimiter` & `GatewayAuditSink`)

**Files:**
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/governance/GatewayCapacityLimiter.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/governance/GatewayAuditSink.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/governance/GatewayCapacityLimiterTest.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/governance/GatewayAuditSinkTest.java`

**Interfaces:**
- Produces:
  ```java
  public class GatewayCapacityLimiter {
      public Mono<CapacityPermit> acquire();
      public int availablePermits();
  }
  public class GatewayAuditSink {
      public void record(LlmAuditRecord record);
      public void addAuditListener(Consumer<LlmAuditRecord> listener);
  }
  ```

- [x] **Step 1: 编写单元测试 `GatewayCapacityLimiterTest` 和 `GatewayAuditSinkTest`**

- [x] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=GatewayCapacityLimiterTest,GatewayAuditSinkTest"`

- [x] **Step 3: 实现 `GatewayCapacityLimiter` 与 `GatewayAuditSink`**

基于响应式 `AtomicInteger` + `Sinks.Many` 或信号量实现非阻塞排队与释放；支持监听器广播回调。

- [x] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=GatewayCapacityLimiterTest,GatewayAuditSinkTest"`
预期: PASS

- [x] **Step 5: 提交 Task 4**

```powershell
git add copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/governance/ copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/governance/ ; git commit -m "feat(gateway): implement GatewayCapacityLimiter and GatewayAuditSink"
```

---

### Task 5: 多场景路由与 Failover 故障自愈执行器 (`LlmRouter` & `LlmFailoverExecutor`)

**Files:**
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/router/LlmRouter.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/router/ProviderEndpointSpec.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/router/LlmFailoverExecutor.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/router/LlmRouterTest.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/router/LlmFailoverExecutorTest.java`

**Interfaces:**
- Produces:
  ```java
  public class LlmRouter {
      public List<ProviderEndpointSpec> resolveRoute(LlmRoutingScene scene, String preferredModel);
  }
  public class LlmFailoverExecutor {
      public Mono<LlmGatewayResponse> executeWithFailover(LlmGatewayRequest request, List<ProviderEndpointSpec> routeChain);
  }
  ```

- [x] **Step 1: 编写单元测试 `LlmRouterTest` 与 `LlmFailoverExecutorTest`**

测试主链路失败（429 / 连接超时 / 5xx）自动重试备选链路，并标明 `isFailover=true`。

- [x] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmRouterTest,LlmFailoverExecutorTest"`

- [x] **Step 3: 实现 `LlmRouter` 与 `LlmFailoverExecutor`**

- [x] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmRouterTest,LlmFailoverExecutorTest"`
预期: PASS

- [x] **Step 5: 提交 Task 5**

```powershell
git add copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/router/ copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/router/ ; git commit -m "feat(gateway): implement multi-scene LlmRouter and LlmFailoverExecutor"
```

---

### Task 6: 统一网关门面与 AgentScope 适配器 (`LlmGatewayClient` & `GatewayAgentScopeModelAdapter`)

**Files:**
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/client/LlmGatewayClient.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/client/DefaultLlmGatewayClient.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/adapter/GatewayAgentScopeModelAdapter.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/client/DefaultLlmGatewayClientTest.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/adapter/GatewayAgentScopeModelAdapterTest.java`

**Interfaces:**
- Produces:
  ```java
  public class DefaultLlmGatewayClient implements LlmGatewayClient;
  public class GatewayAgentScopeModelAdapter extends Model;
  ```

- [x] **Step 1: 编写单元测试 `DefaultLlmGatewayClientTest` 与 `GatewayAgentScopeModelAdapterTest`**

- [x] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=DefaultLlmGatewayClientTest,GatewayAgentScopeModelAdapterTest"`

- [x] **Step 3: 实现 `DefaultLlmGatewayClient` 与 `GatewayAgentScopeModelAdapter`**

- [x] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=DefaultLlmGatewayClientTest,GatewayAgentScopeModelAdapterTest"`
预期: PASS

- [x] **Step 5: 提交 Task 6**

```powershell
git add copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/client/ copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/adapter/ copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/ ; git commit -m "feat(gateway): implement DefaultLlmGatewayClient and GatewayAgentScopeModelAdapter"
```

---

### Task 7: 业务层装配改造与全模块回归验证

**Files:**
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/AgentScopeAgentFactory.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/llm/service/DefaultLlmService.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/agentscope/AgentScopeAgentFactoryTest.java`

- [x] **Step 1: 执行本地 Maven 安装**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway install -DskipTests`

- [x] **Step 2: 将 `AgentScopeAgentFactory` 桥接到 `LlmGatewayClient`**

使 `AgentScopeAgentFactory` 注入 `LlmGatewayClient`，底层模型由 `gatewayClient.createAgentScopeModel(...)` 提供。

- [x] **Step 3: 将 `DefaultLlmService` 门面委派至 `LlmGatewayClient`**

实现平滑无感兼容。

- [x] **Step 4: 执行全量测试套件**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test`
运行: `mvn -s maven-settings.xml -pl copilot-agent-core test`
运行: `mvn -s maven-settings.xml -pl copilot-app test`
预期: 全部测试通过

- [x] **Step 5: 提交 Task 7**

```powershell
git add copilot-agent-core/ ; git commit -m "refactor(agent): integrate LlmGatewayClient into AgentScopeAgentFactory and DefaultLlmService"
```
