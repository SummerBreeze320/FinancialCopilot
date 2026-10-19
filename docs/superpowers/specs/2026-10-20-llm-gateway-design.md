# 统一大模型网关设计规范 (copilot-llm-gateway)

## 1. 背景与目标 (Background & Goals)

### 1.1 现状与痛点
在现有的 `FinancialCopilot` Agent 架构中，底层大模型（LLM）的配置解析、厂商适配、密钥管理、并发控制、超时保护及用量审计散落在 `copilot-agent-core` 的 `infra/llm/` 及各 Agent 工厂中。这导致以下问题：
1. **职责过载**：业务编排层（Agent / DAG Planner）侵入了模型协议适配、重试与网络细节；
2. **多模型治理分散**：缺乏集中的多级场景路由（极速初筛 vs 深度思考推理）与故障自愈降级（Failover）；
3. **安全与审计横切逻辑重复**：密钥脱敏、Token 消耗统计、并发限流与计费扣减没有统一收口。

### 1.2 建设目标
抽取并沉淀全新的独立 Maven 模块 **`copilot-llm-gateway`**，实现：
- **统一门面**：业务代码（AgentScope ReActAgent、工作流、分类器）仅面向统一接口 `LlmGatewayClient`，不感知底层模型与协议；
- **本地优先**：严格遵循全局规范，默认且优先使用本地独立运行的 `codex-bridge` (`http://127.0.0.1:9090/v1`，模型 `gemini-3.8-flash-low`)；
- **多级场景路由与自愈降级 (Failover)**：支持 `FAST`、`STANDARD`、`REASONING`、`QUANT`、`FALLBACK` 场景路由；在线模型网络超时或异常时自动无缝降级至本地备用链路；
- **并发受控与全链路审计**：基于反应式信号量与令牌桶控制全局及单模型并发，生成标准不可变的 `LlmAuditRecord`，无缝对接计费结算与监控指标。

---

## 2. 模块拓扑与依赖架构 (Architecture & Topology)

### 2.1 Maven 分层拓扑
在工程根目录 `pom.xml` 中将 `copilot-llm-gateway` 注册为核心基础组件模块：

```
                    ┌─────────────────────────┐
                    │      copilot-common     │
                    │ (通用枚举/事件/基础 DTO)  │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │   copilot-llm-gateway   │
                    │ (统一 LLM 网关/治理服务) │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │    copilot-agent-core   │
                    │ (Agent/工作流/DAG/执行器)│
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │       copilot-app       │
                    │   (WebFlux 启动器/装配)  │
                    └─────────────────────────┘
```

- **单向依赖约束**：
  - `copilot-llm-gateway` 依赖 `copilot-common`，绝不反向依赖 `copilot-agent-core` 或 `copilot-domain`；
  - `copilot-agent-core` 依赖 `copilot-llm-gateway` 并通过 Spring DI 注入 `LlmGatewayClient`。

---

## 3. 核心契约与数据结构 (Core Contracts & Models)

### 3.1 调用场景枚举 (`LlmRoutingScene`)
```java
package com.financial.copilot.gateway.model;

public enum LlmRoutingScene {
    /** 极速响应场景 (意图识别初筛、实体抽取，默认路由至本地 codex-bridge flash-low) */
    FAST,
    /** 标准对话分析场景 (基金体检、研报初稿、多轮交互) */
    STANDARD,
    /** 深度推理思考场景 (深度归因、多标的横向比对、资产配置，路由至 reasoning 模型) */
    REASONING,
    /** 量化与代码分析场景 (Python 回测校验、公式生成) */
    QUANT,
    /** 兜底降级场景 (主链路熔断或网络异常时的保底链路) */
    FALLBACK
}
```

### 3.2 网关门面契约 (`LlmGatewayClient`)
```java
package com.financial.copilot.gateway.client;

import com.financial.copilot.gateway.model.*;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;
import java.util.function.Consumer;

public interface LlmGatewayClient {
    /** 结构化同步/阻塞式推理调用 */
    LlmGatewayResponse chat(LlmGatewayRequest request);

    /** 响应式 SSE 流式推理 */
    Flux<String> stream(LlmGatewayRequest request);

    /**
     * 为 AgentScope ReActAgent 构建经过全链路网关治理 (限流/自愈/审计) 的 Model 适配器
     */
    Model createAgentScopeModel(LlmRoutingScene scene, Consumer<LlmAuditRecord> auditCallback);

    /** 网关健康检查与下游厂商连通性探测 */
    LlmHealthReport probeHealth();
}
```

### 3.3 请求与响应模型 (`LlmGatewayRequest` / `LlmGatewayResponse`)
```java
package com.financial.copilot.gateway.model;

import lombok.Builder;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

@Builder
public record LlmGatewayRequest(
        String systemPrompt,
        String userPrompt,
        LlmRoutingScene scene,
        String preferredModel,
        Double temperature,
        Integer maxTokens,
        Duration timeout,
        Map<String, Object> metadata,
        Consumer<LlmAuditRecord> auditCallback
) {}
```

```java
package com.financial.copilot.gateway.model;

import lombok.Builder;

@Builder
public record LlmGatewayResponse(
        String content,
        String reasoningContent,
        String actualModel,
        String actualProvider,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs,
        boolean isFailover
) {}
```

---

## 4. 路由与故障自愈降级引擎 (Routing & Failover Engine)

### 4.1 智能路由分发器 (`LlmRouter`)
根据 `LlmRoutingScene` 及配置规则，返回首选与备用 Provider 配置序列：
1. **`FAST`**：首选 `LOCAL_CODEX` (`gemini-3.8-flash-low`)，备选 `FALLBACK`；
2. **`STANDARD`**：首选 `ONLINE_CHAT` (如 DeepSeek / Qwen)，备选 `LOCAL_CODEX`；
3. **`REASONING`**：首选 `ONLINE_REASONER` (如 `deepseek-reasoner`)，备选 `LOCAL_CODEX` (`gemini-3.8-flash-high`)；
4. **`QUANT`**：首选配置的量化代码模型，备选 `LOCAL_CODEX`。

### 4.2 故障自愈执行器 (`LlmFailoverExecutor`)
- 自动捕获 HTTP 连接超时、网络不可达、HTTP 429 (Too Many Requests)、HTTP 5xx 异常；
- 触发重试与平滑故障转移至备用链路；
- 记录 `isFailover = true`，通知审计层与可观测指标。

---

## 5. 安全与治理横切层 (Security, Capacity & Audit)

### 5.1 密钥与凭据管理器 (`GatewayCredentialManager`)
- 遵守优先级规则：环境变量 > 配置文件 > 缺省默认值；
- 默认装配本地 `codex-bridge` 参数：
  ```ini
  URL: http://127.0.0.1:9090/v1
  API Key: local-agy
  Model: gemini-3.8-flash-low
  ```
- 凭据脱敏：网关所有日志输出及异常诊断中，API Key 统一掩码为前 7 位 + `****` + 后 4 位。

### 5.2 响应式并发限流器 (`GatewayCapacityLimiter`)
- 基于响应式信号量 (`Semaphore`) 控制最大全局并发模型调用数（默认 16）；
- 单模型并发配额隔离，防止单一长耗时任务阻塞全局通道。

### 5.3 全链路审计接收器 (`GatewayAuditSink`)
- 每次请求结束后异步投递 `LlmAuditRecord`；
- 记录不可变元数据：`traceId`、`scene`、`actualModel`、`promptTokens`、`completionTokens`、`latencyMs`、`isFailover`；
- 支持挂载外部计费回调（如用户积分扣减）与 Prometheus 指标上报。

---

## 6. AgentScope 与历史业务平滑演进 (Migration)

### 6.1 AgentScope 模型适配器 (`GatewayAgentScopeModelAdapter`)
- 实现 AgentScope `Model` 接口契约：
  - `chat(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options)`
  - `stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options)`
- 将 AgentScope 底层调用包装进 `LlmGatewayClient` 的限流、自愈与审计管道中，业务 Agent 逻辑无需任何改动。

### 6.2 迁移路线图
1. 新建 `copilot-llm-gateway` 模块，沉淀网关组件与测试；
2. 改造 `copilot-agent-core` 的 `AgentScopeAgentFactory`，注入 `LlmGatewayClient` 替代原分散构建逻辑；
3. 保留并适配原 `LlmService` 接口作为兼容门面，底层转发至 `LlmGatewayClient`。

---

## 7. 测试与验证策略 (Validation & Testing)

1. **单元测试覆盖**：
   - `LlmRouterTest`：验证各场景正确路由至首选及备用链路；
   - `LlmFailoverExecutorTest`：模拟主链路超时/429，验证自动降级至本地 Codex-Bridge；
   - `GatewayCapacityLimiterTest`：验证并发信号量溢出与排队机制；
   - `GatewayAuditSinkTest`：验证 Token 统计与审计事件完整性；
   - `GatewayAgentScopeModelAdapterTest`：验证 AgentScope 模型消息适配兼容性。
2. **集成回归验证**：
   - 运行 `copilot-agent-core` 与 `copilot-app` 全量单元测试套件，保证 100% 测试通过率。
