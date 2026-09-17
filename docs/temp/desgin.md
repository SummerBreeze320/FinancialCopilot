# Configured Tool Real Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `copilot-agent-tools` 的配置化基金分析/对比工具，从“JSON 注册 + 模拟路由”补齐为“组件选择、真实外部执行、数据映射、LLM 蒸馏、前端工作台交付”的闭环。

**Architecture:** 参考 `D:\jlwang.zain\GIT\Wind.Fund.ScreeningAgent\src\fund_research` 的 `component_analysis` 工作台模型，但保留当前 Java 重构后的 `ToolDefinition -> ToolRegistry -> ToolExecutorRouter -> ToolExecutor` 主干。执行层按 provider 拆为 `http_invoke`、`mcp`、`data_agent` 三条真实通道，组件层补齐 process/command/params/columns/output 元数据，并在 agent facade 层将重轨组件送入 workspace/artifact。

**Tech Stack:** Java 17, Spring Boot, WebClient, AgentScope Java, Jackson, JSON 配置, Wind FundInfraWeb/C# Invoke, Wind MCP JSON-RPC, Expo DataAgent。

---

## 1. 当前结论

当前 `copilot-agent-tools` 并不是完全“只注册”。它已经具备：

- JSON 扫描与注册：`copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/configured/registry/ToolDefinitionLoader.java`
- 注册表：`ToolRegistry.java`
- 路由器：`ToolExecutorRouter.java`
- 三类执行器接口实现：`HttpToolExecutor.java`、`McpToolExecutor.java`、`LocalToolExecutor.java`
- AgentScope facade：`FundAnalysisToolSet.java`、`FundComparisonToolSet.java`
- Agent 注入：`FundAnalyzerAgent.java`、`FundComparatorAgent.java`

但真实闭环尚未完成：

- `McpToolExecutor` 未执行 `initialize` 和 `tools/call`，仅回显 `server/targetTool/arguments`。
- `HttpToolExecutor` 未调用 FundInfraWeb、C# Invoke 或 command，仅构造 `status=SUCCESS`。
- `data_agent` provider 复用 `HttpToolExecutor`，未接 Expo DataAgent。
- `ToolDefinitionLoader.parseComponent` 未把组件级 `command`、`params`、`columns`、`process`、`output` 读入强模型。
- facade 当前只返回 `textForLlm`，`visualComponents` 没有进入 workspace/artifact。
- `FundAnalyzerAgent` 和 `FundComparatorAgent` 的 evidence 仍强依赖旧工具 `fund_metrics`、`compare_metrics`。

## 2. 参考项目闭环

参考项目核心链路：

1. `fund_research/services/agent_runtime_toolkits.py`
   - `ToolkitRegistry.discover_tools()`
   - 注册 `component_analysis`、`component_data_read`、`financial_search`、`aggregate_search` 等 FunctionTool。

2. `fund_research/tools/component_creator.py`
   - `component_analysis(...)` 接收 `fundCodes/startDate/endDate/reportDates/dimensions/fundType`。
   - `resolve_component_analysis_components(...)` 读取 `component_analysis_mapping.json` 与组件目录。
   - 按基金数量、基金类型、维度、date_mode 选择组件。
   - 生成 workspace components，而不是直接把大数据塞进 LLM。

3. `fund_research/utils/tool_progress.py`
   - 工具执行后检测 workspace 变化。
   - 推送 workspace payload 给前端。
   - 对重点组件调用 `ComponentDataClient.read(context_id, ref_id)` 读取小范围数据，并用 reference 机制控制上下文膨胀。

4. `fund_research/integrations/mcp/client.py`
   - MCP 调用流程为 `initialize` -> `tools/call`。
   - 请求头携带 `wind.sessionid`。
   - 支持 JSON 与 `text/event-stream` 解析。

5. `fund_research/integrations/fundinfraweb/client.py`
   - POST `WIND_FUNDINFRAWEB_SERVICE_URL`。
   - payload 为 `{"Invokes":[{"Name": invokeName, "Parameters": {...}}]}`。
   - 响应取 `Results[0].Value`。

6. `fund_research/integrations/component_data/client.py`
   - POST `WIND_FUNDRESEARCH_SERVICE_URL/IR/{method}`。
   - 用于 `ComponentReader.GetContent`、`FundAgentTool.GetFundInfoListByWindCodesForInvoke` 等。

## 3. 当前 JSON 覆盖情况

当前目录：

- `copilot-agent-tools/src/main/resources/config/tools/analysis`
- `copilot-agent-tools/src/main/resources/config/tools/comparison`

当前配置化 tool：24 个。

| 范围 | 数量 | provider |
| --- | ---: | --- |
| 单基金分析 `ANALYSIS` | 13 | `http_invoke` 8，`mcp` 4，`data_agent` 1 |
| 多基金对比 `COMPARISON` | 11 | `http_invoke` 9，`mcp` 2 |

从参考项目 `component_analysis_mapping.json` 对齐后：

- 多基金对比主流组件基本覆盖。
- 货币型单基金 `cf_money_factsheet` 的 19 个规则均已覆盖。
- 缺口集中在非货币单基金、REITs 单基金、规模对比组件。

## 4. 缺失 JSON 组件清单

这些是参考项目 mapping 中存在，但当前 Java 配置未覆盖的组件。需要补充为新的 tool JSON，或合并进现有 tool JSON 的 `components`。

### 4.1 单基金非货币/普通型/指数型/QDII 组件缺失 23 个

来源目录：`fund_research/components/single_fund_analysis/cf_non_money_factsheet.json`

| 组件 | 维度 | 适用 |
| --- | --- | --- |
| `NonMoneyFactSheetIframe` | FactSheet | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyBasicInfo` | 基础 | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyCompanyTable` | 公司 | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyCompanyProductStructureTable` | 公司 | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyCompanyFundManagerTable` | 公司 | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyCurrentManagerTable` | 经理 | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyHistoryManagerTable` | 经理 | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyPerformanceChart` | 业绩 | range |
| `NonMoneyYearlyReturnChart` | 业绩 | snapshot |
| `NonMoneyRiskAnalysisTable` | 风险 | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyAssetAllocationChart` | 持仓 | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyIndustryAllocationChart` | 持仓 | 普通型、指数型、偏股型、QDII偏股 |
| `NonMoneyHeavyHoldStockTable` | 持仓 | 普通型、指数型、偏股型、FOF、QDII偏股 |
| `NonMoneyHeavyHoldBondTable` | 持仓 | 偏债型、QDII偏债 |
| `NonMoneyEquityStyle` | 持仓 | 偏股型 |
| `NonMoneyHistoryScaleChange` | 规模 | 普通型、指数型、偏股型、偏债型、FOF、QDII |
| `NonMoneyTradeInfo` | 费率 | 非货币默认 |
| `ETFDailyQuoteStatistics` | 业绩 | 指数型、REITs，range |
| `ETFQuotation` | 业绩 | 场内基金，always |
| `ETFMACD` | 业绩 | 指数型，always |
| `FinancingSecuritiesLending` | 业绩 | 指数型，range |
| `FundShare` | 规模 | 指数型，range |
| `ETFPCF` | 持仓 | 指数型，always |

### 4.2 单基金 REITs 组件缺失 7 个

来源目录：`fund_research/components/single_fund_analysis/cf_reits_factsheet.json`

| 组件 | 维度 |
| --- | --- |
| `ReitsFactSheetIframe` | FactSheet |
| `ReitsFundIntroduct` | 基础 |
| `ReitsCompanyTable` | 公司 |
| `ReitsCompanyProductStructureTable` | 公司 |
| `ReitsCompanyFundManagerTable` | 公司 |
| `ReitsCurrentManagerTable` | 经理 |
| `ReitsTradingInfo` | 费率 |

说明：`ReitsAssetsDetails`、`ReitsProjectOperation`、`ReitsFinancialIndicators` 已在当前多基金资产配置 JSON 中出现，但单基金 REITs 视角仍缺基础、公司、经理、交易信息和 iframe。

### 4.3 多基金规模组件缺失 3 个

来源目录：`fund_research/components/multi_fund_analysis/cf_scale.json`

| 组件 | 维度 | date_mode |
| --- | --- | --- |
| `Scale` | 规模 | snapshot |
| `ScaleTrend` | 规模 | range |
| `ETFScaleStatistics` | 规模 | range，指数型 |

建议新增 tool JSON：`config/tools/comparison/compare-scale.json`，toolId 可为 `compare_scale`，并在 `FundComparisonToolSet` 暴露 `compare_scale`。

## 5. 现有 JSON 需要完善的字段

### 5.1 组件级执行字段需要强类型化

当前 JSON 中已有大量字段，但 Loader 未完整读取：

- `components[].command`
- `components[].params`
- `components[].columns`
- `components[].metadata`
- `components[].url`
- `components[].linkId`

建议在 `UITreeComponent` 或新增 `ComponentProcessDefinition` 中保留：

- `providerSpec`: `cloud`、`fundinfra`、`astra`、`mcp`、`iframe`
- `command`
- `params`
- `columns`
- `outputKind`
- `dataType/chartType`
- `rawProcess`

### 5.2 MCP/DataAgent 组件需要响应映射文档

这些组件目前没有 `command/columns`，不能按 http_invoke 的列映射逻辑处理，需要为每个 targetTool 建立响应映射：

| toolId | target/provider | 组件 |
| --- | --- | --- |
| `compare_brinson` | `fund_get_brinson_attribution` | `brinson`, `brinsonChart` |
| `compare_similar` | `fund_get_similar` | `similar` |
| `fund_analysis_nav_attribution` | `fund_get_nav_attribution` | `nav` |
| `fund_analysis_position` | `fund_get_position_estimation` | `position` |
| `fund_analysis_style` | `fund_get_market_cap_style` | `style` |
| `fund_analysis_timing` | `fund_get_selection_timing_analysis` | `timing` |
| `fund_analysis_review` | DataAgent `T609` | `review` |

建议新增文档型配置或内嵌 JSON 节点：

```json
{
  "responseMapping": {
    "textPath": "data.Content.StructuredContent.Summary.Text",
    "tablePath": "data.Content.StructuredContent.Table.Rows",
    "chartPath": "data.Content.StructuredContent.Chart",
    "columns": {}
  }
}
```

## 6. 每类组件真实执行流程

### 6.1 `http_invoke` / `cloud` / `fundinfra` 组件

目标：执行现有 JSON 的 `components[].command`，并填充 `UITreeComponent.data/chartOption`。

流程：

1. Agent 调用 facade tool，例如 `compare_basic_info(fundCodes)`。
2. facade 组装 `ToolExecuteRequest.arguments`，至少包含 `windCodes`。
3. Router 根据 `provider=http_invoke` 进入 `HttpToolExecutor`。
4. Executor 遍历 `ToolDefinition.components`。
5. 对每个组件解析 `params`：
   - `amount:1` 表示缺省值。
   - `windCodes`、`startDate`、`endDate`、`reportDate` 来自请求参数或 date resolver。
   - `getDate(...)`、`getTradeCode(...)` 等函数式参数需要实现 `ParamExpressionResolver`。
6. 渲染 `command`：
   - `report name=... windCodes=[{windCodes}]`
   - `Matrix2 functions=...`
   - `FundCompare.*Picker.GetData`
   - `MCPHandler.*`
7. 根据 `command` 类型分派：
   - `report ...` / `Matrix2 ...`：走 C# Invoke 或当前系统约定的 `WIND_FUNDRESEARCH_SERVICE_URL/IR/{method}`。
   - `FundCompare.*`、`F9.*`、`MFCP.*`：优先按参考项目走 FundInfraWeb `Invokes`。
   - `MCPHandler.*` / `astra`：按 AIMarket/MCP handler 约定接入，不能混入普通 FundInfraWeb。
8. 解包响应。
9. 用 `columns` 做字段映射：
   - `Column0 -> windCode`
   - `Object/list/value -> value`
   - 支持数组展开、嵌套路径、重复记录 flatten。
10. 用 `metadata.table` 或 `metadata.{line/bar/radar}` 构造前端组件。
11. 调 `ComponentDataDistiller` 产出 `textForLlm`。
12. 返回 `ToolExecuteResult`，包含：
   - `textForLlm`
   - `visualComponents`
   - `rawData`
   - `errors` 或组件级错误列表。

### 6.2 `mcp` 组件

目标：执行 Wind MCP JSON-RPC，并把 MCP 返回结构映射到组件。

流程：

1. Router 根据 `provider=mcp` 进入 `McpToolExecutor`。
2. 从配置取 `server` 和 `targetTool`。
3. 用 `financial.copilot.tools.mcp.base-url` 组装 server URL：
   - `wind-fund-analysis -> {baseUrl}/vserver_fund_analysistest/mcp`
   - 后续可扩展 `wind-fund-data`、`wind-fund-holdings`。
4. 校验 `request.sessionId`，并在 header 带 `wind.sessionid`。
5. POST `initialize`：
   - `protocolVersion=2025-03-26`
   - `clientInfo.name=financial-copilot`
6. POST `tools/call`：
   - `params.name=definition.targetTool`
   - `params.arguments=request.arguments`
7. 支持普通 JSON 和 `text/event-stream`。
8. 提取 `content[0].text`，尝试 JSON parse。
9. 通过 `responseMapping` 或 targetTool 专用 mapper 生成组件 data/chart。
10. 蒸馏文本并返回双轨结果。

### 6.3 `data_agent` 组件

目标：支持 `fund_analysis_review` 的 DataAgent 模板 T609。

流程：

1. Router 根据 `provider=data_agent` 进入独立 `DataAgentToolExecutor`，不要继续挂在 `HttpToolExecutor`。
2. 参数要求：
   - `templateId=T609`
   - `traceId`
   - `userId`
   - `windCodes/fundCode`
   - `refresh`
3. 按参考项目 `ExpoDataAgentClient` 组装 `ExpoDataAgentRequest`。
4. 通过 Expo message bus 调用：
   - `WIND_EXPO_DATA_AGENT_APP_CLASS=1694`
   - `WIND_EXPO_DATA_AGENT_COMMAND_ID=23927`
   - `WIND_EXPO_DATA_AGENT_SERVICE_ID=2036`
   - `WIND_EXPO_DATA_AGENT_REFER_TYPE=COM.FundInfra.META`
5. 将返回的研报摘要/结构化文本填入 markdown 组件 `review`。
6. 蒸馏为 LLM 摘要，同时保留完整 review 文本给 workspace。

### 6.4 iframe 组件

目标：不外呼数据接口，只生成前端可渲染 URL。

流程：

1. 组件有 `url`，例如 factsheet iframe。
2. 用请求参数替换 `{windcode}` / `{windCodes}` / `{endDate}`。
3. 返回 `displayType=iframe` 组件。
4. `textForLlm` 只提示“已生成 F9 页面视图”，不要伪造数据结论。

## 7. 推荐文件改造清单

### Task 1: 配置模型补全

**Files:**

- Modify: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/configured/model/UITreeComponent.java`
- Modify: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/configured/model/ToolDefinition.java`
- Modify: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/configured/registry/ToolDefinitionLoader.java`
- Test: `copilot-agent-tools/src/test/java/com/financial/copilot/agent/tools/configured/ToolDefinitionLoaderTest.java`

- [ ] 增加组件级 `command/params/columns/process/output/responseMapping/rawMetadata`。
- [ ] Loader 读取现有 JSON 字段，保证 `compare_basic_info.FundInfoForDefault.command` 可被测试断言。
- [ ] 测试覆盖：任意 http 组件能读取 command、params、columns；iframe 能读取 url/linkId；mcp 组件能读取 responseMapping 或 raw metadata。

### Task 2: 参数解析与日期口径

**Files:**

- Create: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/configured/params/ConfiguredToolArgumentResolver.java`
- Create: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/configured/params/CommandTemplateRenderer.java`
- Test: `copilot-agent-tools/src/test/java/com/financial/copilot/agent/tools/configured/ConfiguredToolArgumentResolverTest.java`

- [ ] 支持 `name:defaultValue`。
- [ ] 支持 `{windCodes}`、`[{windCodes}]`、`{endDate}` 模板替换。
- [ ] 支持基础函数表达式：`getDate(...)`、`getTradeCode(...)`。
- [ ] 按 `dateMode` 补默认 `startDate/endDate/reportDate`。

### Task 3: HTTP/FundInfraWeb 真实执行

**Files:**

- Create: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/configured/client/FundInfraWebClient.java`
- Create: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/configured/client/ComponentInvokeClient.java`
- Modify: `HttpToolExecutor.java`
- Test: `HttpToolExecutorTest.java`

- [ ] 新增配置：`financial.copilot.tools.fundinfraweb.service-url`、`timeout-ms`、`verify-tls`。
- [ ] 实现 `Invokes` payload。
- [ ] 实现 C# Invoke `/IR/{method}` 客户端。
- [ ] 组件执行失败时保留组件级错误，不让整个 tool 静默成功。

### Task 4: 响应映射与组件数据填充

**Files:**

- Create: `configured/mapper/ComponentResponseMapper.java`
- Create: `configured/mapper/ColumnPathExtractor.java`
- Create: `configured/mapper/ChartOptionBuilder.java`
- Modify: `ComponentDataDistiller.java`
- Test: `ComponentResponseMapperTest.java`

- [ ] 支持 `Column0` 平铺映射。
- [ ] 支持 `Object/list/value` 嵌套数组展开。
- [ ] 支持 table、MergedHeader、line、bar、stackedBar、radar、matrix。
- [ ] 蒸馏器基于真实 `component.data`，不再只对空组件返回“暂无记录”。

### Task 5: MCP 真实执行

**Files:**

- Create: `configured/client/WindMcpClient.java`
- Modify: `McpToolExecutor.java`
- Test: `McpToolExecutorTest.java`

- [ ] 新增 MCP server URL 配置。
- [ ] 实现 `initialize`。
- [ ] 实现 `tools/call`。
- [ ] 支持 SSE 解析。
- [ ] 对 6 个 MCP tool 增加 responseMapping。

### Task 6: DataAgent 真实执行

**Files:**

- Create: `configured/executor/DataAgentToolExecutor.java`
- Create: `configured/client/ExpoDataAgentClient.java`
- Modify: `ToolExecutorRouter` 的 executor 注入顺序测试。
- Test: `DataAgentToolExecutorTest.java`

- [ ] 从 `HttpToolExecutor.supports` 移除 `data_agent`。
- [ ] DataAgent 独立执行 T609。
- [ ] `fund_analysis_review` 返回 markdown 组件和 LLM 摘要。

### Task 7: 补齐 JSON 组件配置

**Files:**

- Create/Modify: `copilot-agent-tools/src/main/resources/config/tools/analysis/*.json`
- Create: `copilot-agent-tools/src/main/resources/config/tools/comparison/compare-scale.json`
- Test: `ToolDefinitionLoaderTest.java`

- [ ] 从参考项目迁移 23 个 `NonMoney*`/ETF 组件。
- [ ] 从参考项目迁移 7 个 REITs 单基金组件。
- [ ] 新增 `compare_scale`，包含 `Scale`、`ScaleTrend`、`ETFScaleStatistics`。
- [ ] 每个新增组件保留 `command/params/columns/metadata`。
- [ ] 为 iframe 类组件保留 `url/linkId`。

### Task 8: Agent/workspace 交付闭环

**Files:**

- Modify: `FundAnalysisToolSet.java`
- Modify: `FundComparisonToolSet.java`
- Modify: `FundAnalyzerAgent.java`
- Modify: `FundComparatorAgent.java`
- 可能新增：`configured/workspace/ConfiguredToolWorkspacePublisher.java`

- [ ] facade 不只返回 `textForLlm`，还要把 `visualComponents` 交给当前 DAG context 或 artifact。
- [ ] Agent evidence 从旧工具名逐步过渡到配置化工具名。
- [ ] 保留旧工具兜底，直到配置化工具验收完成。

## 8. 验收标准

### 配置验收

- [ ] `ToolDefinitionLoaderTest` 断言 24 个现有 tool 仍可加载。
- [ ] 新增组件后断言缺口清单归零或按产品边界明确豁免。
- [ ] 每个 http 组件均具备 `command` 或 `url`。
- [ ] 每个 mcp/data_agent tool 均具备 `targetTool/templateId` 与 `responseMapping`。

### 执行验收

- [ ] `compare_basic_info` 能真实返回基金名称、成立日、类型、规模等 `data`。
- [ ] `fund_analysis_profile` 能真实返回基础信息、投资目标、购买信息。
- [ ] `compare_brinson` 能真实调用 `fund_get_brinson_attribution`。
- [ ] `fund_analysis_review` 能真实调用 T609，并返回 markdown review。
- [ ] 任一组件外部调用失败时，结果为失败或部分成功，不允许假成功。

### 工作台验收

- [ ] `ToolExecuteResult.visualComponents` 不为空且包含真实 `data/chartOption/url`。
- [ ] 前端或 artifact 能消费完整组件。
- [ ] LLM observation 只看到蒸馏文本，不直接吃大表/长时序。

## 9. 推荐实施顺序

1. 先补模型和 loader，不接外部服务。
2. 再补参数解析和 command 渲染。
3. 优先打通一个 http 组件：`compare_basic_info.FundInfoForDefault`。
4. 再打通一个 MCP 组件：`compare_similar`。
5. 再打通 DataAgent：`fund_analysis_review`。
6. 补齐缺失 JSON 组件。
7. 最后接 workspace/artifact，替换 agent evidence。

这个顺序能把风险压小：每一步都有可测产物，避免一次性迁移全部组件后才发现协议或数据结构不对。

## 10. 不在本轮实施范围

- 不重写整个 AgentScope Java runtime。
- 不删除旧 `fund_metrics`、`compare_metrics` 工具。
- 不改参考 Python 项目。
- 不在配置化执行器中伪造数据；没有真实外部响应时必须失败或返回明确的 unavailable。

## 11. 2026-09-15 实施记录

本轮已在 `copilot-agent-tools` 落地可直接配置使用的真实执行骨架：

- 配置模型：`UITreeComponent` 已保留组件级 `command`、`params`、`columnMapping`、`responseMapping`、`process`、`output`、`providerSpec`、`rawMetadata`。
- Loader：`ToolDefinitionLoader` 已读取现有 JSON 中的组件执行字段，并从 `metadata.table.columns` 提取前端表格列定义。
- 参数层：新增 `ConfiguredToolArgumentResolver` 与 `CommandTemplateRenderer`，支持 `name:defaultValue`、`{windCodes}`、`{windcode}`、`{startDate}`、`{endDate}`、`{reportDate}`、`getTradeCode(...)`、`getDate(...)`。
- HTTP/FundInfraWeb：新增 `FundInfraWebClient` 与 `ComponentInvokeClient`，`HttpToolExecutor` 已按组件逐个渲染 command、真实调用客户端、映射响应并汇总部分成功/失败；不再把 `data_agent` 归到 HTTP 执行器。
- MCP：新增 `WindMcpClient`，执行 `initialize -> tools/call`，支持普通 JSON 与 SSE `data:` 响应；`McpToolExecutor` 已使用真实客户端结果填充组件。
- DataAgent：新增 `ExpoDataAgentClient` 与 `DataAgentToolExecutor`，按 T609/templateId 与 Expo DataAgent 配置组装请求，返回 markdown 组件和 LLM 蒸馏文本。
- 响应映射：新增 `ComponentResponseMapper`、`ColumnPathExtractor`、`ChartOptionBuilder`，支持 `Results[0].Value`、`data.rows`、`rows`、`result`、markdown、iframe URL、基础 chartOption。
- 应用配置：`copilot-app/src/main/resources/application.yml` 已新增 `financial.copilot.tools.*` 外部服务配置项。

可用配置项：

```yaml
financial:
  copilot:
    tools:
      http-timeout-ms: 15000
      fundinfraweb-service-url: ${WIND_FUNDINFRAWEB_SERVICE_URL:}
      component-invoke-service-url: ${WIND_FUNDRESEARCH_SERVICE_URL:}
      mcp-base-url: ${WIND_MCP_BASE_URL:}
      data-agent-service-url: ${WIND_EXPO_DATA_AGENT_SERVICE_URL:}
      data-agent-app-class: ${WIND_EXPO_DATA_AGENT_APP_CLASS:1694}
      data-agent-command-id: ${WIND_EXPO_DATA_AGENT_COMMAND_ID:23927}
      data-agent-service-id: ${WIND_EXPO_DATA_AGENT_SERVICE_ID:2036}
      data-agent-refer-type: ${WIND_EXPO_DATA_AGENT_REFER_TYPE:COM.FundInfra.META}
```

验证结果：

- `mvn -pl copilot-agent-tools test` 通过，22 个测试全部通过。
- `mvn -pl copilot-agent-tools -am test` 会被上游 `copilot-data-engine` 的本地 Neo4j/Postgres/Ollama 集成测试阻塞；本轮用 `mvn -N -DskipTests install` 与依赖模块 `-DskipTests install` 后单跑 agent-tools 完成验证。

剩余边界：

- 真实 Wind 内网服务 URL、sessionId、DataAgent 网关需要在部署环境配置后做联调。
- JSON 缺口补齐（非货币、REITs、compare_scale）和 workspace/artifact 投递仍按后续任务继续推进。
