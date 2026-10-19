# Contextual MCP Toolkits & User Whitelist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple hardcoded SubAgent toolkits into a configuration-driven, context-aware toolkit factory supporting MCP dynamic invocation and user-level tool whitelisting.

**Architecture:** Introduce `AgentRoleProperties` for YAML role-to-tools mapping, `UserToolPermissionService` for user permission resolution, and `ContextualToolkitFactory` to compute the runtime intersection of role tools and user whitelists into lightweight AgentScope `Toolkit` instances with MCP / local fallback dispatch.

**Tech Stack:** Java 21, Spring Boot 3.3.3, Project Reactor / WebFlux, AgentScope Java 2.0.0, Jackson.

---

## Global Constraints
- Strictly adhere to Java 21 and Spring WebFlux standards.
- Maintain Lombok, Chinese Javadoc, and clean import rules as defined in `.agents/rules/coding-standards.md`.
- Preserve existing backward-compatible test constructors and fallback paths when MCP client is disabled/offline.
- Ensure all Maven tests pass with `mvn -s maven-settings.xml -pl <module> test`.

---

## Task Breakdown

### Task 1: Add AgentRoleProperties and Configuration Mapping
- [ ] Create `AgentRoleProperties.java` in `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/config/` with `@ConfigurationProperties(prefix = "copilot.agent-roles")`.
- [ ] Add default role definitions and tool bindings in `copilot-app/src/main/resources/application.yml` for `FUND_ANALYZER`, `FUND_SCREENER`, `FUND_COMPARATOR`, `STOCK_ANALYZER`, `STOCK_SCREENER`, `STOCK_COMPARATOR`, `REPORT_SYNTHESIZER`.
- [ ] Write unit test `AgentRolePropertiesTest.java` verifying configuration loading and role-to-tool lookups.

### Task 2: Implement UserToolPermissionService
- [ ] Define interface `UserToolPermissionService.java` in `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/security/` specifying `Set<String> getAllowedTools(Long userId)`.
- [ ] Implement `DefaultUserToolPermissionService.java` supporting:
  - Global wildcard (`*`) or empty/null meaning all tools allowed.
  - User-specific explicit whitelists.
- [ ] Write unit test `DefaultUserToolPermissionServiceTest.java`.

### Task 3: Build ContextualToolkitFactory with Dynamic MCP Dispatch
- [ ] Create `ContextualToolkitFactory.java` in `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/toolkit/`.
- [ ] Support runtime intersection: `effectiveTools = roleBaseTools ∩ userWhitelist`.
- [ ] Register tool delegates that forward calls to `ReactiveMcpClient.callToolSync` when enabled, with graceful fallback to local Spring bean tools.
- [ ] Write comprehensive unit tests in `ContextualToolkitFactoryTest.java`:
  - Test intersection filtering: verify unauthorized tools (e.g. `fund_report`) are completely excluded for regular users.
  - Test VIP / wildcard users receive the full suite of role tools.
  - Test tool execution forwards to MCP client or local fallback appropriately.

### Task 4: Refactor Fund SubAgents to Use ContextualToolkitFactory
- [ ] Refactor `FundAnalyzerAgent.java` to obtain its `Toolkit` from `ContextualToolkitFactory.createToolkit(AgentRole.FUND_ANALYZER, context)`.
- [ ] Refactor `FundScreenerAgent.java` and `FundComparatorAgent.java` similarly.
- [ ] Preserve existing test convenience constructors for unit test compatibility.
- [ ] Run and update `FundAnalyzerAgentTest`, `FundComparatorAgentTest`, `FundScreenerAgentTest`, `DagRuntimeTest`.

### Task 5: Full Multi-Module Verification & Documentation
- [ ] Run `mvn -s maven-settings.xml -pl copilot-agent-core test`.
- [ ] Run `mvn -s maven-settings.xml -pl copilot-app test`.
- [ ] Verify clean builds across modules and report completed milestones.
