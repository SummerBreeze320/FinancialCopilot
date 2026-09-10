# 金融研究 Agent 架构设计规范书（公募基金深度实施版）

> **设计日期**：2026-09-10  
> **文档定位**：系统设计标准与实施基准规范 (Design Specification)  
> **设计日期**：2026-09-10  
> **文档定位**：系统设计标准与实施基准规范 (Design Specification)  
> **核心框架**：AgentScope Java 2.x + Spring Boot 3.3.x (Java 21 LTS, 启用虚拟线程)  
> **持久层规范**：Lombok + MyBatis-Plus 3.5.x + PostgreSQL 16 + PGVector  
> **推理模型底座**：**多厂商统一大模型适配架构 (Multi-Provider LLM Engine)**，预置支持 DeepSeek、OpenAI、阿里通义千问 (Qwen)、智谱清言 (GLM)、本地 Ollama 及自定义兼容端点，支持前端无缝动态热切换与超参数调优。  
> **领域定位**：**通用投研底座**，首期深度实施**公募基金（Fund）领域**，预留**股票（Stock）**、**期货（Futures）**、**银行理财（Wealth Management）**平滑扩展接口。

---

## 1. 业务目标与领域架构设计

### 1.1 可扩展分层体系（Multi-Asset Architecture）

为避免系统过度耦合于单一资产形态，整体架构划分为“**通用投研抽象层（Universal Asset Layer）**”与“**特定资产领域插件层（Asset Domain Plugins）**”：

```mermaid
graph TD
    User([投研分析师 / 机构投资者]) --> MultiAgentWorkflow[多智能体复合流水线 Multi-Agent Workflow]
    
    subgraph UniversalLayer [通用金融投研底座 (Universal Core)]
        AssetCategory[资产大类枚举: FUND / STOCK / FUTURES / WEALTH]
        AssetProfile[统一资产简档 AssetProfile]
        TaskDecomposer[复杂任务解构器 TaskDecomposer]
        ResearchBlackboard[投研黑板上下文 ResearchBlackboard]
        AssetDomainRegistry[资产领域策略注册中心 AssetDomainRegistry]
    end

    subgraph AssetPlugins [特定资产领域实现 (首期深度实现: Fund)]
        FundDomain[公募基金领域 (Fund Domain)]
        StockDomain[A股/港美股领域 (Stock Domain - 规划中)]
        FuturesDomain[大宗商品期货领域 (Futures Domain - 规划中)]
        WealthDomain[银行理财/信托领域 (Wealth Domain - 规划中)]
    end

    MultiAgentWorkflow --> UniversalLayer
    AssetDomainRegistry -->|动态分发| FundDomain
    AssetDomainRegistry -.->|未来扩展| StockDomain
    AssetDomainRegistry -.->|未来扩展| FuturesDomain
    AssetDomainRegistry -.->|未来扩展| WealthDomain
```

#### 统一抽象规范：
1. **`AssetCategory` 枚举**：定义资产类别（`FUND` 基金、`STOCK` 股票、`FUTURES` 期货、`WEALTH_MANAGEMENT` 银行理财）。
2. **`AssetProfile` 统一标的契约**：包含跨资产通用的基础字段（`assetCode`, `assetName`, `category`, `exchangeOrIssuer`, `latestPriceOrNav`, `updateDate`）。
3. **`AssetDomainStrategy` 策略接口体系**：
   - `AssetScreeningStrategy<C, R>`：资产智能初筛契约。
   - `AssetAnalysisStrategy<R>`：多维量化与基本面体检契约。
   - `AssetComparatorStrategy<R>`：多标的深度对标契约。
4. **`AssetDomainRegistry`**：根据用户指令由 Agent 识别出目标资产大类后，动态路由到对应的特定资产执行引擎（首期默认且深度注入 `FundDomainStrategy`）。

---

### 1.2 首期深度实施：公募基金领域实体模型

首期系统聚焦于公募基金领域的三个核心主体及其拓扑关联：**基金标的（Fund）、基金经理（Manager）、基金管理公司（Company）**。

```mermaid
erDiagram
    FUND_COMPANY ||--o{ FUND_MANAGER : "雇佣 / 投研支持"
    FUND_COMPANY ||--o{ FUND_PRODUCT : "发行与运作"
    FUND_MANAGER ||--o{ FUND_PRODUCT : "管理 / 共同管理"
    FUND_PRODUCT ||--o{ FUND_NAV_HISTORY : "每日复权净值时序"
    FUND_PRODUCT ||--o{ FUND_QUARTERLY_HOLDINGS : "季度穿透持仓"
    FUND_PRODUCT ||--o{ FUND_PERIODIC_REPORT : "季报/年报策略展望文本 (向量化)"
    FUND_MANAGER ||--o{ MANAGER_TRACK_RECORD : "历任基金业绩追踪"
```

#### 业务功能边界：
1. **智能多维筛选（Screener）**：
   - 自然语言转结构化查询条件（NL2DSL）。
   - 支持复合约束：基金分类（股票型/偏股混合/纯债/二级债基/QDII）、行业板块主题（医药、半导体、消费等）、近 1/3/5 年年化收益率、最大回撤阈值、夏普比率、卡玛比率、经理任职年限、在管规模、公司权益评级。
2. **多维立体分析（Analyzer）**：
   - **基金标的**：收益与超额特征、波动与回撤控制、最长回撤修复期、持仓集中度穿透、风格箱特征（大盘/小盘、价值/成长）。
   - **基金经理**：几何年化回报、代表作牛熊穿越表现、投资风格稳定性（风格漂移度检测）、选股与择时能力归因。
   - **基金公司**：非货/权益总管理规模、投研团队实力与流失率、旗下产品中长期同类胜率分布。
3. **深度横向对比（Comparator）**：
   - 两两对标或多标的对标。
   - 量化指标强对齐（雷达图量化数据）。
   - 季报定性观点对标（通过 PGVector 检索两名经理在多期季报中对宏观、资产配置方向的定性展望，对比投资哲学与知行合一性）。
4. **研报工坊与流式输出（Reporting）**：
   - 结构化合规投研报告生成。
   - 数据溯源脚注（Footnotes / Data Citation）。
   - Spring WebFlux SSE 响应式流式打字机输出。

---

## 2. 复杂投研问题解决设计（Composite Multi-Step Pipeline）

### 2.1 问题与挑战

对于简单的单意图场景（如“帮我筛选夏普大于1的医药基金”、“分析张坤的能力”、“对比易方达蓝筹与富国天惠”），单层路由调度完全足够。

然而，真实的专业投研场景往往是**高阶复合链式任务**，例如：
> “**帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议。**”

该场景下单意图 Router 必然失效，其具备四个关键特征：
1. **拓扑依赖链**：后一个步骤的输入严格依赖前一个步骤的产出（筛选结果 $\to$ 经理评估候选池 $\to$ Top 2 决赛圈 $\to$ 最终投资建议报告）。
2. **并发扇出/聚合（Fan-out / Fan-in）**：对前 5 名基金经理的量化和履历评估需要并发执行，然后通过打分算法聚合排序。
3. **上下文黑板共享（Blackboard Pattern）**：需要跨越多个 Agent 的共享上下文传递中间数据实体，而不是仅仅依靠大模型的长提示词聊天记录。
4. **过程透明与渐进式流式反馈（Step-by-Step Progress Events）**：长流水线运行可能需要 5~15 秒，系统必须通过 SSE 实时向用户上报各阶段执行状态。

---

### 2.2 复合投研流水线架构图

```mermaid
sequenceDiagram
    autonumber
    actor User as 投研用户
    participant Gateway as SSE Controller
    participant Decomposer as TaskDecomposer (规划器)
    participant Blackboard as ResearchBlackboard (上下文黑板)
    participant Screener as ScreenerAgent (选基专家)
    participant Analyzer as AnalyzerAgent (透视专家)
    participant Comparator as ComparatorAgent (对标专家)
    participant Reporter as ReportSynthesizer (研报主编)
    participant MathEngine as MathCore & DB (事实底座)

    User->>Gateway: 发起复合任务 (如: 筛选医药->评前5经理->比最优2个->投资建议)
    Gateway->>Decomposer: 解构复杂 Prompt
    Decomposer-->>Gateway: 返回有序拓扑计划 ExecutionPlan (4 Steps)
    Gateway-->>User: [SSE] 阶段事件: PLAN_READY (分解为4个执行阶段)

    Note over Gateway,Blackboard: 阶段 1: 筛选过去三年表现稳定医药基金
    Gateway-->>User: [SSE] 阶段事件: STEP_START [1/4 基金量化筛选]
    Gateway->>Screener: 执行条件提取与筛选
    Screener->>MathEngine: 查询符合条件的基金池 (指标过滤: 医药, 3年, 波动/回撤稳定)
    MathEngine-->>Screener: 返回候选基金列表 (如10只)
    Screener->>Blackboard: 写入 candidateFunds (10只基金及指标)
    Gateway-->>User: [SSE] 阶段事件: STEP_COMPLETE [初筛完成，选出10只候选医药基金]

    Note over Gateway,Blackboard: 阶段 2: 分析前 5 名基金经理能力 (Fan-Out 并发评估)
    Gateway-->>User: [SSE] 阶段事件: STEP_START [2/4 经理多维体检]
    Gateway->>Analyzer: 提取前5名经理，并行发起体检
    par 并发采集经理量化指标与在管产品
        Analyzer->>MathEngine: 评估经理 A (年化回报、最大回撤控制、从业稳定性)
        Analyzer->>MathEngine: 评估经理 B ...
        Analyzer->>MathEngine: 评估经理 E ...
    end
    Analyzer->>Analyzer: 综合量化评分排序，选出最优 2 名
    Analyzer->>Blackboard: 写入 managerRatings & top2Candidates
    Gateway-->>User: [SSE] 阶段事件: STEP_COMPLETE [完成前5名经理体检，选拔出最优秀2名决赛标的]

    Note over Gateway,Blackboard: 阶段 3: 决赛圈深度横向对标
    Gateway-->>User: [SSE] 阶段事件: STEP_START [3/4 深度横向对标]
    Gateway->>Comparator: 读取 top2Candidates，执行定量指标对齐 + 季报 RAG 语义对比
    Comparator->>MathEngine: 提取两只标的量化数据与季报观点向量切片
    MathEngine-->>Comparator: 返回事实矩阵与定期报告观点
    Comparator->>Blackboard: 写入 comparisonFactMatrix
    Gateway-->>User: [SSE] 阶段事件: STEP_COMPLETE [对标完成，提炼出攻守特性与投资哲学差异]

    Note over Gateway,Blackboard: 阶段 4: 综合投资建议报告合成与流式输出
    Gateway-->>User: [SSE] 阶段事件: STEP_START [4/4 生成投资建议报告]
    Gateway->>Reporter: 结合 Blackboard 所有中间事实，合成专业研报
    Reporter-->>Gateway: 流式 Token 吐出
    Gateway-->>User: [SSE] 流式 Markdown 打字机输出最终报告
    Gateway-->>User: [SSE] 阶段事件: WORKFLOW_COMPLETE
```

---

### 2.3 关键组件技术设计

#### 1. `TaskDecomposer`（任务解构器）
通过专门调优的 Few-Shot Prompt 指导 DeepSeek 将用户输入解析为强类型 `ExecutionPlan`：
```json
{
  "isComplex": true,
  "summary": "三年稳定医药基金筛选 -> 前5名经理多维评估 -> Top2横向对标 -> 最终配置建议",
  "steps": [
    {
      "stepIndex": 1,
      "taskType": "SCREENING",
      "description": "筛选过去三年表现稳定的医药主题基金",
      "params": {
        "sector": "医药",
        "minYears": 3,
        "maxDrawdownLimit": 30.0,
        "limit": 10
      },
      "outputKey": "candidateFunds"
    },
    {
      "stepIndex": 2,
      "taskType": "BATCH_ANALYSIS",
      "description": "评估候选基金排名前5的基金经理专业能力并打分",
      "dependsOn": [1],
      "inputKey": "candidateFunds",
      "params": { "topN": 5, "selectBest": 2 },
      "outputKey": "topCandidates"
    },
    {
      "stepIndex": 3,
      "taskType": "COMPARISON",
      "description": "对排名前2的最优标的进行全方位定量与定性季报对标",
      "dependsOn": [2],
      "inputKey": "topCandidates",
      "outputKey": "comparisonFacts"
    },
    {
      "stepIndex": 4,
      "taskType": "SYNTHESIS",
      "description": "汇总前序事实，生成包含配置逻辑与风险提示的最终投资建议",
      "dependsOn": [1, 2, 3],
      "outputKey": "finalReport"
    }
  ]
}
```
*注：对于简单单意图请求，`TaskDecomposer` 仅生成 1 个步骤的 Plan，无缝退化为即时单步处理。*

#### 2. `ResearchBlackboard`（投研黑板）
作为流水线的状态总线与上下文载体：
```java
public class ResearchBlackboard {
    private final Map<String, Object> state = new ConcurrentHashMap<>();

    public void put(String key, Object value) { state.put(key, value); }
    public <T> T get(String key, Class<T> clazz) { return clazz.cast(state.get(key)); }
    public boolean has(String key) { return state.containsKey(key); }
}
```

#### 3. Fan-Out 并发评估 (Java 21 虚拟线程)
在阶段 2 中，针对 Top 5 基金经理的多维度体检，利用 Java 21 `Executors.newVirtualThreadPerTaskExecutor()` 瞬间并发发起指标计算与数据拉取，随后聚合（Fan-In）送入综合评分模型，执行耗时由串行的 5 秒缩减至 500ms 以内。

---

## 3. 技术栈规范：Lombok + MyBatis-Plus + PostgreSQL

系统全面拥抱 **Lombok** 消除 Java 模板冗余，持久层统一采用 **MyBatis-Plus 3.5.x for Spring Boot 3**，杜绝 Spring Data JPA 与原生复杂 XML 映射配置。

### 3.1 实体（PO）定义规范
所有数据库实体统一放置于 `copilot-data-engine` 模块，标注 `@TableName`, `@TableId`, `@TableField` 与 Lombok 注解：

```java
package com.financial.copilot.data.fund.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_info")
public class FundInfoPO {
    @TableId
    private String fundCode;
    private String fundName;
    private String fundType;
    private LocalDate establishmentDate;
    private String managementCompanyId;
    private BigDecimal currentScaleBillion;
    private String trackingBenchmark;
}
```

### 3.2 Mapper 规范（MyBatis-Plus `BaseMapper`）
单表 CRUD 与常规字段筛选直接采用 `BaseMapper<T>` 配合 `LambdaQueryWrapper<T>`，简洁、类型安全、重构友好：

```java
package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundInfoPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FundInfoMapper extends BaseMapper<FundInfoPO> {
    // 基础单表增删改查完全无需任何 XML 配置！
}
```

### 3.3 PGVector 向量检索支持（MyBatis-Plus 注解/XML 实现）
对于季报切片高维文本向量（Embedding 维度 1536），在 MyBatis-Plus 中通过自定义注解查询直接映射 PostgreSQL 的 `<=>`（余弦距离）操作符：

```java
package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundReportVectorPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface FundReportVectorMapper extends BaseMapper<FundReportVectorPO> {

    @Select("""
        SELECT id, fund_code, report_quarter, section_title, content, created_at,
               (embedding <=> #{queryVector}::vector) AS distance
        FROM fund_report_vector
        WHERE fund_code IN (${fundCodes})
        ORDER BY distance ASC
        LIMIT #{topK}
    """)
    List<FundReportVectorPO> searchSimilarSections(
            @Param("fundCodes") String fundCodesCommaSeparated,
            @Param("queryVector") String queryVectorJson,
            @Param("topK") int topK);
}
```

---

## 4. 系统分层与 Maven 工程模块设计

清晰划分“通用底座”与“基金专属领域模块”的包结构：

```text
financial-copilot/
├── pom.xml                                  // 父 POM：管理 Java 21、Spring Boot 3.3.x、MyBatis-Plus、Lombok
├── copilot-common/                          // 通用资产模型、枚举、统一结果集
│   └── src/main/java/com/financial/copilot/common/
│       ├── enums/AssetCategory.java         // [核心] FUND, STOCK, FUTURES, WEALTH
│       ├── model/AssetProfile.java          // [核心] 统一资产简档
│       ├── result/ApiResult.java            // 全局统一结果封装
│       └── fund/dto/                        // 基金专属 DTO (FundScreeningCriteria, FundMetricsDTO)
├── copilot-domain/                          // 领域模型与核心业务抽象
│   └── src/main/java/com/financial/copilot/domain/
│       ├── core/strategy/AssetDomainStrategy.java // [核心] 通用资产策略接口
│       ├── core/registry/AssetDomainRegistry.java // [核心] 策略路由注册中心
│       └── fund/                            // [首期深度实现] 基金领域模型
│           ├── entity/ (FundInfo, FundManager, FundCompany, FundNavHistory, FundHolding)
│           └── port/FundDataPort.java       // 基金数据访问 SPI
├── copilot-math-core/                       // 纯 Java 原生金融指标计算库 (夏普/回撤/卡玛/胜率/Brinson)
├── copilot-data-engine/                     // 数据访问层：Lombok + MyBatis-Plus + PGVector
│   └── src/main/java/com/financial/copilot/data/
│       └── fund/                            // 基金数据持久化
│           ├── po/ (FundInfoPO, FundManagerPO, FundNavHistoryPO, FundReportVectorPO)
│           ├── mapper/ (FundInfoMapper, FundNavHistoryMapper, FundReportVectorMapper)
│           └── adapter/DatabaseFundDataAdapter.java // 适配 FundDataPort
├── copilot-agent-tools/                     // AgentScope 工具包
│   └── src/main/java/com/financial/copilot/agent/tools/
│       └── fund/                            // 基金专业工具 (Tool-as-Truth)
│           ├── FundScreeningTool.java       // 基金初筛工具
│           ├── FundQuantAnalysisTool.java   // 基金量化体检工具
│           ├── FundHoldingsQueryTool.java   // 基金持仓透视工具
│           └── FundReportRetrieverTool.java // 季报 RAG 语义检索工具
├── copilot-agent-core/                      // AgentScope 多智能体编排与复合流水线
│   └── src/main/java/com/financial/copilot/agent/core/
│       ├── pipeline/                        // [复合任务核心]
│       │   ├── TaskDecomposer.java          // 复杂意图解构与 ExecutionPlan 生成
│       │   ├── ResearchBlackboard.java      // 投研黑板上下文总线
│       │   └── ExecutionPlan.java           // 计划与子任务模型
│       ├── agents/                          // 专家智能体
│       │   ├── ScreenerAgent.java
│       │   ├── AnalyzerAgent.java
│       │   ├── ComparatorAgent.java
│       │   └── ReportSynthesizer.java
│       └── workflow/FinancialResearchWorkflow.java // 流水线编排中枢
└── copilot-app/                             // Web 启动端点、REST API 与 SSE 流式控制器
```

---

## 5. PostgreSQL 16 + PGVector 数据存储模型设计

统一使用一套 PostgreSQL 数据库，兼顾关系型业务表与高维文本向量表。

### 5.1 核心 DDL 架构
```sql
-- 1. 启用 pgvector 插件
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 基金基础信息表 (fund_info)
CREATE TABLE IF NOT EXISTS fund_info (
    fund_code VARCHAR(10) PRIMARY KEY,
    fund_name VARCHAR(100) NOT NULL,
    fund_type VARCHAR(50) NOT NULL,            -- 股票型 / 偏股混合型 / 债券型 / 指数型
    establishment_date DATE NOT NULL,
    management_company_id VARCHAR(20) NOT NULL,
    current_scale_billion NUMERIC(10, 2),      -- 最新规模 (亿元)
    tracking_benchmark TEXT                    -- 业绩比较基准
);

-- 3. 基金经理档案表 (fund_manager)
CREATE TABLE IF NOT EXISTS fund_manager (
    manager_id VARCHAR(20) PRIMARY KEY,
    manager_name VARCHAR(50) NOT NULL,
    company_id VARCHAR(20) NOT NULL,
    working_days INT NOT NULL,                 -- 从业天数
    current_total_scale_billion NUMERIC(10, 2) -- 在管总规模 (亿元)
);

-- 4. 基金-经理任职映射表 (fund_manager_mapping)
CREATE TABLE IF NOT EXISTS fund_manager_mapping (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) REFERENCES fund_info(fund_code),
    manager_id VARCHAR(20) REFERENCES fund_manager(manager_id),
    start_date DATE NOT NULL,
    end_date DATE,                             -- NULL 表示现任
    is_current BOOLEAN DEFAULT TRUE
);

-- 5. 基金每日复权净值时序表 (fund_nav_history)
CREATE TABLE IF NOT EXISTS fund_nav_history (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) NOT NULL,
    nav_date DATE NOT NULL,
    unit_nav NUMERIC(10, 4) NOT NULL,          -- 单位净值
    accumulated_nav NUMERIC(10, 4) NOT NULL,   -- 累计净值
    daily_growth_rate NUMERIC(8, 4),           -- 日涨跌幅 (%)
    CONSTRAINT uk_fund_nav_date UNIQUE(fund_code, nav_date)
);

-- 6. 季度前十大持仓明细表 (fund_quarterly_holdings)
CREATE TABLE IF NOT EXISTS fund_quarterly_holdings (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) NOT NULL,
    report_quarter VARCHAR(10) NOT NULL,       -- 如: '2024Q2'
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(100) NOT NULL,
    holding_ratio NUMERIC(6, 2) NOT NULL,      -- 占净值比 (%)
    holding_sector VARCHAR(50) NOT NULL        -- 所属行业板块 (申万一级)
);

-- 7. 基金定期报告定性文本向量库 (fund_report_vector)
CREATE TABLE IF NOT EXISTS fund_report_vector (
    id BIGSERIAL PRIMARY KEY,
    fund_code VARCHAR(10) NOT NULL,
    report_quarter VARCHAR(10) NOT NULL,
    section_title VARCHAR(100),                -- 例如: "管理人对报告期内运作分析与展望"
    content TEXT NOT NULL,                     -- 文本切片正文
    embedding vector(1536),                    -- OpenAI/DeepSeek 兼容维度
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fund_report_vector_hnsw 
ON fund_report_vector USING hnsw (embedding vector_cosine_ops);
```

---

## 6. 金融计算核心工具集（Tool-as-Truth 防幻觉体系）

在 Java 模块 `copilot-math-core` 中以高精度、确定性的 Java 数学算法实现各项核心指标，严禁依赖大模型心算：
- **最大回撤 (Max Drawdown)**：精准寻找峰值与后续最低谷值的穿透最大跌幅。
- **夏普比率 (Sharpe Ratio)**：超额年化收益率与年化波动率之比。
- **卡玛比率 (Calmar Ratio)**：年化收益率与最大回撤绝对值之比，直观反映承受单位极值风险带来的收益补偿。
- **胜率与回撤修复期 (Win Rate & Recovery Days)**：计算历史滚动季度胜率与最长水下持续天数。

---

## 7. 阶段式 SSE 消息协议规范（用户交互与前端体验）

为支持复杂长链路投研的流畅交互体验，系统在 `/api/v1/research/chat/stream` 端点输出规范化的 JSON 格式 SSE 流：

```json
event: plan
data: {"type":"PLAN","stepCount":4,"summary":"筛选医药基金->评估前5名经理->Top2对标->生成建议"}

event: step_start
data: {"type":"STEP_START","stepIndex":1,"totalSteps":4,"taskType":"SCREENING","title":"正在根据稳定性指标初筛医药基金..."}

event: step_complete
data: {"type":"STEP_COMPLETE","stepIndex":1,"summary":"初筛完成，命中10只医药基金候选标的"}

event: step_start
data: {"type":"STEP_START","stepIndex":2,"totalSteps":4,"taskType":"BATCH_ANALYSIS","title":"并发体检前5名基金经理任职表现..."}

event: message
data: {"type":"CONTENT","chunk":"### 投资建议报告\n\n基于对过去三年医药行业表现稳定基金的筛选..."}

event: done
data: {"type":"DONE"}
```

---

## 8. 实施里程碑与验收标准

1. **里程碑 1 (Week 1)：通用接口定义与 MyBatis-Plus 持久层改造**
   - 确立 `AssetCategory`, `AssetProfile`, `AssetDomainStrategy`, `AssetDomainRegistry`。
   - 在 `copilot-data-engine` 引入 `mybatis-plus-spring-boot3-starter` 与 `lombok`，编写 `FundInfoPO` 及对应 Mapper。
   - 验证 H2/PostgreSQL 环境下单表 CRUD 与向量查询单元测试。
2. **里程碑 2 (Week 2)：复杂任务解构器 (TaskDecomposer) 与黑板总线 (ResearchBlackboard)**
   - 实现 `TaskDecomposer`，基于 DeepSeek 准确解构“筛选->评估->对比->报告”复合意图。
   - 实现 `ResearchBlackboard` 上下文状态保存与传递机制。
   - 单测验证简单意图（1步）与复合意图（4步）的解构与黑板流转。
3. **里程碑 3 (Week 3)：并发评估引擎与复合流水线编排**
   - 实现基于 Java 21 虚拟线程的 Fan-Out / Fan-In 经理批量评估。
   - 升级 `FinancialResearchWorkflow` 为流水线编排器。
   - 将 `copilot-agent-tools` 组织到 `fund` 专属包，明确资产隔离边界。
4. **里程碑 4 (Week 4)：阶段式 SSE 交互与端到端复杂场景闭环**
   - 完善 WebFlux 控制器，输出阶段事件（`step_start`, `step_complete`）与报告打字机流。
   - 验证典型复合投研用例：“帮我筛选过去三年表现稳定的医药基金，然后分析前 5 名基金经理的能力，再比较其中最优秀的两个，最后生成投资建议”。

---

## 9. 多厂商大模型适配底座与前端动态调参架构设计 (Multi-Provider LLM Engine)

### 9.1 架构设计理念
为满足不同机构和个人投资者在不同场景下的模型选择诉求（如：高精度推理采用 DeepSeek-R1 / OpenAI o1，低成本通用筛选采用 DeepSeek-V3 / Qwen-Plus，合规私有化场景采用本地 Ollama / vLLM），系统将大模型通信层全面解耦并抽象为**多厂商统一适配底座**。

```mermaid
graph TD
    Frontend([前端 Web 终端 / 设置抽屉]) -->|1. 查询可用厂商与模型 GET /api/v1/llm/providers| LlmConfigController[LLM 配置管理控制器 LlmConfigController]
    Frontend -->|2. 修改全局默认配置 POST /api/v1/llm/config| LlmConfigController
    Frontend -->|3. 连通性测试 POST /api/v1/llm/test| LlmConfigController
    Frontend -->|4. 会话请求携带参数 LlmSettingsDTO| ResearchAgentController[投研接口控制器 ResearchAgentController]

    ResearchAgentController --> FinancialResearchWorkflow[投研总工作流 FinancialResearchWorkflow]
    FinancialResearchWorkflow --> ResearchBlackboard[投研黑板 ResearchBlackboard (存储生效模型设置)]

    subgraph MultiProviderEngine [多厂商大模型通信底座 Multi-Provider Engine]
        LlmService[统一大模型服务接口 LlmService]
        LlmProviderRegistry[厂商元数据与模型字典注册表 LlmProviderRegistry]
        LlmConfigManager[全局动态配置管理器 LlmConfigManager]
        LlmWebClientFactory[动态高性能 WebClient 缓存工厂 LlmDynamicWebClientFactory]
        DefaultLlmService[统一 OpenAI 驱动实现 DefaultLlmService]
    end

    LlmService --> DefaultLlmService
    DefaultLlmService --> LlmProviderRegistry
    DefaultLlmService --> LlmConfigManager
    DefaultLlmService --> LlmWebClientFactory

    subgraph CloudAndLocalProviders [支持的底层模型生态]
        DeepSeek[DeepSeek 官方 API (deepseek-chat / deepseek-reasoner)]
        OpenAI[OpenAI 官方 API (gpt-4o / gpt-4o-mini / o1 / o3-mini)]
        Qwen[阿里百炼 DashScope (qwen-plus / qwen-max / qwen-turbo)]
        Zhipu[智谱清言 GLM (glm-4-plus / glm-4-flash)]
        Ollama[本地私有化 Ollama (deepseek-r1 / qwen2.5 / llama3)]
        Custom[自定义 OpenAI 兼容私有网关]
    end

    LlmWebClientFactory --> DeepSeek
    LlmWebClientFactory --> OpenAI
    LlmWebClientFactory --> Qwen
    LlmWebClientFactory --> Zhipu
    LlmWebClientFactory --> Ollama
    LlmWebClientFactory --> Custom
```

### 9.2 预置支持的厂商与模型矩阵

| 厂商标识 (`LlmProviderType`) | 厂商显示名称 | 默认 Base URL | 典型推荐模型 | 场景定位与特点 |
| :--- | :--- | :--- | :--- | :--- |
| `DEEPSEEK` (默认) | DeepSeek 深度求索 | `https://api.deepseek.com/v1` | `deepseek-chat`<br>`deepseek-reasoner` | 高性价比金融长文本投研分析与思维链推理 |
| `OPENAI` | OpenAI | `https://api.openai.com/v1` | `gpt-4o`<br>`gpt-4o-mini`<br>`o1` | 业界标杆综合推理与结构化输出能力 |
| `QWEN` | 阿里通义千问 (百炼) | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus`<br>`qwen-max`<br>`qwen-turbo` | 国内合规首选，具备优秀中文金融语义理解 |
| `ZHIPU` | 智谱清言 (BigModel) | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-plus`<br>`glm-4-flash` | 高速响应与企业级中文金融语料调优 |
| `OLLAMA` | Ollama 本地部署 | `http://localhost:11434/v1` | `deepseek-r1:8b`<br>`qwen2.5:14b` | 完全离线私有化运行，零数据出境合规保障 |
| `CUSTOM` | 自定义兼容端点 | 用户自填 | 用户自填 | 适配私有私有云网关 (如 vLLM、OneAPI、SiliconFlow) |

### 9.3 运行时性能档位与思考强度规范（HIGH / MIDDLE / LOW）

针对大模型的性能与推理开销调节，系统在 `LlmSettingsDTO` 中原生提供面向业务直观易用的 **“高中低” 三档调节器 (`LlmPerformanceLevel`)**：

| 档位枚举 (`LlmPerformanceLevel`) | 档位显示名称 | 推理思考强度 (`reasoning_effort`) | 默认采样温度 (`temperature`) | 最大生成 Token (`maxTokens`) | 投研业务场景定位 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`LOW`** | **低 (极速 / 低耗)** | `"low"` | `0.0` (确定性最高) | `2048` | 快速自然语言意图初筛、代码实体提取、低延迟快速响应场景 |
| **`MIDDLE`** (默认) | **中 (标准 / 平衡)** | `"medium"` | `0.2` (微创新平衡) | `4096` | 标准基金经理量化体检、研报定性观点检索、两两双标的常规横向对标 |
| **`HIGH`** | **高 (深度 / 强化)** | `"high"` | `0.4` (多维推演) | `8192` | 跨周期深度长程归因、重仓股风格漂移穿透、CIO 级别资产配置与综合研报终审 |

#### 双模兼容与智能映射机制：
1. **一键预设应用 (Preset Mode)**：前端界面直接提供 `[ 低 (Low) | 中 (Middle) | 高 (High) ]` 分段控制器。用户切换档位时，系统自动联动预设的最佳 `temperature` 与 `maxTokens`；
2. **推理模型深度集成 (Reasoning Effort)**：当所选模型具备深度思考能力（如 OpenAI `o1`、`o3-mini`、`deepseek-reasoner` 等）时，系统自动将档位映射为标准 API 字段 `reasoning_effort: "low" | "medium" | "high"` 随 Payload 提交给大模型；
3. **高级微调覆盖 (Custom Override)**：若前端用户在高级设置中手动调整了数值型滑块（如显式指定了 `temperature = 0.7` 或 `maxTokens = 6000`），系统以用户显式指定的数值为最高优先级。

### 9.4 运行时参数双层解析机制（Per-Request Override）

系统采用“**全局系统默认 + 请求级动态热覆盖**”的解析模式：
1. **系统默认层 (`application.yml` + `LlmConfigManager`)**：定义未传参时的默认厂商（默认 DeepSeek）、默认模型、默认档位（`MIDDLE`）等基础设定；
2. **请求级动态覆盖 (`LlmSettingsDTO`)**：前端在界面上提供模型选择下拉框与性能超参数滑块。请求到达后端时，若包含非空设置字段，将**动态覆盖**系统默认值，仅对当前会话/请求生效；
3. **全流程上下文穿透**：工作流入口将生效的 `LlmSettingsDTO` 存入 `ResearchBlackboard`，确保在多步骤链路中（筛选 -> 体检 -> 对标 -> 合成）所有子智能体保持完全一致的模型与档位配置。

### 9.5 接口规范（RESTful & SSE）

1. **`GET /api/v1/llm/providers`**：获取系统内置的所有厂商元数据、可用模型列表及高中低档位配置模板；
2. **`GET /api/v1/llm/config`**：获取当前系统生效的全局默认大模型配置（含默认厂商、模型、高中低档位）；
3. **`POST /api/v1/llm/config`**：更新系统全局默认大模型配置；
4. **`POST /api/v1/llm/test`**：测试指定厂商、端点、API Key 与模型的连通性及网络延迟；
5. **`POST /api/v1/research/chat`**：同步研报生成接口，请求体中包含可选字段 `llmSettings`（支持传递 `performanceLevel: "HIGH"|"MIDDLE"|"LOW"`）；
6. **`GET /api/v1/research/chat/pipeline/stream`**：阶段式 SSE 流式接口，URL 查询参数支持可选传入 `provider`, `model`, `performanceLevel`, `temperature`, `topP`, `maxTokens`。

### 9.6 彻底去兼容化设计重构路线

- 彻底删除旧版单一模型配置类 `DeepSeekModelConfig` 与单体服务 `DeepSeekClientService`；
- 统一建立 `LlmService` 标准契约；
- 全工程 6 大核心 Agent（`TaskDecomposer`, `PlannerAgent`, `ReportSynthesizer`, `FundScreenerAgent`, `StockScreenerAgent`, `FundComparatorAgent`）全量直接依赖 `LlmService`；
- 严格遵守 Lombok 风格与全量中文 Javadoc 注释规范。

---

## 10. 商业化运营与 Token 计量计费系统规范 (Token Metering, Billing & Commercialization)

### 10.1 需求背景与产品模式定义 (PRD - 业务全景)

作为专业级金融多资产投研 Copilot，系统在底层依赖多厂商异构大模型（DeepSeek-V3/R1、GPT-4o/o1、Qwen-Plus、GLM-4 等）进行深度计算、意图解构与长文研报生成。为实现商业化可持续闭环（SaaS / ToB 机构私有化授权 / ToC 投顾会员），系统构建了**企业级 Token 计量、计费、充值与账户钱包中心**。

#### 商业化计费模式：
1. **“点数制”统一虚拟货币（Compute Points / 智算点）**：
   - **基准汇率**：`1 元人民币 (CNY) = 10,000 智算点`；
   - 对客户屏蔽不同厂商极其零碎的“0.0015元/千tokens”小数感知，转换为整数点数扣减；
2. **按量计费（Pay-as-you-go）+ 预付费规格包（Prepaid Packages）**：
   - 客户通过在线购买充值包获得账户点数余额；
   - 每次投研根据使用的实际模型、输入 Token 与输出 Token 实时精确扣费；
3. **前置额度保护与并发流控 (Pre-check & Rate Limiting)**：
   - 发起投研前执行钱包余额探测，低于起步门槛（如 100 点）优雅拦截并引导充值；
   - 欠费熔断保障服务商不被无限薅羊毛。

---

### 10.2 多模型阶梯定价矩阵 (Pricing Matrix)

平台支持运营人员在管理后台动态维护各厂商模型的单价策略（支持输入、输出及 Prompt 缓存命中差异化定价）：

| 厂商 | 模型标识 (`model_name`) | 输入单价 (点 / 1k Tokens) | 输出单价 (点 / 1k Tokens) | 缓存命中单价 (点 / 1k Tokens) | 对应法币成本与场景设计 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **DeepSeek** | `deepseek-chat` (V3) | `10 点` (0.001元) | `20 点` (0.002元) | `2 点` (0.0002元) | 低成本极速初筛，主打性价比 |
| **DeepSeek** | `deepseek-reasoner` (R1) | `40 点` (0.004元) | `160 点` (0.016元) | `10 点` (0.001元) | 深度思维链推演，主打专业级投研 |
| **OpenAI** | `gpt-4o-mini` | `15 点` (0.0015元) | `60 点` (0.006元) | `7.5 点` | 轻量级高吞吐分析 |
| **OpenAI** | `gpt-4o` | `250 点` (0.025元) | `1000 点` (0.1元) | `125 点` | 业界标杆级综合金融对标 |
| **OpenAI** | `o1` / `o3-mini` | `300 点` (0.03元) | `1200 点` (0.12元) | `150 点` | 复杂量化归因与极端压力测试推演 |
| **阿里千问** | `qwen-plus` | `8 点` (0.0008元) | `20 点` (0.002元) | `2 点` | 国内合规主流金融投研 |
| **本地私有** | `ollama/*` | `0 点` (或固定通道费) | `0 点` | `0 点` | 机构本地部署硬件，零模型 API 费 |

---

### 10.3 商业化数据库实体模型设计 (PostgreSQL 16)

```mermaid
erDiagram
    sys_user_wallet ||--o{ llm_token_usage_ledger : "产生消费流水"
    sys_user_wallet ||--o{ sys_recharge_order : "产生充值订单"
    sys_recharge_package ||--o{ sys_recharge_order : "选择套餐"
    llm_model_pricing ||--o{ llm_token_usage_ledger : "套用定价规则"

    sys_user_wallet {
        bigint id PK "钱包主键 ID"
        bigint user_id UK "用户 ID"
        varchar tenant_id "多租户/机构 ID"
        bigint balance_points "可用算力点余额"
        bigint frozen_points "冻结算力点 (并发投研占用)"
        bigint total_recharged_points "累计充值点数"
        bigint total_consumed_points "累计消耗点数"
        varchar wallet_status "状态: NORMAL(正常), ARREARS(欠费), FROZEN(冻结)"
        bigint version "乐观锁版本号"
        timestamp updated_at "更新时间"
    }

    llm_model_pricing {
        bigint id PK "主键 ID"
        varchar provider_type "厂商标识 (DEEPSEEK, OPENAI等)"
        varchar model_name UK "模型名称 (如 deepseek-reasoner)"
        decimal input_price_per_k "每千 Token 输入点数"
        decimal output_price_per_k "每千 Token 输出点数"
        decimal cache_hit_price_per_k "每千 Token 缓存命中点数"
        boolean is_active "是否启用"
    }

    llm_token_usage_ledger {
        bigint id PK "流水主键 ID"
        bigint user_id "用户 ID"
        varchar session_id "投研会话/任务跟踪 ID"
        varchar task_type "步骤类型 (SCREENING, ANALYSIS, COMPARISON, SYNTHESIS)"
        varchar provider "实际使用厂商"
        varchar model "实际使用模型"
        integer prompt_tokens "输入 Token 数量"
        integer completion_tokens "输出 Token 数量"
        integer total_tokens "总 Token 数量"
        bigint consumed_points "扣减智算点"
        integer latency_ms "响应耗时 (毫秒)"
        timestamp created_at "扣费入账时间"
    }

    sys_recharge_package {
        bigint id PK "套餐主键 ID"
        varchar package_name "套餐名称 (如 体验包 / 专业包 / 机构包)"
        decimal price_cny "标价人民币 (元)"
        bigint granted_points "基础充值点数"
        bigint bonus_points "赠送福利点数"
        varchar badge "角标说明 (如 热销推荐 / 送20%等)"
        integer sort_order "排序权重"
        boolean is_active "是否上架"
    }

    sys_recharge_order {
        bigint id PK "订单主键 ID"
        varchar order_no UK "唯一交易流水号"
        bigint user_id "充值用户 ID"
        bigint package_id "充值套餐 ID"
        decimal pay_amount_cny "实付金额 (元)"
        bigint target_points "到账总点数"
        varchar pay_channel "支付渠道: WECHAT(微信), ALIPAY(支付宝), BANK(对公)"
        varchar order_status "状态: PENDING(待支付), PAID(已支付), CANCELLED(已取消)"
        varchar third_party_trade_no "第三方支付凭单号"
        timestamp paid_at "支付到账时间"
    }
```

---

### 10.4 核心业务处理流程（时序交互）

```mermaid
sequenceDiagram
    autonumber
    actor User as 投研客户 / 分析师
    participant Web as 前端界面 (Web UI)
    participant Controller as 投研调度器 (ResearchController)
    participant Billing as 计费钱包中心 (WalletBillingService)
    participant LLM as 多厂商大模型驱动 (LlmService)
    participant DB as 数据库 (PostgreSQL 16)

    User->>Web: 提交投研问题 (如 "筛选医药基金并对比前两名")
    Web->>Controller: POST /chat 或 SSE /chat/pipeline/stream (携带 userId & llmSettings)
    
    Note over Controller,Billing: 1. 前置配额与余额校验 (Pre-check)
    Controller->>Billing: checkBalance(userId, minThreshold=100点)
    Billing->>DB: 查询 sys_user_wallet 余额
    alt 余额不足 (balance < 100)
        Billing-->>Controller: 抛出 WalletInsufficientException
        Controller-->>Web: 返回 402 Payment Required ("智算点不足，请前往充值")
        Web-->>User: 弹出快捷充值收银台抽屉
    else 余额充足
        Billing-->>Controller: 校验通过 (允许执行)
    end

    Note over Controller,LLM: 2. 流水线执行与 Token 消耗统计
    Controller->>LLM: 调度执行初筛/评估/对比/报告合成
    LLM-->>Controller: 返回投研成果 + Usage 元数据 (promptTokens, completionTokens)

    Note over Controller,Billing: 3. 异步原子扣费与对账记账 (Billing Ledger)
    Controller->>Billing: deductTokenPoints(userId, model, promptTokens, completionTokens)
    Billing->>DB: 根据 llm_model_pricing 计算本次消耗点数
    Billing->>DB: 扣减 sys_user_wallet.balance_points (乐观锁防超扣)
    Billing->>DB: 写入不可篡改账单流水 llm_token_usage_ledger

    Controller-->>Web: 推送完整报告 + 本次消耗摘要 (模型、Token数、消耗点数)
    Web-->>User: 呈现报告，并在报告底部与顶栏实时刷新剩余可用算力点
```

---

### 10.5 前端交互与数据可视化规范 (UI/UX Specification)

1. **顶栏“算力点数胶囊” (Global Compute Bar)**：
   - 顶部导航栏常驻展示：`⚡ 86,400 算力点`，鼠标悬浮显示“当前约可生成 43 份深度投研报告”；
   - 当点数低于 1,000 点时，图标变为橙黄色预警，并展示闪烁的“立即充值”按钮；
2. **研报卡片底部“消耗透明度仪表” (Transparency Footer)**：
   - 每一份由 AI 生成的专业投研报告末尾，自动附带极简暗色透明面板：
     > *本次投研生成由 **DeepSeek-Reasoner (R1)** 强力驱动 ｜ 消耗输入: 1,420 tokens, 输出: 3,890 tokens ｜ 本次计费: 68 智算点 (约合 ¥0.0068) ｜ 耗时: 6.4s*
3. **在线充值收银台 (Recharge Drawer / Modal)**：
   - 预设 4 款标准阶梯充值卡片（¥49 尝鲜版、¥199 进阶版、¥599 专业版、¥2999 机构旗舰版）；
   - 支持展示立省折扣与赠送点数徽章（“送 20% 点数”）；
   - 唤起微信支付/支付宝扫码即时到账，到账后基于 WebSocket / SSE 瞬间刷新用户前台点数。
4. **财务中心与消耗趋势报表 (Billing & Analytics Dashboard)**：
   - **消耗折线图/面积图**：展示过去 30 天每日 Token 消耗量与每日消费点数走势；
   - **资产模块分布饼图**：展示公募基金、个股、研报合成各模块的消耗占比；
   - **消费流水清单**：支持按时间、任务类型、模型筛选每一笔扣费详情，并支持一键导出 CSV 财务对账单。

---

### 10.6 商业化核心 RESTful API 清单

| 请求方式 | 端点路径 | 接口功能与业务描述 |
| :--- | :--- | :--- |
| `GET` | `/api/v1/billing/wallet` | 查询当前登录用户的钱包资产（可用点数、累计充值、累计消耗、钱包状态） |
| `GET` | `/api/v1/billing/packages` | 获取当前平台已上架的充值规格套餐列表 |
| `POST` | `/api/v1/billing/order/create` | 创建充值订单，生成唯一订单号与待支付收银台信息 |
| `POST` | `/api/v1/billing/order/pay-callback` | 第三方支付网关异步回调接口（验签、原子充值入账、更新钱包余额） |
| `GET` | `/api/v1/billing/ledger` | 分页查询用户的 Token 消费对账明细列表（支持按时间/模型筛选） |
| `GET` | `/api/v1/billing/stats/trend` | 查询过去 7 天 / 30 天每日消耗 Token 数量与点数统计趋势（供前端图表渲染） |
| `GET` | `/api/v1/billing/pricing` | 查询当前系统各厂商模型的公开计费单价矩阵 |

