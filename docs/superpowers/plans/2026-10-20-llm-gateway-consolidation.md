# LLM 基础设施下沉与 Gateway 架构收敛实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `copilot-agent-core` 中散落的 `infra/llm`（模型厂商元数据、基础 DTO、连接池工厂、配置管理器、`LlmService` 门面及简易限流）完整下沉合并至独立的 `copilot-llm-gateway`，实现全工程大模型治理与调度的单点真相源（Single Source of Truth），消除双重限流与两套配置。

**Architecture:** 
1. 模块分工收敛：`copilot-llm-gateway` 成为全站唯一的 LLM 底层接入与治理内核；`copilot-agent-core` 纯粹专注于业务 Agent（ReAct）、DAG 调度与业务工具；
2. 消除冗余治理：废除原 `infra/llm/execution` 中的重复执行封装，将 DAG 运行时所需的容量控制直接桥接统一至 `GatewayCapacityLimiter`；
3. 管理面统一：下沉 `LlmConfigManager` 和 `LlmProviderRegistry`，并与 `GatewayCredentialManager` 双向联动，让后台管理控制器直接对接网关管理能力；
4. 渐进式平滑演进：保持包路径平滑重构，全量 280+ 个测试套件保持 100% 通过。

**Tech Stack:** Java 21, Spring Boot 3.3.3 WebFlux, Project Reactor, AgentScope 2.0 Core, Lombok, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-20-llm-gateway-design.md`

## Global Constraints

- 严禁硬编码 OpenAI 官方 API 地址或联网 Key，默认使用本地 Codex-Bridge `http://127.0.0.1:9090/v1` (`local-agy`)；
- 保持严格的单向依赖原则：`copilot-common` -> `copilot-llm-gateway` -> `copilot-agent-core` -> `copilot-app`；
- 所有日志、异常排查与管理报告输出严格执行 API Key 掩码 (`sk-proj-****1234`)；
- 保证旧接口 (`LlmService`、`LlmConfigManager` 等) 的调用语义在全站无感兼容；
- 每个 Task 完成后执行本地测试并提交 Git，全量测试必须 100% 通过。

---

### Task 1: 厂商目录与基础 DTO 下沉至 `copilot-llm-gateway`

**Files:**
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/catalog/LlmProviderType.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/catalog/LlmModelOption.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/catalog/LlmProviderMetadata.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/catalog/LlmProviderRegistry.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmSettingsDTO.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmRequest.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmResponse.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmConnectionTestRequest.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/LlmConnectionTestResult.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/catalog/LlmProviderRegistryTest.java`

**Interfaces:**
- Produces:
  ```java
  public class LlmProviderRegistry {
      public List<LlmProviderMetadata> getAllProviders();
      public Optional<LlmProviderMetadata> getProvider(LlmProviderType type);
      public Optional<LlmProviderMetadata> getProvider(String typeName);
      public List<LlmModelOption> getModelsForProvider(LlmProviderType type);
  }
  public class LlmSettingsDTO;
  public class LlmRequest;
  public class LlmResponse;
  public class LlmConnectionTestRequest;
  public class LlmConnectionTestResult;
  ```

- [ ] **Step 1: 编写单元测试 `LlmProviderRegistryTest.java`**

测试内置 Provider（DEEPSEEK, QWEN, OPENAI, OLLAMA, SILICONFLOW 等）的加载、模型选项检索及容错查找。

- [ ] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmProviderRegistryTest"`

- [ ] **Step 3: 实现 `catalog` 与 `model` 基础模型**

将相关模型与注册表迁移至 `copilot-llm-gateway`，补齐中文 Javadoc 与 Lombok 注解。

- [ ] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmProviderRegistryTest"`
预期: PASS

- [ ] **Step 5: 提交 Task 1**

```powershell
git add copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/catalog/ copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/model/ copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/catalog/ ; git commit -m "feat(gateway): migrate provider catalog and core DTOs into copilot-llm-gateway"
```

---

### Task 2: 连接工厂与配置管理下沉 (`LlmDynamicWebClientFactory`, `LlmConfigManager`)

**Files:**
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/transport/LlmDynamicWebClientFactory.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/config/LlmProperties.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/config/LlmConfigManager.java`
- Modify: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/router/LlmFailoverExecutor.java` (集成 WebClient 连接复用)
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/transport/LlmDynamicWebClientFactoryTest.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/config/LlmConfigManagerTest.java`

**Interfaces:**
- Produces:
  ```java
  public class LlmDynamicWebClientFactory {
      public WebClient getOrCreateWebClient(String baseUrl, String apiKey);
  }
  public class LlmConfigManager {
      public LlmSettingsDTO getActiveSettings();
      public synchronized void updateSettings(LlmSettingsDTO newSettings);
      public void addListener(Consumer<LlmSettingsDTO> listener);
  }
  ```

- [ ] **Step 1: 编写单元测试 `LlmDynamicWebClientFactoryTest` 与 `LlmConfigManagerTest`**

测试 WebClient 按 `baseUrl + apiKey` 缓存复用，以及 `LlmConfigManager` 的活动配置获取、动态更新广播与本地 Codex-Bridge 缺省兜底。

- [ ] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmDynamicWebClientFactoryTest,LlmConfigManagerTest"`

- [ ] **Step 3: 实现 `LlmDynamicWebClientFactory`、`LlmProperties` 与 `LlmConfigManager`**

支持动态热更新配置，并在 `DefaultWebClientEndpointInvoker` 中集成 `LlmDynamicWebClientFactory` 避免频繁销毁创建 WebClient。

- [ ] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmDynamicWebClientFactoryTest,LlmConfigManagerTest"`
预期: PASS

- [ ] **Step 5: 提交 Task 2**

```powershell
git add copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/transport/ copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/config/ copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/ ; git commit -m "feat(gateway): migrate LlmDynamicWebClientFactory and LlmConfigManager into gateway"
```

---

### Task 3: 通用服务契约下沉与连接探测 (`LlmService` & `DefaultLlmService`)

**Files:**
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/service/LlmService.java`
- Create: `copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/service/DefaultLlmService.java`
- Test: `copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/service/LlmServiceTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface LlmService {
      String chat(LlmRequest request);
      String chat(String systemPrompt, String userMessage, boolean enableThinking);
      Flux<String> chatStream(LlmRequest request);
      LlmConnectionTestResult testConnection(LlmConnectionTestRequest request);
  }
  ```

- [ ] **Step 1: 编写单元测试 `LlmServiceTest.java`**

测试通过 `LlmService` 发起结构化同步推理、流式推理以及连通性探测。

- [ ] **Step 2: 运行测试验证失败**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmServiceTest"`

- [ ] **Step 3: 实现 `LlmService` 与 `DefaultLlmService`**

使 `DefaultLlmService` 原生依托于 `LlmGatewayClient` 驱动，并提供对非规范外部模型的直接探测能力。

- [ ] **Step 4: 运行测试验证通过**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test "-Dtest=LlmServiceTest"`
预期: PASS

- [ ] **Step 5: 执行 `copilot-llm-gateway` 本地 install**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway install -DskipTests`

- [ ] **Step 6: 提交 Task 3**

```powershell
git add copilot-llm-gateway/src/main/java/com/financial/copilot/gateway/service/ copilot-llm-gateway/src/test/java/com/financial/copilot/gateway/service/ ; git commit -m "feat(gateway): migrate LlmService and DefaultLlmService into gateway"
```

---

### Task 4: `copilot-agent-core` 清理与旧执行层消除

**Files:**
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/config/DagRuntimeConfig.java` (将 LlmCapacityLimiter 对接 GatewayCapacityLimiter)
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/AgentScopeAgentFactory.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/llm/execution/LlmCapacityLimiter.java` (适配器桥接)
- Remove/Deprecate: `copilot-agent-core` 中已被完全替换的旧实现文件
- Update: `copilot-agent-core` 各处 package import，指向 `com.financial.copilot.gateway.*`

- [ ] **Step 1: 将 `LlmCapacityLimiter` 内部委托至 `GatewayCapacityLimiter`**

消除双重限流，使 DAG 运行时和 Gateway 共享统一的并发配额。

- [ ] **Step 2: 更新 `copilot-agent-core` 中的 imports**

将旧 `infra.llm.*` 的导入更新为 `com.financial.copilot.gateway.*`，消除编译警告。

- [ ] **Step 3: 运行 `copilot-agent-core` 编译与测试**

运行: `mvn -s maven-settings.xml -pl copilot-agent-core test "-Dcopilot.neo4j.enabled=false"`
预期: 全部 213 个测试通过。

- [ ] **Step 4: 提交 Task 4**

```powershell
git add copilot-agent-core/ ; git commit -m "refactor(agent): redirect copilot-agent-core to copilot-llm-gateway and eliminate redundant execution layers"
```

---

### Task 5: `copilot-app` 管理端控制台适配与全链路回归

**Files:**
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/admin/LlmAdminConfigController.java`
- Modify: `copilot-app/src/test/java/com/financial/copilot/controller/admin/LlmAdminConfigControllerTest.java`

- [ ] **Step 1: 更新 `LlmAdminConfigController` 的导包与装配**

使后台大模型配置管理接口直接操作 `copilot-llm-gateway` 的 `LlmConfigManager` 与 `LlmService`。

- [ ] **Step 2: 运行 `LlmAdminConfigControllerTest`**

运行: `mvn -s maven-settings.xml -pl copilot-app test "-Dtest=LlmAdminConfigControllerTest"`
预期: PASS

- [ ] **Step 3: 执行工程全量端到端测试套件**

运行: `mvn -s maven-settings.xml -pl copilot-llm-gateway test`
运行: `mvn -s maven-settings.xml -pl copilot-agent-core test "-Dcopilot.neo4j.enabled=false"`
运行: `mvn -s maven-settings.xml -pl copilot-app test "-Dcopilot.neo4j.enabled=false"`
预期: 100% 测试通过率，零回归。

- [ ] **Step 4: 提交 Task 5**

```powershell
git add copilot-app/ docs/superpowers/plans/ ; git commit -m "refactor(app): wire admin config controller to llm gateway and verify full regression"
```
