# AgentScope Native ReAct Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every project-defined agent loop and directly orchestrated agent with AgentScope 2.0 native `ReActAgent`.

**Architecture:** A small runtime factory creates isolated AgentScope models and agents from active configuration. Role agents expose existing financial capabilities as narrow AgentScope toolkits and return typed DAG artifacts; a plain router connects DAG node types to roles.

**Tech Stack:** Java 21, Spring Boot 3.3, AgentScope Java 2.0, Reactor, JUnit 5, Mockito, Jackson.

**Spec:** `docs/superpowers/specs/2026-09-12-agentscope-native-react.md`

## Global Constraints

- No custom ReAct loop or compatibility execution path.
- No `LlmService` dependency from an AgentScope-backed agent or planner.
- Create one AgentScope agent per invocation.
- Preserve typed DAG artifacts, cancellation, ownership, checkpoints, and billing.

---

### Task 1: Native AgentScope runtime

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agentscope/AgentScopeAgentFactory.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agentscope/AgentScopeInvocation.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/agentscope/AgentScopeAgentFactoryTest.java`

**Interfaces:**
- Produces: `AgentScopeInvocation invoke(AgentDefinition definition, String prompt, NodeExecutionContext context)` and an injectable `Model` provider for tests.
- Consumes: active `LlmSettingsDTO`, existing usage consumer, AgentScope `Toolkit`.

- [x] Write a scripted AgentScope model test that emits `ToolUseBlock`, receives `ToolResultBlock`, emits final text, and verifies usage forwarding and session isolation.
- [x] Run the focused test and confirm it fails because the runtime API does not exist.
- [x] Implement the minimal model factory, runtime context, bounded native `ReActAgent.call`, usage forwarding, and cancellation bridge.
- [x] Run the focused test and confirm it passes.

### Task 2: Native planner agent

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/GraphPlannerAgent.java`
- Modify: planner tool classes to expose AgentScope annotations through one invocation-local toolkit adapter.
- Delete: `GraphPlanner.java`, `DeterministicGraphPlanner.java`, `PlannerAction.java`, `PlannerObservation.java`, `PlannerAgent.java`.
- Test: replace planner tests with `GraphPlannerAgentTest.java`.

**Interfaces:**
- Produces: `ExecutionGraph plan(GraphPlanningRequest request)` and `GraphPatch advise(...)` through `RePlanAdvisor`.
- Consumes: `AgentScopeAgentFactory`, planner discovery tools, `GraphPlan` structured result.

- [x] Write failing tests proving the planner model calls discovery tools before finishing and invalid model output fails without deterministic fallback.
- [x] Run the focused planner tests and confirm the expected failures.
- [x] Implement the annotated planner toolkit and native ReAct structured result conversion.
- [x] Update workflow and replan dependencies to `GraphPlannerAgent` and run the focused tests.

### Task 3: Native domain agents and router

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/AgentNodeRouter.java`
- Rewrite: fund and stock agent classes under `agents/fund` and `agents/stock`.
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/ReportSynthesizerAgent.java`
- Delete: `ScreenerAgent.java`, `AnalyzerAgent.java`, `ComparatorAgent.java`, `ReportSynthesizer.java`.
- Test: replace facade and artifact contract tests with native AgentScope role tests.

**Interfaces:**
- Produces: `Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context)` for each role and `AgentNodeRouter.execute(...)` for workflow registration.
- Consumes: invocation-local AgentScope toolkits and bound typed inputs.

- [x] Write failing tests where scripted models choose a business tool, consume its observation, then finish with the expected typed artifact.
- [x] Run focused role tests and confirm they fail against the old direct orchestration.
- [x] Rewrite each role to invoke AgentScope first and build artifacts only from recorded tool observations plus final structured output.
- [x] Add the plain router and update workflow node registration.
- [x] Run all role and workflow tests.

### Task 4: Remove legacy architecture

**Files:**
- Delete: `agents/react/AgentAction.java`, `AgentObservation.java`, `BoundedAgentLoop.java` and their tests.
- Modify: `NoLegacyExecutionArchitectureTest.java`.
- Modify: affected prompts, tests, and documentation references.

**Interfaces:**
- Produces: an architecture test that scans production sources.
- Consumes: the final source tree.

- [x] Add failing assertions rejecting custom loop classes, old facades, deterministic planner, and `LlmService` imports from agent/planner packages.
- [x] Run the architecture test and confirm it detects the legacy files.
- [x] Delete legacy sources and update remaining callers.
- [x] Run the architecture and module tests.

### Task 5: Full verification

**Files:**
- Modify only defects exposed by verification.

**Interfaces:**
- Produces: a clean reactor build with all tests passing.
- Consumes: all modules.

- [x] Run `mvn clean test` with the repository Maven settings and local repository.
- [x] Run integration startup and billing persistence tests with `copilot.integration=true`.
- [x] Scan production sources for forbidden legacy symbols and inspect `git diff --check`.
- [x] Review the spec requirement by requirement and commit the complete migration.
