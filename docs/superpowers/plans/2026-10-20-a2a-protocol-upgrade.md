# Open-Source A2A Protocol & AgentCard Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize SubAgents with open-source A2A protocol specifications (`io.a2a.spec.AgentCard`, `AgentSkill`, `AgentCapabilities`) and replace hardcoded routing in `AgentNodeRouter` with a dynamic capability-driven `A2aAgentRegistry`.

**Architecture:** Introduce `A2aExecutableAgent` as the unified agent protocol contract, expose official `io.a2a.spec.AgentCard` metadata for all domain SubAgents via `AgentCardDefinitions`, maintain an indexed `A2aAgentRegistry`, and refactor `AgentNodeRouter` to dynamically discover and dispatch execution by task type and asset category while preserving full backward compatibility.

**Tech Stack:** Java 21, Spring Boot 3.3.3, Project Reactor, AgentScope Java 2.0.0, a2a-java-sdk-spec 0.2.5.Beta2, JUnit 5, AssertJ.

**Spec:** Open-source Agent-to-Agent protocol (`io.a2a.spec`) specification and AgentScope A2A architecture (`io.agentscope.core.a2a`).

---

## Global Constraints
- Strictly adhere to Java 21, Spring WebFlux, and Project Reactor standards.
- Follow `.agents/rules/coding-standards.md` (Chinese Javadoc, clean imports, Lombok hygiene).
- Preserve existing backward compatibility for DAG execution (`DagRuntime`, `SupervisorWorkflow`).
- No external dependency additions (utilize existing `io.github.a2asdk:a2a-java-sdk-spec:0.2.5.Beta2` transitive dependency).
- All changes must pass tests via `mvn -s maven-settings.xml -pl copilot-agent-core test`.

---

## Task Breakdown

### Task 1: Define `A2aExecutableAgent` Interface and `AgentCardDefinitions`

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/A2aExecutableAgent.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/AgentCardDefinitions.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/AgentCardDefinitionsTest.java`

**Interfaces:**
- Produces:
  - `A2aExecutableAgent`: `io.a2a.spec.AgentCard agentCard()`, `Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context)`, `boolean supports(String taskType, String assetCategory)`.
  - `AgentCardDefinitions`: static factory methods for creating `AgentCard` for `FUND_SCREENER`, `STOCK_SCREENER`, `FUND_ANALYZER`, `STOCK_ANALYZER`, `FUND_COMPARATOR`, `STOCK_COMPARATOR`, and `REPORT_SYNTHESIZER`.

- [ ] **Step 1: Write failing test `AgentCardDefinitionsTest.java`**

```java
package com.financial.copilot.agent.core.business.agents.a2a;

import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("A2A AgentCardDefinitions 元数据规范测试")
class AgentCardDefinitionsTest {

    @Test
    @DisplayName("验证所有领域智能体的 AgentCard 符合 A2A 规范且包含必要标签")
    void testAllAgentCardsValid() {
        AgentCard fundScreener = AgentCardDefinitions.fundScreenerCard();
        assertNotNull(fundScreener);
        assertEquals("FundScreenerAgent", fundScreener.name());
        assertFalse(fundScreener.skills().isEmpty());
        assertTrue(fundScreener.skills().getFirst().tags().contains("SCREENING"));
        assertTrue(fundScreener.skills().getFirst().tags().contains("FUND"));

        AgentCard stockAnalyzer = AgentCardDefinitions.stockAnalyzerCard();
        assertNotNull(stockAnalyzer);
        assertEquals("StockAnalyzerAgent", stockAnalyzer.name());
        assertTrue(stockAnalyzer.skills().getFirst().tags().contains("BATCH_ANALYSIS"));
        assertTrue(stockAnalyzer.skills().getFirst().tags().contains("STOCK"));

        AgentCard synth = AgentCardDefinitions.reportSynthesizerCard();
        assertNotNull(synth);
        assertEquals("ReportSynthesizerAgent", synth.name());
        assertTrue(synth.skills().getFirst().tags().contains("SYNTHESIS"));
        assertTrue(synth.skills().getFirst().tags().contains("ALL"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=AgentCardDefinitionsTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement `A2aExecutableAgent` and `AgentCardDefinitions`**

Write `A2aExecutableAgent.java`:
```java
package com.financial.copilot.agent.core.business.agents.a2a;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import io.a2a.spec.AgentCard;

import java.util.List;
import java.util.Locale;

/**
 * <h1>A2A (Agent-to-Agent) 可执行智能体标准契约</h1>
 * <p>
 * 遵循开源 A2A 协议规范，每个智能体持有自描述的 {@link AgentCard}，
 * 支持基于 TaskType 与 AssetCategory 的能力自省与动态匹配。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface A2aExecutableAgent {

    /**
     * 获取智能体的标准 A2A 名片元数据
     *
     * @return 符合 io.a2a.spec.AgentCard 规范的实例
     */
    AgentCard agentCard();

    /**
     * 执行当前 DAG 节点分析任务
     *
     * @param node 当前 DAG 节点元数据
     * @param input 输入产物集合
     * @param context 节点运行时上下文
     * @return 业务产物工件
     */
    Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context);

    /**
     * 判断当前智能体是否支持指定的任务类型与资产域
     *
     * @param taskType 任务类型 (如 SCREENING, BATCH_ANALYSIS, COMPARISON, SYNTHESIS)
     * @param assetCategory 资产类别 (如 FUND, STOCK)
     * @return 是否支持
     */
    default boolean supports(String taskType, String assetCategory) {
        if (taskType == null) {
            return false;
        }
        AgentCard card = agentCard();
        if (card == null || card.skills() == null || card.skills().isEmpty()) {
            return false;
        }
        String normalizedTask = taskType.trim().toUpperCase(Locale.ROOT);
        String normalizedAsset = assetCategory != null ? assetCategory.trim().toUpperCase(Locale.ROOT) : "FUND";

        return card.skills().stream().anyMatch(skill -> {
            List<String> tags = skill.tags();
            if (tags == null) {
                return false;
            }
            boolean taskMatch = tags.stream().anyMatch(t -> t.equalsIgnoreCase(normalizedTask));
            boolean assetMatch = tags.contains("ALL") || tags.stream().anyMatch(t -> t.equalsIgnoreCase(normalizedAsset));
            return taskMatch && assetMatch;
        });
    }
}
```

Write `AgentCardDefinitions.java`:
```java
package com.financial.copilot.agent.core.business.agents.a2a;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;

import java.util.List;

/**
 * <h1>标准 A2A AgentCard 元数据工厂</h1>
 * <p>
 * 为投研系统的各个领域 SubAgent 提供标准化的 {@link AgentCard} 名片定义。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class AgentCardDefinitions {

    private static final String PROTOCOL_VERSION = "0.2.5";
    private static final String AGENT_VERSION = "1.0.0";

    private AgentCardDefinitions() {
    }

    public static AgentCard fundScreenerCard() {
        return buildCard(
                "FundScreenerAgent",
                "公募基金初筛排查智能体，支持对齐指标助记码与板块并筛选候选基金池",
                "fund-screening",
                "基金条件筛选",
                List.of("SCREENING", "FUND")
        );
    }

    public static AgentCard stockScreenerCard() {
        return buildCard(
                "StockScreenerAgent",
                "个股行情与财务条件筛选智能体，根据行业板块与基本面指标筛选股票池",
                "stock-screening",
                "股票条件筛选",
                List.of("SCREENING", "STOCK")
        );
    }

    public static AgentCard fundAnalyzerAgentCard() {
        return buildCard(
                "FundAnalyzerAgent",
                "公募基金量化与定性分析智能体，执行指标测算、重仓穿透与研报检索",
                "fund-analysis",
                "基金量化分析",
                List.of("BATCH_ANALYSIS", "FUND")
        );
    }

    public static AgentCard stockAnalyzerCard() {
        return buildCard(
                "StockAnalyzerAgent",
                "个股基本面与财务穿透分析智能体，负责深度量化与定性研报",
                "stock-analysis",
                "股票基本面分析",
                List.of("BATCH_ANALYSIS", "STOCK")
        );
    }

    public static AgentCard fundComparatorCard() {
        return buildCard(
                "FundComparatorAgent",
                "多只公募基金深度对比与评级智能体，生成横向对比矩阵与优劣势洞察",
                "fund-comparison",
                "基金深度对比",
                List.of("COMPARISON", "DEEP_DIVE", "FUND")
        );
    }

    public static AgentCard stockComparatorCard() {
        return buildCard(
                "StockComparatorAgent",
                "多只个股横向对比与竞争壁垒分析智能体",
                "stock-comparison",
                "股票深度对比",
                List.of("COMPARISON", "DEEP_DIVE", "STOCK")
        );
    }

    public static AgentCard reportSynthesizerCard() {
        return buildCard(
                "ReportSynthesizerAgent",
                "投研研报终审与合成聚合专家，接收经验证产物并进行降级披露与最终研报编排",
                "report-synthesis",
                "研报终审合成",
                List.of("SYNTHESIS", "ALL", "FUND", "STOCK")
        );
    }

    private static AgentCard buildCard(String name, String description, String skillId, String skillName, List<String> tags) {
        AgentCapabilities capabilities = new AgentCapabilities.Builder()
                .streaming(false)
                .pushNotifications(false)
                .stateTransitionHistory(false)
                .build();

        AgentSkill skill = new AgentSkill.Builder()
                .id(skillId)
                .name(skillName)
                .description(description)
                .tags(tags)
                .inputModes(List.of("application/json", "text/plain"))
                .outputModes(List.of("application/json"))
                .build();

        return new AgentCard.Builder()
                .name(name)
                .description(description)
                .version(AGENT_VERSION)
                .protocolVersion(PROTOCOL_VERSION)
                .capabilities(capabilities)
                .skills(List.of(skill))
                .defaultInputModes(List.of("application/json", "text/plain"))
                .defaultOutputModes(List.of("application/json"))
                .build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=AgentCardDefinitionsTest`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/
git add copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/
git commit -m "feat(a2a): define A2aExecutableAgent contract and AgentCardDefinitions"
```

---

### Task 2: Build `A2aAgentRegistry`

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/A2aAgentRegistry.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/A2aAgentRegistryTest.java`

**Interfaces:**
- Consumes: `A2aExecutableAgent`, `io.a2a.spec.AgentCard`.
- Produces: `A2aAgentRegistry`:
  - `Optional<A2aExecutableAgent> findAgent(String taskType, String assetCategory)`
  - `A2aExecutableAgent getRequiredAgent(String taskType, String assetCategory)`
  - `List<AgentCard> getAllAgentCards()`
  - `Optional<AgentCard> findCardByName(String agentName)`
  - `void register(A2aExecutableAgent agent)`

- [ ] **Step 1: Write failing test `A2aAgentRegistryTest.java`**

```java
package com.financial.copilot.agent.core.business.agents.a2a;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("A2aAgentRegistry 注册中心测试")
class A2aAgentRegistryTest {

    static class MockAgent implements A2aExecutableAgent {
        private final AgentCard card;

        MockAgent(AgentCard card) {
            this.card = card;
        }

        @Override
        public AgentCard agentCard() {
            return card;
        }

        @Override
        public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
            return null;
        }
    }

    @Test
    @DisplayName("验证按任务类型与资产类别匹配已注册智能体")
    void testFindAndGetAgent() {
        MockAgent fundScreener = new MockAgent(AgentCardDefinitions.fundScreenerCard());
        MockAgent stockScreener = new MockAgent(AgentCardDefinitions.stockScreenerCard());
        MockAgent synth = new MockAgent(AgentCardDefinitions.reportSynthesizerCard());

        A2aAgentRegistry registry = new A2aAgentRegistry(List.of(fundScreener, stockScreener, synth));

        Optional<A2aExecutableAgent> foundFund = registry.findAgent("SCREENING", "FUND");
        assertTrue(foundFund.isPresent());
        assertEquals("FundScreenerAgent", foundFund.get().agentCard().name());

        Optional<A2aExecutableAgent> foundStock = registry.findAgent("SCREENING", "STOCK");
        assertTrue(foundStock.isPresent());
        assertEquals("StockScreenerAgent", foundStock.get().agentCard().name());

        // SYNTHESIS matches ALL asset categories
        Optional<A2aExecutableAgent> foundSynth = registry.findAgent("SYNTHESIS", "STOCK");
        assertTrue(foundSynth.isPresent());
        assertEquals("ReportSynthesizerAgent", foundSynth.get().agentCard().name());

        // Missing task type throws exception on getRequiredAgent
        assertThrows(IllegalArgumentException.class, () -> registry.getRequiredAgent("NON_EXISTENT", "FUND"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=A2aAgentRegistryTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement `A2aAgentRegistry`**

```java
package com.financial.copilot.agent.core.business.agents.a2a;

import io.a2a.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <h1>A2A 智能体注册中心与能力发现引擎 (A2aAgentRegistry)</h1>
 * <p>
 * 管理系统中已注册的 {@link A2aExecutableAgent} 及其标准 {@link AgentCard}，
 * 支持按 TaskType 与 AssetCategory 进行能力发现与精准路由。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class A2aAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(A2aAgentRegistry.class);

    private final List<A2aExecutableAgent> agents = new CopyOnWriteArrayList<>();

    @Autowired
    public A2aAgentRegistry(List<A2aExecutableAgent> initialAgents) {
        if (initialAgents != null) {
            initialAgents.forEach(this::register);
        }
    }

    public A2aAgentRegistry() {
    }

    public synchronized void register(A2aExecutableAgent agent) {
        if (agent == null) {
            return;
        }
        AgentCard card = agent.agentCard();
        String name = card != null ? card.name() : agent.getClass().getSimpleName();
        agents.removeIf(a -> a.agentCard() != null && Objects.equals(a.agentCard().name(), name));
        agents.add(agent);
        log.info("Registered A2A Agent: name={}, skills={}", name,
                card != null && card.skills() != null ? card.skills().size() : 0);
    }

    public Optional<A2aExecutableAgent> findAgent(String taskType, String assetCategory) {
        if (taskType == null) {
            return Optional.empty();
        }
        return agents.stream()
                .filter(a -> a.supports(taskType, assetCategory))
                .findFirst();
    }

    public A2aExecutableAgent getRequiredAgent(String taskType, String assetCategory) {
        return findAgent(taskType, assetCategory)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No A2A Agent registered for taskType=" + taskType + ", assetCategory=" + assetCategory));
    }

    public List<AgentCard> getAllAgentCards() {
        return agents.stream()
                .map(A2aExecutableAgent::agentCard)
                .filter(Objects::nonNull)
                .toList();
    }

    public Optional<AgentCard> findCardByName(String agentName) {
        if (agentName == null) {
            return Optional.empty();
        }
        return agents.stream()
                .map(A2aExecutableAgent::agentCard)
                .filter(c -> c != null && agentName.equalsIgnoreCase(c.name()))
                .findFirst();
    }

    public List<A2aExecutableAgent> getAgents() {
        return Collections.unmodifiableList(agents);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=A2aAgentRegistryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/A2aAgentRegistry.java
git add copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/A2aAgentRegistryTest.java
git commit -m "feat(a2a): implement A2aAgentRegistry for capability-based agent discovery"
```

---

### Task 3: Refactor SubAgents to Implement `A2aExecutableAgent`

**Files:**
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/fund/FundScreenerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/fund/FundAnalyzerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/fund/FundComparatorAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/stock/StockScreenerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/stock/StockAnalyzerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/stock/StockComparatorAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/ReportSynthesizerAgent.java`
- Create: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/SubAgentA2aContractTest.java`

**Interfaces:**
- Each SubAgent adds `implements A2aExecutableAgent` and returns its designated `AgentCard` from `AgentCardDefinitions`.
- Existing `execute(...)` signatures are unchanged, ensuring zero regressions.

- [ ] **Step 1: Write failing test `SubAgentA2aContractTest.java`**

```java
package com.financial.copilot.agent.core.business.agents.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.ReportSynthesizerAgent;
import com.financial.copilot.agent.core.business.agents.fund.FundAnalyzerAgent;
import com.financial.copilot.agent.core.business.agents.fund.FundComparatorAgent;
import com.financial.copilot.agent.core.business.agents.fund.FundScreenerAgent;
import com.financial.copilot.agent.core.business.agents.stock.StockAnalyzerAgent;
import com.financial.copilot.agent.core.business.agents.stock.StockComparatorAgent;
import com.financial.copilot.agent.core.business.agents.stock.StockScreenerAgent;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("SubAgents A2A 契约测试")
class SubAgentA2aContractTest {

    @Test
    @DisplayName("验证所有 SubAgents 均实现 A2aExecutableAgent 且正确提供 AgentCard 与支持度匹配")
    void testAllSubAgentsImplementA2aExecutableAgent() {
        AgentScopeAgentFactory factory = mock(AgentScopeAgentFactory.class);
        RoleToolkitRegistry registry = mock(RoleToolkitRegistry.class);
        ObjectMapper mapper = new ObjectMapper();

        FundScreenerAgent fundScreener = new FundScreenerAgent(factory, registry, mapper);
        assertTrue(fundScreener instanceof A2aExecutableAgent);
        assertEquals("FundScreenerAgent", fundScreener.agentCard().name());
        assertTrue(fundScreener.supports("SCREENING", "FUND"));
        assertFalse(fundScreener.supports("SCREENING", "STOCK"));

        StockScreenerAgent stockScreener = new StockScreenerAgent(factory, registry, mapper);
        assertTrue(stockScreener instanceof A2aExecutableAgent);
        assertTrue(stockScreener.supports("SCREENING", "STOCK"));

        FundAnalyzerAgent fundAnalyzer = new FundAnalyzerAgent(factory, registry, mapper);
        assertTrue(fundAnalyzer instanceof A2aExecutableAgent);
        assertTrue(fundAnalyzer.supports("BATCH_ANALYSIS", "FUND"));

        StockAnalyzerAgent stockAnalyzer = new StockAnalyzerAgent(factory, registry, mapper);
        assertTrue(stockAnalyzer instanceof A2aExecutableAgent);
        assertTrue(stockAnalyzer.supports("BATCH_ANALYSIS", "STOCK"));

        FundComparatorAgent fundComparator = new FundComparatorAgent(factory, registry, mapper);
        assertTrue(fundComparator instanceof A2aExecutableAgent);
        assertTrue(fundComparator.supports("COMPARISON", "FUND"));
        assertTrue(fundComparator.supports("DEEP_DIVE", "FUND"));

        StockComparatorAgent stockComparator = new StockComparatorAgent(factory, registry, mapper);
        assertTrue(stockComparator instanceof A2aExecutableAgent);
        assertTrue(stockComparator.supports("COMPARISON", "STOCK"));
        assertTrue(stockComparator.supports("DEEP_DIVE", "STOCK"));

        ReportSynthesizerAgent synth = new ReportSynthesizerAgent(factory, registry);
        assertTrue(synth instanceof A2aExecutableAgent);
        assertTrue(synth.supports("SYNTHESIS", "FUND"));
        assertTrue(synth.supports("SYNTHESIS", "STOCK"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=SubAgentA2aContractTest`
Expected: FAIL (compilation errors / `FundScreenerAgent` does not implement `A2aExecutableAgent`)

- [ ] **Step 3: Implement `implements A2aExecutableAgent` on all SubAgents**

- In `FundScreenerAgent.java`:
  ```java
  public class FundScreenerAgent implements com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent {
      // ...
      @Override
      public io.a2a.spec.AgentCard agentCard() {
          return com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions.fundScreenerCard();
      }
  ```
- In `StockScreenerAgent.java`:
  ```java
  public class StockScreenerAgent implements com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent {
      // ...
      @Override
      public io.a2a.spec.AgentCard agentCard() {
          return com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions.stockScreenerCard();
      }
  ```
- In `FundAnalyzerAgent.java`:
  ```java
  public class FundAnalyzerAgent implements com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent {
      // ...
      @Override
      public io.a2a.spec.AgentCard agentCard() {
          return com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions.fundAnalyzerAgentCard();
      }
  ```
- In `StockAnalyzerAgent.java`:
  ```java
  public class StockAnalyzerAgent implements com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent {
      // ...
      @Override
      public io.a2a.spec.AgentCard agentCard() {
          return com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions.stockAnalyzerCard();
      }
  ```
- In `FundComparatorAgent.java`:
  ```java
  public class FundComparatorAgent implements com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent {
      // ...
      @Override
      public io.a2a.spec.AgentCard agentCard() {
          return com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions.fundComparatorCard();
      }
  ```
- In `StockComparatorAgent.java`:
  ```java
  public class StockComparatorAgent implements com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent {
      // ...
      @Override
      public io.a2a.spec.AgentCard agentCard() {
          return com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions.stockComparatorCard();
      }
  ```
- In `ReportSynthesizerAgent.java`:
  ```java
  public class ReportSynthesizerAgent implements AggregatorAgent, com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent {
      // ...
      @Override
      public io.a2a.spec.AgentCard agentCard() {
          return com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions.reportSynthesizerCard();
      }
  ```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=SubAgentA2aContractTest`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/
git add copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/SubAgentA2aContractTest.java
git commit -m "feat(a2a): implement A2aExecutableAgent and bind AgentCards across all SubAgents"
```

---

### Task 4: Refactor `AgentNodeRouter` to Dispatch via `A2aAgentRegistry`

**Files:**
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/AgentNodeRouter.java`
- Create: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/AgentNodeRouterTest.java`

**Interfaces:**
- Consumes: `A2aAgentRegistry`.
- Produces: `AgentNodeRouter.execute(GraphNode, NodeInput, NodeExecutionContext)`.
- Backward Compatibility: preserves `AgentNodeRouter(...)` constructor taking individual agents, automatically wrapping them in an `A2aAgentRegistry`.

- [ ] **Step 1: Write failing test `AgentNodeRouterTest.java`**

```java
package com.financial.copilot.agent.core.business.agents;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentNodeRouter A2A 动态路由测试")
class AgentNodeRouterTest {

    @Test
    @DisplayName("验证根据 node 参数与 prompt 自动路由至正确的 A2A 智能体")
    void testDynamicRoutingByTaskTypeAndAsset() {
        A2aExecutableAgent fundScreener = mock(A2aExecutableAgent.class);
        when(fundScreener.agentCard()).thenReturn(AgentCardDefinitions.fundScreenerCard());
        when(fundScreener.supports(eq("SCREENING"), eq("FUND"))).thenReturn(true);
        when(fundScreener.execute(any(), any(), any())).thenReturn(
                new Artifact<>("fundPool", "pool-1", "FundPool", ArtifactMetadata.standard("fund"), "fund-data"));

        A2aExecutableAgent stockScreener = mock(A2aExecutableAgent.class);
        when(stockScreener.agentCard()).thenReturn(AgentCardDefinitions.stockScreenerCard());
        when(stockScreener.supports(eq("SCREENING"), eq("STOCK"))).thenReturn(true);
        when(stockScreener.execute(any(), any(), any())).thenReturn(
                new Artifact<>("stockPool", "pool-2", "StockPool", ArtifactMetadata.standard("stock"), "stock-data"));

        A2aAgentRegistry registry = new A2aAgentRegistry(List.of(fundScreener, stockScreener));
        AgentNodeRouter router = new AgentNodeRouter(registry);

        GraphRunRequest fundRequest = new GraphRunRequest("req-1", "帮我筛选消费基金", UUID.randomUUID().toString(), null);
        NodeExecutionContext fundContext = new NodeExecutionContext("node-1", fundRequest, 1, CancellationToken.none());
        GraphNode fundNode = GraphNode.builder().nodeId("node-1").taskType("SCREENING").params(Map.of("assetCategory", "FUND")).build();

        Artifact<?> fundResult = router.execute(fundNode, NodeInput.empty(), fundContext);
        assertNotNull(fundResult);
        assertEquals("fund-data", fundResult.getPayload());

        GraphRunRequest stockRequest = new GraphRunRequest("req-2", "帮我筛选白酒股票", UUID.randomUUID().toString(), null);
        NodeExecutionContext stockContext = new NodeExecutionContext("node-2", stockRequest, 1, CancellationToken.none());
        GraphNode stockNode = GraphNode.builder().nodeId("node-2").taskType("SCREENING").params(Map.of("assetCategory", "STOCK")).build();

        Artifact<?> stockResult = router.execute(stockNode, NodeInput.empty(), stockContext);
        assertNotNull(stockResult);
        assertEquals("stock-data", stockResult.getPayload());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=AgentNodeRouterTest`
Expected: FAIL (Constructor `AgentNodeRouter(A2aAgentRegistry)` not found)

- [ ] **Step 3: Refactor `AgentNodeRouter.java`**

```java
package com.financial.copilot.agent.core.business.agents;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.fund.*;
import com.financial.copilot.agent.core.business.agents.stock.*;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <h1>A2A 智能体节点调度路由器 (AgentNodeRouter)</h1>
 * <p>
 * 基于 {@link A2aAgentRegistry} 发现并动态分发 DAG 节点至符合 {@link io.a2a.spec.AgentCard} 规范的智能体。
 * 消除传统硬编码 switch-case，提供透明平滑的向下兼容。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class AgentNodeRouter {

    private final A2aAgentRegistry registry;

    @Autowired
    public AgentNodeRouter(A2aAgentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 向下兼容旧版构造器，自动将传入的 SubAgent 注册进内部 A2aAgentRegistry
     */
    public AgentNodeRouter(FundScreenerAgent fundScreener, StockScreenerAgent stockScreener,
                           FundAnalyzerAgent fundAnalyzer, StockAnalyzerAgent stockAnalyzer,
                           FundComparatorAgent fundComparator, StockComparatorAgent stockComparator,
                           AggregatorAgent synthesizer) {
        this(createCompatibilityRegistry(
                fundScreener, stockScreener, fundAnalyzer, stockAnalyzer,
                fundComparator, stockComparator, synthesizer));
    }

    private static A2aAgentRegistry createCompatibilityRegistry(
            FundScreenerAgent fundScreener, StockScreenerAgent stockScreener,
            FundAnalyzerAgent fundAnalyzer, StockAnalyzerAgent stockAnalyzer,
            FundComparatorAgent fundComparator, StockComparatorAgent stockComparator,
            AggregatorAgent synthesizer) {
        List<A2aExecutableAgent> list = new ArrayList<>();
        if (fundScreener != null) list.add(fundScreener);
        if (stockScreener != null) list.add(stockScreener);
        if (fundAnalyzer != null) list.add(fundAnalyzer);
        if (stockAnalyzer != null) list.add(stockAnalyzer);
        if (fundComparator != null) list.add(fundComparator);
        if (stockComparator != null) list.add(stockComparator);
        if (synthesizer instanceof A2aExecutableAgent a2a) {
            list.add(a2a);
        } else if (synthesizer != null) {
            list.add(new AggregatorAdapter(synthesizer));
        }
        return new A2aAgentRegistry(list);
    }

    public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        if (!AgentRoleCatalog.supports(node.getTaskType())) {
            throw new IllegalArgumentException("No AgentScope role for task type: " + node.getTaskType());
        }

        String assetCategory = resolveAssetCategory(node, context);
        A2aExecutableAgent targetAgent = registry.getRequiredAgent(node.getTaskType(), assetCategory);
        return targetAgent.execute(node, input, context);
    }

    private String resolveAssetCategory(GraphNode node, NodeInput contextNodeInput) {
        // Overload helper for prompt inspection
        return "FUND";
    }

    private String resolveAssetCategory(GraphNode node, NodeExecutionContext context) {
        Object paramCategory = node.getParams() != null ? node.getParams().get("assetCategory") : null;
        if (paramCategory != null && !String.valueOf(paramCategory).isBlank()) {
            return String.valueOf(paramCategory).trim().toUpperCase(java.util.Locale.ROOT);
        }
        if (context != null && context.request() != null && context.request().prompt() != null) {
            String prompt = context.request().prompt();
            if (prompt.contains("股票") || prompt.contains("个股")) {
                return "STOCK";
            }
        }
        return "FUND";
    }

    public A2aAgentRegistry getRegistry() {
        return registry;
    }

    private static class AggregatorAdapter implements A2aExecutableAgent {
        private final AggregatorAgent delegate;

        AggregatorAdapter(AggregatorAgent delegate) {
            this.delegate = delegate;
        }

        @Override
        public io.a2a.spec.AgentCard agentCard() {
            return com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions.reportSynthesizerCard();
        }

        @Override
        public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
            return delegate.execute(node, input, context);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=AgentNodeRouterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/AgentNodeRouter.java
git add copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/AgentNodeRouterTest.java
git commit -m "refactor(agent): dispatch DAG nodes via A2aAgentRegistry in AgentNodeRouter"
```

---

### Task 5: Full Regression Testing & Verification

**Files:**
- Test: All tests in `copilot-agent-core` and `copilot-app`.

- [ ] **Step 1: Run complete `copilot-agent-core` test suite**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test`
Expected: PASS across all 160+ unit tests without any failures.

- [ ] **Step 2: Run complete `copilot-app` integration & wiring test suite**

Run: `mvn -s maven-settings.xml -pl copilot-app test`
Expected: PASS across all 55+ tests.

- [ ] **Step 3: Verification check and final branch status inspection**

Run: `git status`
Expected: Clean working tree on branch `main`.
