# AgentScope Native ReAct Architecture

## Goal

Replace every custom or service-orchestrated agent with AgentScope 2.0 `ReActAgent`. The model must choose tools, observe their results, continue reasoning, and decide when to finish.

## Boundaries

- Keep the dynamic DAG runtime, typed artifacts, ownership, checkpoints, cancellation, timeout, and billing contracts.
- Remove the custom `BoundedAgentLoop`, its action/observation protocol, the unused `PlannerAgent`, deterministic planner fallback, and agent facades that only route to hand-written implementations.
- `LlmService` remains available only for non-agent administration and memory utilities. No agent or graph planner may depend on it.
- Build a fresh `ReActAgent` per node execution so concurrent graph runs never share agent state.

## Runtime

`AgentScopeAgentFactory` reads the active model settings and creates AgentScope's native `OpenAIChatModel` plus `ReActAgent`. It applies bounded iterations and forwards AgentScope usage to the existing billing callback. Each invocation receives an AgentScope `RuntimeContext` containing user, session, graph request, node input, and cancellation state.

Each domain agent owns a narrow AgentScope `Toolkit`. Existing financial tools are exposed through annotated adapter methods. Tool observations are retained in invocation-local state and used to build the typed artifact after AgentScope returns its final response. Business agents do not call tools before invoking ReAct.

## Agents

- `GraphPlannerAgent`: metric search, capability discovery, skill discovery, document search, and market memory; final structured output is a valid `GraphPlan`.
- `FundScreenerAgent` and `StockScreenerAgent`: screening tools only.
- `FundAnalyzerAgent` and `StockAnalyzerAgent`: quantitative and evidence retrieval tools only.
- `FundComparatorAgent`: symmetric metrics, holdings, reports, and graph-overlap tools.
- `ReportSynthesizerAgent`: reads bound typed artifacts and produces the final report.

The workflow routes task and asset categories to these native agents through a plain `AgentNodeRouter`. Classes named `Agent` are AgentScope-backed roles, not routing facades.

## Failure behavior

Invalid structured results, exhausted iterations, missing required tool evidence, model failures, and cancellation fail the node. There is no deterministic legacy execution fallback. DAG failure and replan policy remains responsible for recovery.

## Verification

Tests use a scripted AgentScope `Model` that first emits a tool call and then a final response. Architecture tests reject custom loops, `LlmService` dependencies in agents/planner, old facade classes, and deterministic planner fallback.
