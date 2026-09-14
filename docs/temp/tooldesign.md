基金研究项目 Tool 配置化接入与落地设计

版本：V1.0
适用范围：当前 Java Fund Copilot / Fund Research 项目
目标：将参考项目中的 Tool 定义、参数、Provider、HTTP / Expo / MCP 调用协议迁移为 Java 项目内可配置、可路由、可治理的 Tool 体系。
核心原则：不引入 Python Runtime，不调用 Python Tool 实现，只复用 Tool 设计、Tool 名称、参数语义、Provider 路由以及底层协议。

1. 背景与设计目标

当前项目已经存在一批 Java 原生 Tool，例如：

FundScreeningTool

FundQuantAnalysisTool

FundHoldingsQueryTool

FundReportRetrieverTool

FinancialGraphTool

StockScreeningTool

StockQuantAnalysisTool

这些 Tool 主要访问当前项目自己的 PostgreSQL、PGVector、FundDataPort、StockDataPort、FinancialGraphPort 等数据能力。

参考项目则存在另一套成熟的基金研究 Tool 体系，底层数据来源包含：

Wind 内部 HTTP Invoke 服务；

AI Market；

MCP HTTP JSON-RPC；

Expo DPU；

Expo Data Agent；

Workspace / Component 服务；

前端交互事件。

本设计希望将参考项目 Tool 能力接入当前 Java 项目，但不让 Java 反向调用 Python Tool，而是在 Java 中重新实现一套：

Tool Definition
↓
Tool Registry
↓
Tool Provider / Router
↓
Provider Executor
↓
HTTP / MCP / Expo / Local / Frontend Event

最终实现以下目标：

支持当前项目原生 Tool。

支持参考项目同款外部 Tool。

Tool 参数、Provider、Endpoint、超时等尽可能配置化。

支持请求级切换 Tool 数据源。

避免本地数据库 Tool 与外部 Tool 在同一模式下混用。

Tool 对 AgentScope / ReAct Agent 保持统一的 Tool 接口。

Tool 执行结果可统一映射为文本、结构化数据、Workspace、Component、Progress 等事件。

后续可进一步扩展 Tool 权限、审计、熔断、限流、重试、版本治理和动态注册。

2. Tool 来源模式

当前项目保留两类 Tool 来源：

模式

Tool 来源

数据来源

LOCAL_PROJECT

当前 Java 原生 Tool

PostgreSQL、PGVector、FundDataPort、StockDataPort、FinancialGraphPort

EXTERNAL_CONFIGURED

参考项目同款 Tool 配置

Java 直接调用 HTTP / Expo / MCP HTTP JSON-RPC

Java 枚举：

public enum ToolSourceMode {

    /**
     * 使用当前项目本地 Java Tool。
     */
    LOCAL_PROJECT,

    /**
     * 使用参考项目同款外部 Tool。
     */
    EXTERNAL_CONFIGURED
}

建议请求对象增加：

public class GraphRunRequest {

    private String prompt;

    /**
     * Tool 来源模式。
     * 未指定时使用配置中的默认值。
     */
    private ToolSourceMode toolSourceMode;
}

请求示例：

{
"prompt": "分析 000011.OF 的业绩和持仓",
"toolSourceMode": "EXTERNAL_CONFIGURED"
}

2.1 Tool 注册互斥原则

LOCAL_PROJECT 模式：

Agent
-> screen_funds
-> fund_metrics
-> fund_holdings
-> fund_report
-> financial_graph

EXTERNAL_CONFIGURED 模式：

Agent
-> financial_search
-> component_analysis
-> component_analysis_edit
-> component_comparison_edit
-> component_data_read
-> fund_material_supplement
-> aggregate_search
-> index_query_description
-> thematic_statistics
-> user_fund_pool
-> date_range_parse
-> html_workspace_create
-> interactive_form

重要约束：

EXTERNAL_CONFIGURED 模式下不要继续注册 screen_funds、fund_metrics、fund_holdings、fund_report 等当前项目数据库 Tool。

否则 LLM 在同一个 ReAct Agent 中可能：

一部分数据来自当前 PostgreSQL；

一部分数据来自外部 Wind 服务；

一部分结果来自旧缓存；

最终形成数据口径混用。

因此建议 Tool Source Mode 在一次 Graph Run 中保持固定。

3. 总体架构

推荐架构：

                         ┌────────────────────┐
                         │      Agent         │
                         │ ReAct / AgentScope │
                         └─────────┬──────────┘
                                   │
                                   ▼
                         ┌────────────────────┐
                         │   ToolProvider     │
                         └─────────┬──────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
                    ▼                             ▼
          LOCAL_PROJECT                 EXTERNAL_CONFIGURED
                    │                             │
           Java Native Tools                  ToolRegistry
                                                  │
                                     ┌────────────┼─────────────┐
                                     ▼            ▼             ▼
                                  HTTP         MCP          EXPO
                                     │            │             │
                                     ▼            ▼             ▼
                               WebClient     JSON-RPC      Expo SDK

推荐核心包结构：

com.wind.fund.copilot.tool
├── api
│   ├── ToolDefinition.java
│   ├── ToolParameterDefinition.java
│   ├── ToolExecuteRequest.java
│   ├── ToolExecuteResult.java
│   └── ToolExecutor.java
├── config
│   ├── ToolProperties.java
│   ├── ToolRegistry.java
│   └── ToolDefinitionLoader.java
├── provider
│   ├── ToolProvider.java
│   ├── LocalToolProvider.java
│   └── ExternalConfiguredToolProvider.java
├── executor
│   ├── HttpToolExecutor.java
│   ├── McpToolExecutor.java
│   ├── ExpoDpuToolExecutor.java
│   ├── ExpoDataAgentToolExecutor.java
│   ├── CompositeToolExecutor.java
│   └── FrontendEventToolExecutor.java
├── routing
│   ├── ToolExecutorRouter.java
│   └── ToolRouteResolver.java
├── mapping
│   ├── ToolRequestMapper.java
│   ├── ToolResponseMapper.java
│   └── WorkspaceMappingService.java
├── external
│   └── ExternalConfiguredFundToolSet.java
└── exception
├── ToolExecutionException.java
├── ToolValidationException.java
└── ToolProviderException.java

4. Tool 配置模型

4.1 ToolDefinition

建议统一定义：

@Data
public class ToolDefinition {

    /**
     * 暴露给 LLM 的 Tool 名称。
     */
    private String name;

    /**
     * Tool 描述。
     */
    private String description;

    /**
     * Provider 类型。
     */
    private ToolProviderType provider;

    /**
     * 是否只读。
     */
    private boolean readOnly = true;

    /**
     * 是否启用。
     */
    private boolean enabled = true;

    /**
     * 参数 Schema。
     */
    private Map<String, ToolParameterDefinition> parameters;

    /**
     * HTTP / MCP / Expo 等 Provider 配置。
     */
    private Map<String, Object> providerConfig;

    /**
     * 请求模板。
     */
    private Map<String, Object> requestTemplate;

    /**
     * 响应映射。
     */
    private Map<String, Object> responseMapping;

    /**
     * Workspace / Component 映射。
     */
    private Map<String, Object> workspaceMapping;

    /**
     * 超时。
     */
    private Duration timeout;

    /**
     * 重试次数。
     */
    private int maxRetries;

    /**
     * Tool 版本。
     */
    private String version;
}

4.2 ToolParameterDefinition

@Data
public class ToolParameterDefinition {

    private String type;

    private boolean required;

    private Object defaultValue;

    private String description;

    /**
     * enum 可选值。
     */
    private List<String> enumValues;

    /**
     * Array item type。
     */
    private String itemType;
}

4.3 ToolExecuteRequest

@Data
@Builder
public class ToolExecuteRequest {

    private String toolName;

    private Map<String, Object> arguments;

    private String sessionId;

    private String conversationId;

    private String runId;

    private String stepId;

    private String userId;

    private Map<String, Object> context;
}

4.4 ToolExecuteResult

@Data
@Builder
public class ToolExecuteResult {

    /**
     * Tool 是否成功。
     */
    private boolean success;

    /**
     * 给模型阅读的文本结果。
     */
    private String content;

    /**
     * 原始结构化返回。
     */
    private Object data;

    /**
     * 引用、组件、资料等。
     */
    private List<ToolReference> references;

    /**
     * Workspace 变化。
     */
    private List<WorkspaceEvent> workspaceEvents;

    /**
     * 调试信息。
     */
    private ToolExecutionMetadata metadata;

    /**
     * 失败信息。
     */
    private ToolError error;
}

5. Provider 分类与执行器

Provider

主要用途

Java 实现

LOCAL

当前项目数据库 / Port Tool

现有 Java Tool 适配

HTTP_INVOKE

FundResearch Invoke 服务

WebClient Form POST

HTTP_AIMARKET

新闻、研报、公告、指数说明检索

WebClient JSON POST

HTTP_MCP

Wind MCP Server

HTTP JSON-RPC

EXPO_DPU

DPU 自然语言查询

Java Expo SDK / 内部消息总线

EXPO_DATA_AGENT

DataAgent 模板任务

Java Expo SDK / 内部消息总线

COMPOSITE

一个 Tool 内部按 materialType 再路由

CompositeToolExecutor

LOCAL_WORKSPACE

本地 Workspace 操作

Java 本地服务

FRONTEND_EVENT

需要用户补充信息

SSE / WebSocket / Graph Interrupt

枚举：

public enum ToolProviderType {
LOCAL,
HTTP_INVOKE,
HTTP_AIMARKET,
HTTP_MCP,
EXPO_DPU,
EXPO_DATA_AGENT,
COMPOSITE,
LOCAL_WORKSPACE,
FRONTEND_EVENT
}

统一执行器接口：

public interface ToolExecutor {

    boolean supports(ToolDefinition definition);

    ToolExecuteResult execute(
        ToolDefinition definition,
        ToolExecuteRequest request
    );
}

路由器：

@Component
@RequiredArgsConstructor
public class ToolExecutorRouter {

    private final List<ToolExecutor> executors;

    public ToolExecuteResult execute(
            ToolDefinition definition,
            ToolExecuteRequest request) {

        ToolExecutor executor = executors.stream()
            .filter(it -> it.supports(definition))
            .findFirst()
            .orElseThrow(() ->
                new ToolProviderException(
                    "No executor for provider: " + definition.getProvider()
                )
            );

        return executor.execute(definition, request);
    }
}

6. Tool 总览

Tool

Provider

主要职责

必须接入

financial_search

EXPO_DPU

自然语言基金筛选

是

component_analysis

LOCAL_WORKSPACE + HTTP_MCP/外部组件能力

创建基金分析/比较组件

是

component_analysis_edit

LOCAL_WORKSPACE

编辑单基金工作台

是

component_comparison_edit

LOCAL_WORKSPACE

编辑多基金比较工作台

是

component_data_read

HTTP_INVOKE

回读组件真实数据

是

fund_material_supplement

COMPOSITE

点评、相似基金、Brinson 等资料补充

是

aggregate_search

HTTP_AIMARKET

新闻、研报、公告、政策检索

是

index_query_description

HTTP_AIMARKET

指数定义、编制规则查询

是

thematic_statistics

LOCAL_WORKSPACE / 可选 LLM

专题统计入口推荐

是

user_fund_pool

LOCAL / HTTP

用户自选基金池

建议

date_range_parse

LOCAL

自然语言日期区间解析

是

html_workspace_create

LOCAL_WORKSPACE

创建 HTML Artifact

可选

interactive_form

FRONTEND_EVENT

请求用户补充条件

可选

7. Tool 详细设计

7.1 financial_search

7.1.1 Tool 定位

financial_search 是外部模式下最核心的基金筛选 Tool。

它负责把用户自然语言筛选需求发送给 DPU，由 DPU 完成：

基金条件理解；

指标映射；

筛选逻辑执行；

排序；

TopN；

指定展示指标返回。

典型问题：

筛选近三年最大回撤小于 20%，夏普比率前 10 的医药主题基金。

7.1.2 适用场景

适合：

基金筛选；

自然语言指标过滤；

多条件组合；

TopN；

指定行业 / 主题 / 基金类型；

指定返回字段。

不建议用于：

单基金深度研究；

新闻检索；

基金经理点评；

组件数据回读。

7.1.3 参数

参数

类型

必填

说明

query

string

是

完整自然语言筛选问题

indicators

array[string]

否

期望返回的指标字段

resultType

string

否

默认“基金”

mode

string

否

特殊模式，默认空

推荐 Schema：

parameters:
query:
type: string
required: true
description: 完整自然语言基金筛选问题
indicators:
type: array
itemType: string
required: false
description: 需要返回或展示的基金指标
resultType:
type: string
required: false
default: 基金
mode:
type: string
required: false
default: ""

7.1.4 Provider

EXPO_DPU

参考调用参数：

{
"appClass": 2096,
"commandId": 28904,
"args": [
{
"question": "筛选近三年回撤低、夏普高的医药基金",
"requestApp": "2462",
"version": "2.0"
}
],
"srcUserId": 8746,
"destUserId": 8746,
"sourceUserId": 8746
}

配置建议：

- name: financial_search
  description: 根据自然语言条件进行基金筛选、排序和 TopN 查询
  provider: EXPO_DPU
  readOnly: true
  timeout: 15s
  maxRetries: 1
  expo:
  appClass: 2096
  commandId: 28904
  requestApp: "2462"
  version: "2.0"

7.1.5 Java 执行逻辑

LLM Tool Call
↓
参数校验
↓
query -> question
↓
组装 ExpoDpuRequest
↓
ExpoDpuClient.execute()
↓
解析 DPU 返回
↓
标准化为 FundScreenResult
↓
生成 ToolExecuteResult

建议标准化对象：

@Data
public class FundScreenResult {
private List<String> columns;
private List<Map<String, Object>> rows;
private Integer total;
private String queryExplanation;
}

7.1.6 失败处理

需处理：

DPU 超时；

空结果；

DPU 返回错误码；

指标不存在；

条件无法理解；

返回字段不完整。

建议失败结果不要直接抛给 LLM 原始异常：

{
"success": false,
"error": {
"code": "FINANCIAL_SEARCH_TIMEOUT",
"message": "基金筛选服务暂时未返回结果"
}
}

7.2 component_analysis

7.2.1 Tool 定位

用于创建：

单基金分析组件；

多基金比较组件；

基金工作台初始布局。

该 Tool 的重点不是直接返回所有分析数据，而是生成 Workspace / Component Artifact，并返回后续可读取的 referenceId。

7.2.2 参数

参数

类型

必填

说明

fundCodes

array[string]

是

基金代码列表

dimensions

array[string]

否

分析维度

endDate

string

否

分析截止日期

fundType

string

否

基金类型

benchmarkCode

string/array

否

基准

startDate

string

否

区间开始

示例：

{
"fundCodes": ["000011.OF"],
"dimensions": ["基础", "业绩", "风险", "持仓", "经理"],
"endDate": "2026-09-14",
"fundType": "普通型"
}

7.2.3 执行结果

推荐：

{
"workspaceId": "ws_xxx",
"components": [
{
"referenceId": 1,
"componentType": "FUND_ANALYSIS",
"title": "000011.OF 基金分析"
}
]
}

同时通过 SSE 输出：

{
"type": "workspace",
"action": "create",
"workspaceId": "ws_xxx"
}

{
"type": "component",
"action": "create",
"referenceId": 1
}

7.2.4 与 component_data_read 的关系

推荐固定工作流：

component_analysis
↓
创建 Component
↓
获得 referenceId
↓
component_data_read
↓
读取真实组件数据
↓
主 LLM 总结

这样避免 Tool 只创建组件但模型不知道组件里实际有什么数据。

7.3 component_analysis_edit

7.3.1 Tool 定位

用于编辑单基金分析 Workspace 中已经存在的组件。

典型场景：

“把业绩模块改成近三年”
“增加基金经理分析”
“去掉持仓行业分布”

7.3.2 推荐参数

parameters:
referenceId:
type: integer
required: true
action:
type: string
required: true
enumValues:
- ADD_DIMENSION
- REMOVE_DIMENSION
- UPDATE_DATE_RANGE
- UPDATE_BENCHMARK
- UPDATE_CONFIG
dimensions:
type: array
required: false
startDate:
type: string
required: false
endDate:
type: string
required: false
benchmarkCode:
type: string
required: false
config:
type: object
required: false

7.3.3 Provider

LOCAL_WORKSPACE

原则上无需让 LLM 自己拼前端 JSON。

应该由 Java Workspace Service 根据稳定的 Domain Model 修改组件配置。

7.4 component_comparison_edit

7.4.1 Tool 定位

用于多基金比较组件编辑。

典型场景：

增加比较基金；

删除基金；

增加比较维度；

修改排序指标；

切换时间区间；

修改业绩基准。

7.4.2 推荐参数

parameters:
referenceId:
type: integer
required: true
action:
type: string
required: true
fundCodes:
type: array
required: false
dimensions:
type: array
required: false
sortBy:
type: string
required: false
sortDirection:
type: string
required: false
startDate:
type: string
required: false
endDate:
type: string
required: false

7.4.3 输出

输出应同时包含：

Tool 文本结果；

最新组件结构；

Workspace / Component SSE Event。

7.5 component_data_read

7.5.1 Tool 定位

读取已经创建的 Component 中的真实数据。

这是组件体系最重要的“数据回读 Tool”。

如果没有它：

LLM -> 创建组件 -> 用户能看到图表

但是主 LLM 本身并不知道图表里面的真实值。

加入它后：

LLM -> 创建组件
-> component_data_read
-> 获得真实组件数据
-> LLM 基于真实数据总结

7.5.2 Provider

HTTP_INVOKE

调用：

POST {WIND_FUNDRESEARCH_SERVICE_URL}/IR/ComponentReader.GetContent
Content-Type: application/x-www-form-urlencoded
Header: wind.sessionid

Body：

contextId=...
refId=...

7.5.3 Tool 参数

参数

类型

必填

说明

referenceId

integer

否

指定组件

indexes

array[integer]

否

指定读取子模块

maxCharacters

integer

否

防止组件内容过大

7.5.4 Session 注入

wind.sessionid 不应该由 LLM 传入。

由：

Request Context
↓
ToolExecuteRequest.sessionId
↓
HttpToolExecutor
↓
Header wind.sessionid

7.5.5 返回裁剪

推荐默认：

maxCharacters = 5000

优先：

focused component；

用户刚创建/编辑的 component；

indexes 指定区域；

截断低优先级内容。

7.6 fund_material_supplement

7.6.1 Tool 定位

用于补充单基金或多基金研究材料。

它不是单一 Provider Tool，而是一个 Composite Tool。

典型材料：

基金点评；

相似基金；

Brinson 归因；

业绩归因；

风格分析；

基金经理材料；

基准信息。

7.6.2 参数

parameters:
fundCode:
type: array|string
required: false
materialTypes:
type: array
required: false
fundType:
type: string
required: false
benchmarkCode:
type: array
required: false
startDate:
type: string
required: false
endDate:
type: string
required: false

7.6.3 Composite 路由

routes:
review:
provider: EXPO_DATA_AGENT
templateId: T609
serviceId: "2036"
referType: COM.FundInfra.META

similar:
provider: HTTP_MCP
server: wind-fund-analysis
tool: fund_get_similar

brinson:
provider: HTTP_MCP
server: wind-fund-analysis
tool: fund_get_brinson_attribution

执行流程：

materialTypes
↓
for each type
↓
ToolRouteResolver
↓
review  -> ExpoDataAgent
similar -> MCP
brinson -> MCP
↓
并行执行
↓
合并 ToolReference
↓
返回给 LLM

7.6.4 推荐并行

多个 materialTypes 之间通常不存在依赖，应使用：

CompletableFuture

或 Java Virtual Thread：

Executors.newVirtualThreadPerTaskExecutor()

并行执行。

7.6.5 部分成功

Composite Tool 不应因为一个子材料失败而全部失败。

推荐：

{
"success": true,
"data": {
"review": {...},
"similar": {...},
"brinson": null
},
"warnings": [
"brinson attribution request timeout"
]
}

7.7 aggregate_search

7.7.1 Tool 定位

统一检索外部文本类金融资料：

新闻；

公告；

研报；

政策；

市场资讯；

基金相关材料。

7.7.2 Provider

HTTP_AIMARKET

7.7.3 参数

参数

类型

必填

说明

query

string / array[string]

是

查询问题

knowledgeGroups

array

否

知识库范围

startDate

string

否

开始日期

endDate

string

否

结束日期

topK

integer

否

最大返回数量

7.7.4 推荐返回结构

@Data
public class SearchDocument {
private String title;
private String source;
private String publishDate;
private String snippet;
private String url;
private Double score;
}

Tool 返回不建议直接把整篇文档塞给模型。

推荐：

TopK
+ title
+ source
+ date
+ snippet
+ reference

必要时由下一层 document Tool 再获取全文。

7.8 index_query_description

7.8.1 Tool 定位

用于查询指数：

指数定义；

编制方案；

样本空间；

调样规则；

权重规则；

指数用途。

例如：

沪深300的编制规则是什么？
399959.SZ 是什么指数？

7.8.2 Provider

HTTP_AIMARKET

7.8.3 参数

parameters:
indexName:
type: string
required: true

后续建议兼容：

indexCode:
type: string
required: false

因为用户通常既可能输入：

沪深300

也可能输入：

000300.SH

7.9 thematic_statistics

7.9.1 Tool 定位

用于把用户的“专题统计”需求映射到当前支持的专题入口。

例如：

查看 REITs 资产明细
查看 ETF 资金流专题
查看基金经理变更专题

它更像：

Topic Router + Workspace Builder

而不是单纯数据查询。

7.9.2 推荐参数

parameters:
query:
type: string
required: true
reportId:
type: string
required: false
startDate:
type: string
required: false
endDate:
type: string
required: false
sort:
type: string
required: false
limit:
type: integer
required: false

7.9.3 实现建议

本地维护：

thematic_catalog.yaml

例如：

- reportId: fund_reits_asset_detail
  name: REITs 资产明细
  aliases:
    - REITs资产
    - REITs底层资产
      description: 查询公募REITs底层资产经营与估值明细

执行：

query
↓
规则 / Embedding / LLM rerank
↓
候选专题
↓
生成 ThematicQueryPlan
↓
执行专题数据能力

7.10 user_fund_pool

7.10.1 Tool 定位

读取用户基金池 / 自选基金。

典型问题：

分析一下我基金池里的基金
从我的自选里筛选今年收益最高的5只

7.10.2 参数

parameters:
poolId:
type: string
required: false
keyword:
type: string
required: false

7.10.3 用户身份

用户身份不能由 LLM 输入。

必须：

SecurityContext
↓
accountId
↓
ToolExecuteRequest.context
↓
user_fund_pool

避免越权查询其他用户基金池。

7.10.4 Provider

可支持：

LOCAL

或：

HTTP_INVOKE

由部署环境决定。

7.11 date_range_parse

7.11.1 Tool 定位

统一自然语言时间解析。

例如：

近三年
今年以来
2024年以来
最近六个月
成立以来
任职以来
上半年
二季度

7.11.2 为什么单独做 Tool

时间解析如果散落在每个业务 Tool 内，会出现：

近一年口径不一致；

交易日 / 自然日不一致；

endDate 默认值不一致；

基金成立日前处理不一致。

因此建议集中处理。

7.11.3 Provider

LOCAL

纯 Java 即可。

7.11.4 返回

{
"type": "RELATIVE",
"startDate": "2023-09-14",
"endDate": "2026-09-14",
"originalExpression": "近三年"
}

若是：

成立以来

则返回：

{
"type": "SINCE_INCEPTION",
"requiresFundContext": true
}

再由业务 Tool 根据基金成立日补齐。

7.12 html_workspace_create

7.12.1 Tool 定位

用于生成自定义 HTML / Artifact 工作台。

适合：

非标准报告；

临时组合图表；

复杂展示；

当前 Component Schema 无法覆盖的场景。

7.12.2 安全要求

不建议允许 LLM 直接生成任意可执行 JS。

建议：

LLM
↓
Artifact DSL / Safe HTML Schema
↓
Server-side sanitizer
↓
HTML Render Service

避免 XSS。

7.12.3 参数

parameters:
title:
type: string
required: true
content:
type: object
required: true
layout:
type: string
required: false

7.13 interactive_form

7.13.1 Tool 定位

当 Agent 缺少关键参数，且无法安全推断时，生成前端交互表单。

例如用户只说：

帮我比较基金

但没有基金代码。

可以输出：

{
"type": "interactive_form",
"fields": [
{
"name": "fundCodes",
"label": "请选择基金",
"type": "fund_selector",
"required": true
}
]
}

7.13.2 Provider

FRONTEND_EVENT

7.13.3 Graph 行为

执行到该 Tool 时：

RUNNING
↓
WAITING_USER_INPUT

用户提交后：

WAITING_USER_INPUT
↓
RESUME
↓
继续原 Graph

因此它本质上是：

Human-in-the-loop Interrupt

而不是普通查询 Tool。

8. MCP 接入设计

8.1 配置

wind:
mcp:
base-url: https://114.80.154.45/Wind.MCP.Server/vserver
servers:
wind-fund-data: ${wind.mcp.base-url}/vserver_fund_datatest/mcp
wind-fund-holdings: ${wind.mcp.base-url}/vserver_fund_holdtest/mcp
wind-fund-analysis: ${wind.mcp.base-url}/vserver_fund_analysistest/mcp

8.2 MCP 初始化

建议 McpClientManager 按 Server 维护 Session。

调用：

{
"jsonrpc": "2.0",
"id": "init-1",
"method": "initialize",
"params": {}
}

之后调用 Tool：

{
"jsonrpc": "2.0",
"id": "fund-tool-call",
"method": "tools/call",
"params": {
"name": "fund_get_brinson_attribution",
"arguments": {
"fundCode": "000011.OF",
"benchCode": "000300.SH",
"startDate": "2024-01-01",
"endDate": "2026-09-14"
}
}
}

8.3 Java Client

public interface McpClient {

    McpInitializeResult initialize(String serverName);

    McpToolResult callTool(
        String serverName,
        String toolName,
        Map<String, Object> arguments
    );
}

需要统一处理：

MCP session；

initialize；

JSON-RPC id；

HTTP timeout；

MCP error；

result content；

session 失效重连。

9. HTTP Provider 设计

9.1 HttpToolExecutor

@Component
@RequiredArgsConstructor
public class HttpToolExecutor implements ToolExecutor {

    private final WebClient webClient;
    private final ToolRequestMapper requestMapper;
    private final ToolResponseMapper responseMapper;

    @Override
    public boolean supports(ToolDefinition definition) {
        return definition.getProvider() == ToolProviderType.HTTP_INVOKE
            || definition.getProvider() == ToolProviderType.HTTP_AIMARKET;
    }

    @Override
    public ToolExecuteResult execute(
            ToolDefinition definition,
            ToolExecuteRequest request) {

        // 1. 参数校验
        // 2. endpoint 渲染
        // 3. header 注入
        // 4. body 映射
        // 5. WebClient 调用
        // 6. responseMapping
        // 7. ToolExecuteResult
        return null;
    }
}

9.2 Header 安全

配置中：

headers:
wind.sessionid: "${sessionId}"

${sessionId} 是运行期变量，不允许 LLM 修改。

同理：

access token；

userId；

accountId；

service credential；

均必须来自服务器 Context。

10. Expo Provider 设计

10.1 ExpoDpuToolExecutor

职责：

Tool arguments
↓
Expo request mapping
↓
appClass / commandId
↓
Expo SDK
↓
response decode

建议接口：

public interface ExpoClient {

    ExpoResult invoke(
        int appClass,
        int commandId,
        Object request
    );
}

10.2 ExpoDataAgentToolExecutor

适用于：

templateId
serviceId
referType

驱动的 Data Agent。

统一请求：

@Data
@Builder
public class DataAgentRequest {

    private String templateId;

    private String serviceId;

    private String referType;

    private Map<String, Object> variables;
}

11. Tool Registry

11.1 作用

ToolRegistry 负责：

加载配置；

校验 ToolDefinition；

根据 name 查找 Tool；

根据 mode 筛选 Tool；

控制启用 / 禁用；

版本治理。

public interface ToolRegistry {

    Optional<ToolDefinition> get(String name);

    List<ToolDefinition> listEnabled();

    void reload();
}

11.2 启动校验

启动时建议检查：

Tool name 是否重复；

Provider 是否存在 Executor；

required 参数是否合法；

MCP server 是否配置；

endpoint 是否为空；

Composite route 是否完整；

requestTemplate 是否引用不存在的参数。

有问题时直接 Fail Fast。

12. Agent 暴露方式

由于 AgentScope 通常希望 Java Method 形式的 Tool，推荐新增：

@Component
@RequiredArgsConstructor
public class ExternalConfiguredFundToolSet {

    private final ConfiguredToolInvoker invoker;

    @Tool(description = "根据自然语言条件筛选基金")
    public ToolResponse financial_search(
            String query,
            List<String> indicators,
            String resultType,
            String mode) {
        return invoker.invoke(
            "financial_search",
            Map.of(
                "query", query,
                "indicators", indicators,
                "resultType", resultType,
                "mode", mode
            )
        );
    }

    @Tool(description = "读取基金分析组件真实数据")
    public ToolResponse component_data_read(
            Integer referenceId,
            List<Integer> indexes) {
        return invoker.invoke(
            "component_data_read",
            Map.of(
                "referenceId", referenceId,
                "indexes", indexes
            )
        );
    }
}

这样做的好处：

LLM 看到的是稳定 Tool Signature；

Provider 仍然可以配置化；

Java 类型检查更可靠；

AgentScope 接入简单；

Tool Definition 与执行层解耦。

不建议第一版直接做完全动态 JSON Schema Tool 注册，复杂度较高。

13. ToolProvider

public interface ToolProvider {

    boolean supports(ToolSourceMode mode);

    List<Object> provideTools(AgentContext context);
}

本地：

@Component
public class LocalToolProvider implements ToolProvider {

    @Override
    public boolean supports(ToolSourceMode mode) {
        return mode == ToolSourceMode.LOCAL_PROJECT;
    }
}

外部：

@Component
public class ExternalConfiguredToolProvider implements ToolProvider {

    @Override
    public boolean supports(ToolSourceMode mode) {
        return mode == ToolSourceMode.EXTERNAL_CONFIGURED;
    }
}

14. Tool 与 Workspace / SSE 的统一事件

建议 Tool 本身不要直接写 SSE。

Tool 返回：

ToolExecuteResult

由 Workflow / Agent Runtime 转换成事件。

统一事件：

progress
workspace
component
tool_start
tool_end
tool_error
final

示例：

{
"type": "tool_start",
"toolName": "financial_search",
"stepId": "step_2"
}

{
"type": "tool_end",
"toolName": "financial_search",
"stepId": "step_2",
"success": true
}

组件：

{
"type": "component",
"action": "create",
"referenceId": 13,
"componentType": "FUND_ANALYSIS"
}

15. Tool 执行上下文

建议不要只有：

arguments

还要有统一 Runtime Context：

@Data
@Builder
public class ToolExecutionContext {

    private String runId;

    private String stepId;

    private String conversationId;

    private String sessionId;

    private String userId;

    private String accountId;

    private Locale locale;

    private LocalDate currentDate;

    private ToolSourceMode toolSourceMode;
}

这样：

Session Header；

用户基金池；

当前日期；

多语言；

Trace；

审计；

都不需要 LLM 显式传参。

16. 参数校验

所有 Tool 调用进入 Executor 前统一校验：

required
type
enum
array item
date format
fund code format
max array size
max string length

建议：

ToolArgumentValidator

例如基金代码：

000011.OF
000300.SH
159915.SZ

可做基础格式校验，但不要过度硬编码所有证券类型。

17. 错误模型

统一错误：

@Data
@Builder
public class ToolError {

    private String code;

    private String message;

    private boolean retryable;

    private String provider;

    private Object details;
}

错误编码建议：

TOOL_NOT_FOUND
TOOL_DISABLED
TOOL_ARGUMENT_INVALID
TOOL_PROVIDER_NOT_FOUND
TOOL_TIMEOUT
TOOL_REMOTE_ERROR
TOOL_EMPTY_RESULT
TOOL_PERMISSION_DENIED
MCP_INITIALIZE_FAILED
MCP_TOOL_CALL_FAILED
EXPO_CALL_FAILED
HTTP_CALL_FAILED
WORKSPACE_REFERENCE_NOT_FOUND

18. Timeout / Retry / Circuit Breaker

不同 Tool 使用不同 SLA。

推荐初始配置：

Tool

Timeout

Retry

date_range_parse

100ms

0

user_fund_pool

2s

1

component_data_read

5s

1

aggregate_search

10s

1

index_query_description

10s

1

financial_search

15s

1

fund_material_supplement

20s

子路由独立

建议结合：

Resilience4j

实现：

Timeout；

Retry；

CircuitBreaker；

Bulkhead。

注意：

LLM Tool 层不要无限重试。

建议最大：

Executor Retry = 1
Agent ReAct Retry = 1

避免：

HTTP retry × Agent retry × Graph retry

形成指数级重复请求。

19. 可观测性

每次 Tool 调用记录：

runId
stepId
agentName
toolName
provider
startTime
duration
success
errorCode
retryCount
requestSize
responseSize

Micrometer：

copilot.tool.calls
copilot.tool.duration
copilot.tool.errors
copilot.tool.timeout

Tags：

tool
provider
success

不要把基金筛选 query 全量打进指标 Tag，避免高基数。

20. Tool 审计与敏感信息

日志中禁止直接输出：

wind.sessionid

access token

cookie

service credential

用户敏感账户信息

建议：

ToolAuditLog

保存：

Tool 名
Provider
参数摘要
耗时
结果摘要
Trace ID

而不是保存完整 Header。

21. 配置示例

copilot:
tools:

    source-mode-default: LOCAL_PROJECT

    external:
      enabled: true

      tools:

        - name: financial_search
          description: 根据自然语言筛选基金
          provider: EXPO_DPU
          readOnly: true
          timeout: 15s
          maxRetries: 1
          expo:
            appClass: 2096
            commandId: 28904
            requestApp: "2462"
            version: "2.0"
          parameters:
            query:
              type: string
              required: true
            indicators:
              type: array
              required: false
            resultType:
              type: string
              required: false
              default: 基金
            mode:
              type: string
              required: false
              default: ""

        - name: component_data_read
          description: 读取组件真实数据
          provider: HTTP_INVOKE
          readOnly: true
          timeout: 5s
          endpoint: "${wind.fundresearch.service-url}/IR/ComponentReader.GetContent"
          method: POST_FORM
          headers:
            wind.sessionid: "${sessionId}"
          parameters:
            referenceId:
              type: integer
              required: false
            indexes:
              type: array
              required: false

        - name: aggregate_search
          description: 查询新闻、公告、研报、政策等金融资料
          provider: HTTP_AIMARKET
          readOnly: true
          timeout: 10s
          endpoint: "${wind.aimarket.service-url}"
          method: POST_JSON
          parameters:
            query:
              type: array|string
              required: true
            knowledgeGroups:
              type: array
              required: false
            startDate:
              type: string
              required: false
            endDate:
              type: string
              required: false

        - name: index_query_description
          description: 查询指数定义、编制方案和规则
          provider: HTTP_AIMARKET
          readOnly: true
          timeout: 10s
          endpoint: "${wind.aimarket.service-url}"
          method: POST_JSON
          parameters:
            indexName:
              type: string
              required: true

        - name: fund_material_supplement
          description: 补充基金点评、相似基金、Brinson 等研究材料
          provider: COMPOSITE
          timeout: 20s
          parameters:
            fundCode:
              type: array|string
              required: false
            materialTypes:
              type: array
              required: false
            fundType:
              type: string
              required: false
            benchmarkCode:
              type: array
              required: false
            startDate:
              type: string
              required: false
            endDate:
              type: string
              required: false
          routes:
            review:
              provider: EXPO_DATA_AGENT
              templateId: T609
              serviceId: "2036"
              referType: COM.FundInfra.META
            similar:
              provider: HTTP_MCP
              server: wind-fund-analysis
              tool: fund_get_similar
            brinson:
              provider: HTTP_MCP
              server: wind-fund-analysis
              tool: fund_get_brinson_attribution

22. Demo

22.1 基金筛选

用户：

筛选近三年回撤低、夏普高的医药基金，返回前5只。

Agent Tool Call：

{
"tool": "financial_search",
"arguments": {
"query": "筛选近三年回撤低、夏普高的医药基金，返回前5只",
"indicators": [
"基金代码",
"基金名称",
"基金经理",
"近三年收益",
"最大回撤",
"夏普比率"
],
"resultType": "基金"
}
}

底层：

EXPO_DPU
-> appClass = 2096
-> commandId = 28904

22.2 单基金分析

用户：

分析 000011.OF 的业绩、风险、持仓和基金经理。

第一步：

{
"tool": "component_analysis",
"arguments": {
"fundCodes": ["000011.OF"],
"dimensions": ["基础", "业绩", "风险", "持仓", "经理"],
"endDate": "2026-09-14",
"fundType": "普通型"
}
}

返回：

{
"referenceId": 1
}

第二步：

{
"tool": "component_data_read",
"arguments": {
"referenceId": 1,
"indexes": [1, 2, 3]
}
}

最后：

真实组件数据
↓
主 LLM
↓
研究总结

22.3 基金材料补充

{
"tool": "fund_material_supplement",
"arguments": {
"fundCode": "000011.OF",
"materialTypes": [
"review",
"similar",
"brinson"
],
"fundType": "偏股型",
"benchmarkCode": ["000300.SH"],
"startDate": "2024-01-01",
"endDate": "2026-09-14"
}
}

路由：

review
-> EXPO_DATA_AGENT
-> T609

similar
-> HTTP_MCP
-> fund_get_similar

brinson
-> HTTP_MCP
-> fund_get_brinson_attribution

23. 推荐实施阶段

Phase 1：Tool 配置底座

实现：

ToolSourceMode
ToolDefinition
ToolParameterDefinition
ToolRegistry
ToolExecutor
ToolExecutorRouter
ToolArgumentValidator

这一阶段先不迁具体业务。

Phase 2：HTTP / MCP 通道

实现：

HttpToolExecutor
McpToolExecutor
McpClientManager

优先接：

component_data_read
aggregate_search
index_query_description

原因：

协议清楚；

调试简单；

不依赖 Expo Java SDK。

Phase 3：Expo

实现：

ExpoClient
ExpoDpuToolExecutor
ExpoDataAgentToolExecutor

接入：

financial_search
review

Phase 4：Workspace

实现：

component_analysis
component_analysis_edit
component_comparison_edit
thematic_statistics
html_workspace_create

同时建立：

WorkspaceEvent
ComponentEvent
ToolReference

Phase 5：Composite Tool

接入：

fund_material_supplement

增加：

ToolRouteResolver
parallel execution
partial-success

Phase 6：Human-in-the-loop

实现：

interactive_form
WAITING_USER_INPUT
Graph Resume

24. 推荐第一版类清单

第一版至少增加：

ToolSourceMode
ToolProviderType

ToolDefinition
ToolParameterDefinition
ToolExecuteRequest
ToolExecuteResult
ToolError
ToolReference

ToolRegistry
YamlToolDefinitionLoader

ToolExecutor
ToolExecutorRouter
HttpToolExecutor
McpToolExecutor
ExpoDpuToolExecutor
ExpoDataAgentToolExecutor
CompositeToolExecutor

ToolArgumentValidator
ToolRequestMapper
ToolResponseMapper

ToolProvider
LocalToolProvider
ExternalConfiguredToolProvider

ConfiguredToolInvoker
ExternalConfiguredFundToolSet

McpClient
McpClientManager
ExpoClient

WorkspaceEvent
ComponentEvent
WorkspaceMappingService

25. 不推荐的实现

25.1 Java 调 Python Tool

不建议：

Java
-> Python HTTP Service
-> Python Tool
-> Wind HTTP / Expo / MCP

问题：

多一跳；

多一个 Runtime；

故障链更长；

Java 无法直接治理 Tool；

参数 Schema 分散；

部署复杂。

25.2 每个 Tool 手写一个完整 HTTP Client

不建议：

FinancialSearchClient
ComponentDataClient
AggregateSearchClient
IndexDescriptionClient
SimilarFundClient
BrinsonClient
...

如果每个 Tool 都重复：

WebClient；

timeout；

retry；

header；

JSON；

error mapping；

后续会非常难维护。

应该抽象成：

ToolDefinition
+
Provider Executor

25.3 完全动态 Tool

第一版不建议直接根据 YAML 动态生成所有 AgentScope Tool。

推荐：

Agent 暴露接口：固定 Java @Tool
底层执行：配置化

这是当前阶段复杂度与灵活性的最佳平衡。

26. 最终推荐结构

完整调用链：

GraphRunRequest
│
│ toolSourceMode
▼
ToolProvider
│
├── LOCAL_PROJECT
│      └── 当前 Java Tool
│
└── EXTERNAL_CONFIGURED
│
▼
ExternalConfiguredFundToolSet
│
▼
ConfiguredToolInvoker
│
▼
ToolRegistry
│
▼
ToolExecutorRouter
│
┌─────┼─────────┬──────────┐
▼     ▼         ▼          ▼
HTTP   MCP       EXPO     COMPOSITE
│     │         │          │
└─────┴─────────┴──────────┘
│
▼
ToolExecuteResult
│
┌────────┼──────────┐
▼        ▼          ▼
LLM     Workspace    SSE

27. 核心结论

当前项目真正需要迁移的不是 Python Tool Runtime，而是参考项目已经沉淀好的：

Tool 名称
Tool 语义
Tool 参数
Provider
路由规则
HTTP 协议
MCP 协议
Expo 协议
Workspace / Component 协议
Tool Result 到 Agent 的返回语义

当前 Java 项目应围绕：

ToolDefinition
+ ToolRegistry
+ Provider Executor
+ ToolSourceMode
+ Workspace Event

构建自己的 Tool Runtime。

第一版建议采用：

固定 Java @Tool 接口
+
配置化 Provider 执行

而不是直接追求完全动态 Tool。

这样既能保持 AgentScope Tool 接口稳定，也能逐步将参考项目的 Tool 体系迁移进来，并为后续动态 Tool、权限治理、版本治理、可观测性和 Graph Planner 动态编排保留扩展空间。