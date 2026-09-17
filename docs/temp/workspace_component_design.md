# 基金研究配置化 Tool 工作台组件设计

日期：2026-09-16  
范围：当前 `FinancialCopilot-main` 项目参考 `D:\jlwang.zain\GIT\Wind.Fund.ScreeningAgent\src\fund_research` 的基金研究工作台组件机制进行设计分析。  
约束：本文件只做设计沉淀，不修改代码，不替换现有实现。

## 1. 结论摘要

参考项目的核心不是单个 Tool 返回一段文本，也不是简单返回组件 ID，而是：

```text
配置选择组件
  -> 创建 workspace/component 实例
  -> 通过 workspace 事件推送给前端工作台
  -> Tool 结果只给 LLM 精简 observation 与 references
  -> 需要明细时通过 component_data_read 按 referenceId/index 补读
```

当前 Java 项目已经具备迁移基础：

- `ToolDefinition`：已经承载配置化工具元数据。
- `UITreeComponent`：已经承载前端组件的表格、图表、iframe、markdown 等展示信息。
- `ToolExecuteResult`：已经区分 `textForLlm` 与 `visualComponents`，具备双轨交付雏形。
- `ResearchStreamEvent`：已有 `components/payload` 字段，可扩展 `type=workspace` 事件。
- `docs/temp/tooldesign.md` 与 `docs/superpowers/plans/2026-09-15-fund-research-workspace-refactor.md` 已描述 Tool Runtime 与一版实施计划。

本设计建议：不要重建一套 Python Runtime，也不要让 Java 调 Python Tool。当前项目应把已有 `visualComponents` 升级为“工作台组件实例协议”，用 Java 内部的 workspace payload 承接参考项目的 `WorkspaceModel/ComponentModel` 语义。

## 2. 参考项目机制拆解

### 2.1 组件配置分层

参考项目通过 `UnifiedComponentConfig` 把组件配置收敛为统一结构：

```json
{
  "schemaVersion": "1.0",
  "schemaType": "component",
  "config": [
    {
      "id": "Fund-Analysis/cf_non_money_factsheet/NonMoneyCompanyProductStructureTable",
      "metadata": {
        "mapping": {},
        "workspaceType": "Fund-Analysis",
        "outputRefs": ["companyProductStructure"]
      },
      "process": {
        "provider": "fundinfra",
        "request": {},
        "response": {}
      },
      "output": []
    }
  ]
}
```

这套结构将职责拆开：

| 层级 | 职责 | 设计意义 |
| --- | --- | --- |
| `metadata.mapping` | 选择哪个组件 | 让 LLM 或业务规则只关心“需要什么维度” |
| `process` | 如何取数 | 屏蔽 MCP、FundInfra、Cloud、DataAgent 等差异 |
| `output` | 如何展示 | 表格、图表、Markdown、Card 布局统一进入渲染层 |

### 2.2 工作台状态模型

参考项目的 `WorkspaceModel` 结构：

```json
{
  "id": "workspace-id",
  "type": "FUND_ANALYSIS",
  "name": "基金分析",
  "referenceId": 1,
  "capabilities": ["component_analysis_edit"],
  "components": []
}
```

参考项目的 `ComponentModel` 结构：

```json
{
  "id": "FundInfoForDefault:005827.OF:2026-09-16",
  "type": "MCPTool",
  "nodeID": "",
  "name": "基金资料",
  "description": "展示基金基础资料",
  "prompt": "展示基金基础资料",
  "content": "",
  "config": {},
  "params": {},
  "layout": {},
  "status": "focused"
}
```

关键点：

- `config.id` 是组件模板 ID，来自配置。
- `component.id` 是组件实例 ID，应包含基金代码、日期、报告期等运行参数。
- `referenceId` 是本轮对话内工作台引用，供 `component_data_read` 使用。
- `status=focused` 用于控制哪些组件优先内联读取或展示。
- `capabilities` 控制后续是否支持单基金编辑、多基金比较编辑等操作。

### 2.3 Tool 运行时包装

参考项目 `ProgressToolProxy` 负责横切能力：

- Tool 执行前后发 `progress`。
- 检测 `context.workspace` 是否变化。
- workspace 变化时发 `workspace` 事件。
- 延迟短时间后读取部分组件明细。
- 把读取到的内容合并到 Tool result 的 references。
- Tool 自身不直接知道 SSE 或前端协议。

这说明 Java 侧也应保持同样边界：业务 Tool/Executor 只返回结构化结果；事件发布、artifact 保存、前端推送应由 Agent Runtime 或 DAG Runtime 接管。

### 2.4 明细读取模型

参考项目的 `component_data_read(referenceId, indexes)` 只做一件事：

```text
referenceId -> 找到 workspace
indexes -> 选择组件
component.id -> 调 ComponentDataClient.read(contextId, refId)
返回 references.items[index/name/content/description]
```

这使工作台组件可以先以“占位与元数据”形式展示，必要时再读取大表、图表或正文，避免 LLM observation 被大结果污染。

## 3. 当前项目现状

### 3.1 已有配置化工具能力

当前项目已有配置目录：

```text
copilot-agent-tools/src/main/resources/config/tools/
  analysis/
  comparison/
```

示例 `fund-position.json` 已包含：

- `toolId/name/scope/toolType/description`
- `provider/server/targetTool/templateId`
- `parameters`
- `components[]`
- 组件级 `componentId/name/cardTitle/displayType/metadata`

这与参考项目的统一配置目标高度接近，只是当前结构把 Tool 与组件配置合在一个文件里，缺少统一的 workspace 实例层。

### 3.2 已有执行链路

当前执行链路：

```text
FundAnalysisToolSet / FundComparisonToolSet
  -> ToolExecuteRequest
  -> ToolExecutorRouter
  -> HttpToolExecutor / McpToolExecutor / DataAgentToolExecutor
  -> ComponentResponseMapper
  -> ToolExecuteResult.textForLlm + visualComponents
```

当前已经有“双轨”雏形：

| 字段 | 当前用途 | 需要增强 |
| --- | --- | --- |
| `textForLlm` | 给 AgentScope 作为 Tool 返回文本 | 保持精简，补充 reference 提示 |
| `visualComponent/visualComponents` | 给前端工作台渲染的组件 | 升级为 workspace payload 的 components |
| `rawData` | 原始响应 | 保留给调试与审计 |
| `errorMessage` | 失败信息 | 增加组件级部分成功语义 |

### 3.3 当前缺口

当前项目距离参考项目还差 5 个关键能力：

1. 缺少 run 内唯一的 `workspace.referenceId` 与 workspace registry。
2. 缺少统一 `ToolWorkspacePayload`，无法表达完整工作台。
3. `visualComponents` 还不是稳定的 workspace component 实例协议。
4. `ResearchStreamEvent` 尚未明确 `type=workspace` 的事件工厂。
5. 缺少按 `referenceId/indexes` 读取组件明细的统一工具或 API。

## 4. 目标设计

### 4.1 总体链路

推荐链路：

```text
用户问题
  -> FundAnalyzerAgent / FundComparatorAgent
  -> 固定 Java @Tool 方法
  -> ToolExecutorRouter
  -> Provider Executor
  -> ComponentResponseMapper
  -> ToolExecuteResult
       - textForLlm
       - visualComponents
       - workspacePayload
       - references
  -> Agent Runtime 发布 workspace SSE
  -> 前端渲染工作台
  -> component_data_read/API 补读组件明细
```

设计原则：

- AgentScope 暴露的 Tool Signature 保持稳定，第一版不追求完全动态 Tool 注册。
- Provider 调用继续走现有配置化执行器，不引入 Python Runtime。
- Tool 执行器不直接发 SSE，不依赖 `copilot-agent-core`。
- `copilot-agent-core` 负责把工具结果挂到 DAG event、artifact、run workspace registry。
- LLM observation 只保留摘要与 references，前端工作台拿完整结构。

### 4.2 新增概念模型

建议在设计上引入以下模型：

| 模型 | 建议位置 | 职责 |
| --- | --- | --- |
| `ToolWorkspacePayload` | `copilot-agent-tools` | 表达一个工具产生的完整工作台 |
| `ToolWorkspaceComponent` | `copilot-agent-tools` | 表达可被前端渲染的组件实例 |
| `ToolWorkspaceReference` | `copilot-agent-tools` | 表达 LLM 可引用的组件索引 |
| `RunWorkspaceRegistry` | `copilot-agent-core` | 管理 run 内 referenceId 与 workspace |
| `ConfiguredToolWorkspacePublisher` | `copilot-agent-core` | 发布 workspace SSE 并保存 artifact |
| `ComponentDataClient` | `copilot-agent-tools` 或 `copilot-app` | 读取组件明细 |

### 4.3 Workspace Payload 协议

目标协议：

```json
{
  "id": "workspace-0d0c2c67",
  "type": "FUND_ANALYSIS",
  "name": "基金分析",
  "referenceId": 1,
  "capabilities": ["component_analysis_edit"],
  "components": [
    {
      "id": "MoneyBasicInfo:005827.OF:2026-09-16",
      "type": "MCPTool",
      "nodeID": "",
      "name": "基本信息",
      "description": "展示 005827.OF 基金基础资料",
      "prompt": "展示 005827.OF 基金基础资料",
      "content": "",
      "config": {
        "id": "ANALYSIS/fund_analysis_profile/MoneyBasicInfo",
        "name": "基本信息",
        "description": "展示基金基础资料",
        "displayType": "table",
        "process": []
      },
      "params": {
        "windCodes": ["005827.OF"],
        "endDate": "2026-09-16"
      },
      "layout": {
        "width": 24,
        "order": 1
      },
      "status": "focused"
    }
  ]
}
```

约定：

- `workspace.type`：单基金为 `FUND_ANALYSIS`，多基金为 `FUND_COMPARISON`。
- `workspace.referenceId`：run 内唯一，从 1 开始递增。
- `component.id`：稳定实例 ID，推荐格式为 `componentId:fundCodes:endDate/reportDate`。
- `component.config.id`：稳定模板 ID，推荐格式为 `scope/toolId/componentId`。
- `component.params`：只放后续可复用的取数参数，不能放 session、token、用户敏感信息。
- `component.layout.width`：对齐参考项目 card width 语义。
- `component.status`：优先支持 `focused`，用于明细读取与前端聚焦。

### 4.4 Tool Result 协议

`ToolExecuteResult` 应形成三层输出：

```json
{
  "success": true,
  "textForLlm": "已创建基金分析工作台，包含 3 个组件。重点组件摘要如下...",
  "workspacePayload": {},
  "references": [
    {
      "id": 1,
      "type": "component",
      "items": [
        {
          "index": 1,
          "name": "基本信息",
          "description": "基金基本资料、成立日、基金经理、规模等"
        }
      ]
    }
  ],
  "rawData": {}
}
```

规则：

- `textForLlm` 不放大表全文。
- `references.id` 对应 `workspace.referenceId`。
- `references.items.index` 从 1 开始，对应 workspace components 顺序。
- 若组件内容较短，可在 `references.items.content` 中内联；若较大，只给 description，并提示通过 `component_data_read` 读取。

### 4.5 SSE 事件协议

建议新增事件：

```json
{
  "type": "workspace",
  "runId": "run-xxx",
  "nodeId": "node-xxx",
  "payload": {
    "id": "workspace-xxx",
    "type": "FUND_ANALYSIS",
    "components": []
  },
  "components": {
    "id": "workspace-xxx",
    "type": "FUND_ANALYSIS",
    "components": []
  }
}
```

说明：

- `payload` 是主字段。
- `components` 保留兼容旧前端读取习惯。
- `type=workspace` 对齐参考项目 `event_handler.workspace(payload)`。
- 不建议让 executor 直接发 SSE，应由 Runtime 层统一转换。

### 4.6 组件数据读取协议

第一版可先做 HTTP API，再暴露为 AgentScope Tool：

```http
GET /api/research/workspace/components/{refId}/content?contextId=xxx
```

后续 Agent Tool 形态：

```json
{
  "tool": "component_data_read",
  "arguments": {
    "referenceId": 1,
    "indexes": [1, 3]
  }
}
```

返回：

```json
{
  "message": "已读取 2 个工作台组件数据。",
  "references": [
    {
      "id": 1,
      "type": "component",
      "items": [
        {
          "index": 1,
          "name": "基本信息",
          "content": "...",
          "description": "..."
        }
      ]
    }
  ]
}
```

读取约束：

- 只能读取当前 run/session 可见的 workspace。
- 用户要求修改时间、基金范围、报告期时，应先调整工作台，不能直接读旧组件。
- 默认优先读取 `focused` 组件。
- 需要设置字符预算，避免把大表全部塞回 LLM。

## 5. 配置结构设计

### 5.1 保留当前 Tool JSON，逐步对齐参考结构

当前项目已有配置文件很多，不建议一次性转换为参考项目 `.md` JSON。推荐第一阶段保持现有 JSON 结构，只补齐 workspace 所需字段。

当前结构：

```json
{
  "toolId": "fund_analysis_position",
  "scope": "ANALYSIS",
  "provider": "mcp",
  "server": "wind-fund-analysis",
  "targetTool": "fund_get_position_estimation",
  "components": [
    {
      "componentId": "position",
      "displayType": "table",
      "metadata": {
        "table": {},
        "width": 18
      }
    }
  ]
}
```

建议补齐后的兼容结构：

```json
{
  "toolId": "fund_analysis_position",
  "scope": "ANALYSIS",
  "provider": "mcp",
  "server": "wind-fund-analysis",
  "targetTool": "fund_get_position_estimation",
  "workspace": {
    "type": "FUND_ANALYSIS",
    "capabilities": ["component_analysis_edit"]
  },
  "components": [
    {
      "componentId": "position",
      "displayType": "table",
      "metadata": {
        "mapping": {
          "dimension": ["持仓", "仓位"],
          "fundType": ["普通型", "偏股型", "偏债型"]
        },
        "card": {
          "title": "仓位估算",
          "width": 18
        },
        "table": {}
      },
      "process": {
        "provider": "mcp",
        "request": {
          "server": "wind-fund-analysis",
          "tool": "fund_get_position_estimation"
        }
      },
      "output": [
        {
          "id": "table",
          "kind": "data",
          "dataType": "table",
          "name": "仓位估算",
          "metadata": {
            "card": {
              "title": "仓位估算",
              "width": 18
            },
            "table": {}
          }
        }
      ]
    }
  ]
}
```

第一版可以不强制所有文件都补 `process/output`，但新增组件应按该形态写，避免继续扩散字段。

### 5.2 字段收敛建议

| 当前字段 | 建议归属 | 说明 |
| --- | --- | --- |
| `displayType` | `output.dataType/chartType` 或组件快捷字段 | 兼容保留，长期收敛到 output |
| `metadata.table/chart/card` | `output[].metadata` | 当前可保留，loader 适配为 output |
| `provider/server/targetTool/templateId` | `process.provider/request` | Tool 级默认，组件可覆盖 |
| `columns/columnMapping/responseMapping` | `process.response` 与 `output.metadata.table` | 取数映射与展示列要分开 |
| `componentCount` | 可计算字段 | 不建议长期手写 |
| `width` | `output.metadata.card.width` | 工作台布局统一入口 |

## 6. 模块边界

### 6.1 `copilot-agent-tools`

职责：

- 加载配置。
- 执行 provider。
- 映射原始响应为 `UITreeComponent`。
- 生成 `ToolWorkspacePayload` 与 `references`。
- 生成 `textForLlm`。

不做：

- 不注入 `NodeEventBus`。
- 不保存 DAG artifact。
- 不直接依赖 `copilot-agent-core`。
- 不从 LLM 参数中接收 session/token/accountId。

### 6.2 `copilot-agent-core`

职责：

- 运行 Agent。
- 收集工具执行结果。
- 分配 run 内 workspace referenceId。
- 发布 `workspace` SSE。
- 保存 `WORKSPACE` artifact。
- 在 evidence contract 中挂 workspace artifact。

风险点：

- 若用 `ThreadLocal` 收集 AgentScope Tool 结果，异步或线程切换时可能丢上下文。
- 更稳妥的长期方案是 AgentScope RuntimeContext 或显式 invocation context。

### 6.3 `copilot-app`

职责：

- 暴露 stream API。
- 暴露 workspace component content API。
- 处理用户权限与参数校验。

不做：

- 不直接理解每种业务组件如何取数。
- 不把 component data 读取逻辑散落到 controller。

### 6.4 前端工作台

职责：

- 订阅 `type=workspace` SSE。
- 按 `workspace.components` 渲染卡片。
- 用 `layout.width/order/status` 布局与聚焦。
- 需要明细时按 `referenceId/index` 或 `component.id` 请求后端。

## 7. 推荐迁移阶段

### Phase 1：Workspace Payload 只在 tools 模块内生成

目标：

- 不改前端。
- 不改 SSE。
- 只让 `ToolExecuteResult` 增加 workspace payload 与 references。

验收：

- `visualComponents.size == workspacePayload.components.size`。
- 每个组件实例 ID 稳定。
- `references[0].id == workspacePayload.referenceId`。

### Phase 2：Core 发布 workspace SSE

目标：

- `ResearchStreamEvent` 增加 `workspace` 事件。
- Agent 执行后发布 workspace。
- 保存 `WORKSPACE` artifact。

验收：

- `/api/research/stream` 返回 SSE 中出现 `type=workspace`。
- payload 中包含 `type/referenceId/components`。
- node completed 的 artifactIds 可追溯 workspace artifact。

### Phase 3：组件数据读取 API

目标：

- 打通 `contextId/refId` 的组件明细读取。
- 支持前端点击卡片读取。
- 支持后续 Agent `component_data_read`。

验收：

- API 能读取指定组件内容。
- 缺少 `contextId`、`refId`、无权限时返回明确错误。
- 大内容有截断或预算控制。

### Phase 4：Agent Prompt 与回答风格收敛

目标：

- Prompt 明确工作台组件已经通过前端展示，最终回答聚焦结论。
- LLM 不重复罗列完整表格。
- 需要明细时才使用 `component_data_read`。

验收：

- 单基金分析先创建工作台，再基于摘要回答。
- 多基金比较能生成比较工作台。
- 回答能引用组件，但不把组件 JSON 泄露给用户。

### Phase 5：组件编辑与 Human-in-the-loop

目标：

- 支持 `component_analysis_edit`。
- 支持 `component_comparison_edit`。
- 支持缺参时 `interactive_form` 或 graph interrupt。

验收：

- “把业绩改成近三年”能更新 workspace。
- “增加 000001.OF 对比”能更新比较工作台。
- 缺基金代码时进入等待用户输入，而不是编造基金代码。

## 8. 最小可运行闭环

建议第一条闭环选 `fund_analysis_profile` 或 `compare_basic_info`，因为它们更容易验证：

```text
用户：分析 005827.OF 的基本资料
  -> fund_analysis_profile
  -> HttpToolExecutor
  -> ComponentResponseMapper
  -> ToolExecuteResult.textForLlm + workspacePayload
  -> Agent 输出摘要
  -> SSE 推送 workspace
  -> 前端渲染基本资料组件
```

期望 SSE：

```json
{
  "type": "workspace",
  "payload": {
    "type": "FUND_ANALYSIS",
    "referenceId": 1,
    "components": [
      {
        "id": "MoneyBasicInfo:005827.OF:2026-09-16",
        "config": {
          "id": "ANALYSIS/fund_analysis_profile/MoneyBasicInfo"
        }
      }
    ]
  }
}
```

## 9. 验收清单

### 9.1 配置加载

- Tool JSON 能继续加载，旧字段兼容。
- 新增组件配置能声明 `metadata/process/output`。
- 配置校验能发现重复 `componentId`、缺失输出、无效 provider。

### 9.2 组件实例

- 单基金组件 ID 包含基金代码和日期。
- 多基金组件 ID 包含多个基金代码，顺序稳定。
- 同一 run 内多次创建工作台 referenceId 不冲突。
- `focused` 状态能传到前端和数据读取流程。

### 9.3 Tool 执行

- HTTP/MCP/DataAgent 成功时都能生成 workspace。
- 部分组件失败时保留成功组件，并在 `textForLlm` 中提示部分失败。
- Executor 不直接发 SSE。

### 9.4 SSE 与 Artifact

- `type=workspace` 事件可序列化。
- `payload` 与 `components` 字段均可读。
- workspace artifact 可按 artifactId 查询。

### 9.5 数据读取

- `component_data_read(referenceId, indexes)` 能定位当前 workspace。
- 未找到 workspace 时给出可理解错误。
- 大内容不会无限进入 LLM 上下文。

## 10. 风险与处理

| 风险 | 影响 | 建议 |
| --- | --- | --- |
| `ThreadLocal` 收集工具结果在异步场景丢失 | workspace 不发布 | 短期限制同步调用，长期改 RuntimeContext |
| `referenceId` 在多节点并发下冲突 | component_data_read 读错 | 放入 run 级 registry，不放 executor 全局 AtomicInteger |
| 前端协议不一致 | workspace 事件无法渲染 | `payload` 和 `components` 双字段兼容 |
| 组件数据服务协议不确定 | content API 调不通 | 先确认 JSON/form-urlencoded 与 header 要求 |
| LLM 混用本地 Tool 和外部 Tool | 数据口径不一致 | 一次 run 内固定 `ToolSourceMode` |
| 组件配置继续发散 | 后续维护困难 | 新配置按 `metadata/process/output` 写，旧配置由 loader 兼容 |

## 11. 与已有文档关系

本设计文档与已有文档分工如下：

- `docs/temp/tooldesign.md`：偏 Tool Runtime、Provider、路由、执行器设计。
- `docs/temp/tool_catalog.md`：偏工具与 UI 组件清单。
- `docs/superpowers/plans/2026-09-15-fund-research-workspace-refactor.md`：偏实施计划与代码任务拆分。
- 本文档：偏参考项目机制分析、当前项目目标协议、组件工作台设计边界。

## 12. 最终建议

当前项目应按“配置化 Tool + workspace payload + SSE 工作台 + component_data_read”落地，而不是继续让 Tool 只返回 `String` 或只返回孤立 `UITreeComponent`。

第一版不要追求所有组件统一配置重构，先把已有 `visualComponents` 包成完整 workspace，并打通一个单基金或多基金组件闭环。闭环稳定后，再逐步把配置字段收敛到参考项目的 `metadata/process/output` 三层结构。
