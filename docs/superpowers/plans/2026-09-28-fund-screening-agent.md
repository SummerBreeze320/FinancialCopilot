# 基金筛选 Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立独立 FastAPI 基金筛选服务，将自然语言稳定转换为 `fund-screening/v1` DSL，并支持 HTTP、WebSocket、多轮编辑、三路候选召回和可观测五段编排。

**Architecture:** 新服务位于 `fund-screening-service/`，采用 Pydantic 模型约束请求、候选契约、阶段输出和最终 DSL。编排器固定执行 preprocess → analysis/recall → command build → validator/repair → finalize；Milvus 和模型调用通过端口抽象，单元测试使用内存替身。

**Tech Stack:** Python 3.11、FastAPI、Pydantic v2、WebSocket、AgentScope、Milvus、Redis、pytest、httpx。

**Spec:** `docs/superpowers/specs/2026-09-28-fund-screening-agent-design.md`

## Global Constraints

- 服务目录固定为 `fund-screening-service/`，不修改现有 Java 模块。
- 最终 DSL 只含执行语义：不得含 `asset`、catalog 版本、候选分数、调试信息或内部 ID。
- 每个业务筛选组必须有 `sector` 与 `metric`；未指定板块时注入 `1000019220000000`。
- `metric` 与 `sector` 分别使用递归 `AND`、`OR`、`NOT` 逻辑树，`NOT` 只允许一个 child。
- 只能从 Candidate Contract Index 绑定指标、板块或实体；禁止 LLM 编造 catalog 外 key。
- 最大回撤的用户正值阈值须归一化为底层负值及反向操作符。
- HTTP 与 WebSocket 对同一输入必须产生等价 DSL；测试不能依赖真实 Milvus、Redis 或 LLM。

---

## File Structure

```text
fund-screening-service/
  pyproject.toml                         # 依赖与 pytest 配置
  README.md                              # 启动、接口、DSL 示例
  app/
    main.py                              # FastAPI app、lifespan、路由和统一错误
    config.py                            # 环境变量与默认阈值
    models/api.py                        # HTTP/WS 请求、响应、事件模型
    models/dsl.py                        # fund-screening/v1 Pydantic 递归模型
    models/candidate.py                  # Candidate Contract 与 recall plan
    catalog/loader.py                    # 加载 metrics.json、sectors.json 与实体目录
    catalog/contracts.py                 # catalog 记录转 Candidate Contract
    retrieval/ports.py                   # Metric/Scope/Entity 检索端口
    retrieval/milvus.py                  # Milvus 向量检索适配器
    retrieval/lexical.py                 # 实体 lexical 检索适配器
    pipeline/preprocess.py               # 归一化及新建/编辑判断
    pipeline/analysis.py                 # pre-recall plan 与候选裁决接口
    pipeline/router.py                   # direct/simple/full 判定
    pipeline/builder.py                  # 候选绑定到 DSL AST
    pipeline/validator.py                # Schema、catalog、单位与修复规则
    pipeline/finalizer.py                # 去内部字段、形成 API 响应
    pipeline/orchestrator.py             # 五段编排与阶段事件
    pipeline/editor.py                   # simple/complex 编辑策略
    evaluation/models.py                 # case 与命中统计模型
    evaluation/report.py                 # Top-1/Top-3、错误归因报告
  tests/
    fixtures/metrics-small.json
    fixtures/sectors-small.json
    fixtures/entities-small.json
    unit/...
    api/...
```

### Task 1: 服务骨架、配置与健康检查

**Files:**
- Create: `fund-screening-service/pyproject.toml`
- Create: `fund-screening-service/app/config.py`
- Create: `fund-screening-service/app/main.py`
- Create: `fund-screening-service/tests/api/test_health.py`

**Interfaces:**
- Produces `Settings` with `host`, `port`, `metrics_catalog_path`, `sectors_catalog_path`, `default_sector_id`, `group_limit_default`, `group_limit_max`.
- Produces `create_app()` and `GET /v1/fund-screening/health`.

- [ ] **Step 1: Write the failing health test**

```python
async def test_health_returns_service_and_dependency_states(client):
    response = await client.get("/v1/fund-screening/health")
    assert response.status_code == 200
    assert response.json()["service"] == "fund-screening-service"
    assert set(response.json()["dependencies"]) == {"catalog", "milvus", "redis", "llm"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd fund-screening-service; python -m pytest tests/api/test_health.py -v`

Expected: FAIL because the service package does not exist.

- [ ] **Step 3: Implement the smallest application shell**

```python
def create_app() -> FastAPI:
    app = FastAPI(title="Fund Screening Service", version="0.1.0")

    @app.get("/v1/fund-screening/health")
    async def health() -> dict[str, object]:
        return {"service": "fund-screening-service", "dependencies": {
            "catalog": "configured", "milvus": "configured",
            "redis": "configured", "llm": "configured",
        }}
    return app
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd fund-screening-service; python -m pytest tests/api/test_health.py -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add fund-screening-service
git commit -m "feat: scaffold fund screening service"
```

### Task 2: DSL Pydantic Schema 与 JSON Schema 端点

**Files:**
- Create: `fund-screening-service/app/models/dsl.py`
- Create: `fund-screening-service/app/api/schema.py`
- Create: `fund-screening-service/tests/unit/test_dsl_schema.py`

**Interfaces:**
- Produces `FundScreeningDsl`, `BusinessGroup`, `MetricExpression`, `SectorExpression`, `MetricPredicate`, `SortItem`.
- `FundScreeningDsl.model_json_schema()` is exposed at `GET /v1/fund-screening/schema`.

- [ ] **Step 1: Write failing schema tests**

```python
def test_dsl_allows_duplicate_metric_with_nested_or():
    dsl = FundScreeningDsl.model_validate(EXAMPLE_WITH_TWO_RETURN_PREDICATES)
    assert dsl.children[0].metric.operator == "AND"

def test_business_group_requires_sector_and_metric():
    with pytest.raises(ValidationError):
        BusinessGroup.model_validate({"metric": {"operator": "AND", "children": []}})

def test_not_requires_exactly_one_child():
    with pytest.raises(ValidationError):
        MetricExpression.model_validate({"operator": "NOT", "children": [PREDICATE, PREDICATE]})
```

- [ ] **Step 2: Run tests to verify failure**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_dsl_schema.py -v`

Expected: FAIL with import errors.

- [ ] **Step 3: Implement recursive discriminated-by-shape models**

Use a `MetricPredicate` model for `{metric, operator, value, unit, period?}` and a recursive `MetricExpression` union of predicate and `{operator, children}`. Model validators must enforce `AND|OR|NOT`, nonempty `AND|OR`, and exactly one `NOT` child. `BusinessGroup` must declare required `sector` and `metric`, optional `sort`, optional positive `limit`; the root model declares `version`, `operator`, `children`, `sort`, `limit`.

- [ ] **Step 4: Run tests to verify pass**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_dsl_schema.py -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add fund-screening-service/app/models fund-screening-service/app/api fund-screening-service/tests/unit/test_dsl_schema.py
git commit -m "feat: define fund screening dsl schema"
```

### Task 3: Catalog、Candidate Contract 与三路检索端口

**Files:**
- Create: `fund-screening-service/app/models/candidate.py`
- Create: `fund-screening-service/app/catalog/loader.py`
- Create: `fund-screening-service/app/catalog/contracts.py`
- Create: `fund-screening-service/app/retrieval/ports.py`
- Create: `fund-screening-service/app/retrieval/lexical.py`
- Create: `fund-screening-service/tests/fixtures/metrics-small.json`
- Create: `fund-screening-service/tests/fixtures/sectors-small.json`
- Create: `fund-screening-service/tests/fixtures/entities-small.json`
- Create: `fund-screening-service/tests/unit/test_catalog_contracts.py`
- Create: `fund-screening-service/tests/unit/test_lexical_retrieval.py`

**Interfaces:**
- Produces immutable `CandidateContract(domain, key, name, aliases, supported_operators, value_type, supported_usage, binding_role, preferred_metric, score)`.
- Produces async `MetricRetriever.search(text, top_k)`, `ScopeRetriever.search(text, top_k)`, `EntityRetriever.search(text, top_k)`.

- [ ] **Step 1: Write failing catalog and entity tests**

```python
def test_metric_contract_keeps_mnemonic_and_supported_usage(catalog):
    contract = catalog.metric_contract("f_risk_maxdownside")
    assert contract.domain == "metric"
    assert "sort" in contract.supported_usage

async def test_lexical_entity_search_prefers_exact_alias(retriever):
    result = await retriever.search("张坤管理的基金", top_k=3)
    assert result[0].binding_role == "FUND_MANAGER"
```

- [ ] **Step 2: Run tests to verify failure**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_catalog_contracts.py tests/unit/test_lexical_retrieval.py -v`

Expected: FAIL because catalog modules are absent.

- [ ] **Step 3: Implement loader and contracts**

Read the supplied metric and sector JSON without mutating it. Convert `mnemonic` to metric key and `source_sector_id` to scope key. Seed the entity fixture with company, fund-manager, stock and index roles. Lexical retrieval must score exact canonical names and aliases before token containment, returning only catalog entities.

- [ ] **Step 4: Run tests to verify pass**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_catalog_contracts.py tests/unit/test_lexical_retrieval.py -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add fund-screening-service/app/catalog fund-screening-service/app/models/candidate.py fund-screening-service/app/retrieval fund-screening-service/tests
git commit -m "feat: add candidate contracts and lexical entity retrieval"
```

### Task 4: Milvus retrieval adapters and candidate-pool aggregation

**Files:**
- Create: `fund-screening-service/app/retrieval/milvus.py`
- Create: `fund-screening-service/app/pipeline/analysis.py`
- Create: `fund-screening-service/tests/unit/test_analysis_recall.py`

**Interfaces:**
- Produces `MilvusMetricRetriever` and `MilvusScopeRetriever`; both implement the ports from Task 3.
- Produces `RecallPlan`, `ConditionPlan`, `CandidatePool`, and `AnalysisService.analyze_and_recall(query) -> CandidatePool`.

- [ ] **Step 1: Write failing aggregation test**

```python
async def test_analysis_uses_three_retrievers_and_returns_contract_index(service):
    pool = await service.analyze_and_recall("半导体基金最大回撤低于5%，张坤管理")
    assert pool.contract("f_risk_maxdownside").domain == "metric"
    assert pool.contract("1000022713000000").domain == "scope"
    assert pool.contract("manager:zhangkun").domain == "entity"
```

- [ ] **Step 2: Run test to verify failure**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_analysis_recall.py -v`

Expected: FAIL because `AnalysisService` is absent.

- [ ] **Step 3: Implement retriever adapters and analysis service**

Milvus adapters accept an injected embedding client and collection client, map hit metadata to Candidate Contract, and reject keys absent from the active catalog. The analysis service accepts an injected plan generator, executes only the domains named in `RecallPlan`, merges candidates by `(domain, key)`, and builds a lookup index. Tests use fake retrievers and a deterministic plan generator.

- [ ] **Step 4: Run test to verify pass**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_analysis_recall.py -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add fund-screening-service/app/retrieval/milvus.py fund-screening-service/app/pipeline/analysis.py fund-screening-service/tests/unit/test_analysis_recall.py
git commit -m "feat: aggregate three-way recall candidates"
```

### Task 5: Preprocess、路由与多轮编辑判断

**Files:**
- Create: `fund-screening-service/app/pipeline/preprocess.py`
- Create: `fund-screening-service/app/pipeline/router.py`
- Create: `fund-screening-service/app/pipeline/editor.py`
- Create: `fund-screening-service/tests/unit/test_preprocess.py`
- Create: `fund-screening-service/tests/unit/test_router.py`
- Create: `fund-screening-service/tests/unit/test_editor.py`

**Interfaces:**
- Produces `PreprocessedQuery(query, normalized_query, previous_dsl, edit_kind)`.
- Produces `Route.DIRECT | SIMPLE | FULL` via `classify_route(preprocessed, recall_plan, pool)`.
- Produces `apply_simple_edit(previous_dsl, query) -> FundScreeningDsl | None`.

- [ ] **Step 1: Write failing normalization, route and edit tests**

```python
def test_preprocess_normalizes_chinese_amount_and_percent():
    result = preprocess("规模大于十亿，回撤低于15％", None)
    assert "10亿" in result.normalized_query and "15%" in result.normalized_query

def test_route_is_full_for_cross_group_or():
    assert classify_route(PREPROCESSED, PLAN_WITH_TWO_GROUPS_AND_OR, POOL) is Route.FULL

def test_simple_edit_changes_only_final_limit():
    changed = apply_simple_edit(PREVIOUS_DSL, "改成前50只")
    assert changed.limit == 50
```

- [ ] **Step 2: Run tests to verify failure**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_preprocess.py tests/unit/test_router.py tests/unit/test_editor.py -v`

Expected: FAIL with missing pipeline modules.

- [ ] **Step 3: Implement deterministic preprocessing and routing**

Normalize full-width punctuation, Chinese numbers used as amounts, percentage tokens and `近三月/近一年/近三年`. A request with previous DSL and only delete/limit/sort direction vocabulary is simple edit. Route direct only when one high-confidence complete candidate group has no nested logic; route simple for one AND-only group; otherwise full. A simple edit must parse and return a validated copy of the supplied DSL, never mutate input.

- [ ] **Step 4: Run tests to verify pass**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_preprocess.py tests/unit/test_router.py tests/unit/test_editor.py -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add fund-screening-service/app/pipeline fund-screening-service/tests/unit
git commit -m "feat: add query routing and simple dsl edits"
```

### Task 6: Command builder, entity binding and default-sector injection

**Files:**
- Create: `fund-screening-service/app/pipeline/builder.py`
- Create: `fund-screening-service/tests/unit/test_command_builder.py`

**Interfaces:**
- Produces `CommandBuilder.build(decision, candidate_pool) -> FundScreeningDsl`.
- Produces `BuildDiagnostics(default_sector_injected, entity_bindings, rejected_keys)` internally.

- [ ] **Step 1: Write failing build tests**

```python
def test_builder_creates_two_business_groups_with_independent_sectors(builder, pool):
    dsl = builder.build(TWO_GROUP_DECISION, pool)
    assert len(dsl.children) == 2
    assert dsl.children[0].sector.children[0].sector_id == "1000022713000000"

def test_builder_injects_default_sector_when_group_has_only_metrics(builder, pool):
    dsl = builder.build(METRIC_ONLY_DECISION, pool)
    assert dsl.children[0].sector.children[0].sector_id == "1000019220000000"

def test_entity_binding_uses_contract_preferred_metric(builder, pool):
    dsl = builder.build(ZHANG_KUN_DECISION, pool)
    assert dsl.children[0].metric.children[0].metric == "f_info_fundmanager"
```

- [ ] **Step 2: Run tests to verify failure**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_command_builder.py -v`

Expected: FAIL because `CommandBuilder` is absent.

- [ ] **Step 3: Implement candidate-constrained builder**

The decision input may refer only to candidate keys. Resolve entity conditions through `preferred_metric` and allowed operators. Build the exact DSL shape defined in Task 2; inject the configured default sector only when the business group contains a metric expression without a recalled scope. Do not serialize diagnostics into the DSL.

- [ ] **Step 4: Run tests to verify pass**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_command_builder.py -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add fund-screening-service/app/pipeline/builder.py fund-screening-service/tests/unit/test_command_builder.py
git commit -m "feat: build candidate-bound fund screening commands"
```

### Task 7: Validator、可安全修复与最终收口

**Files:**
- Create: `fund-screening-service/app/pipeline/validator.py`
- Create: `fund-screening-service/app/pipeline/finalizer.py`
- Create: `fund-screening-service/tests/unit/test_validator.py`
- Create: `fund-screening-service/tests/unit/test_finalizer.py`

**Interfaces:**
- Produces `ValidationResult(dsl, repairs, clarification)` from `validate_and_repair(dsl, contracts)`.
- Produces `finalize(validation_result, debug) -> FinalizedCommand`.

- [ ] **Step 1: Write failing validation tests**

```python
def test_validator_normalizes_positive_drawdown_threshold_to_negative():
    result = validate_and_repair(DRAWDOWN_LT_15, CONTRACTS)
    leaf = result.dsl.children[0].metric.children[0]
    assert (leaf.operator, leaf.value) == (">=", -15)

def test_validator_rejects_unknown_sort_metric():
    result = validate_and_repair(DSL_WITH_UNKNOWN_SORT, CONTRACTS)
    assert result.clarification.code == "UNKNOWN_SORT_METRIC"

def test_finalizer_never_serializes_internal_repair_log_when_debug_is_false():
    payload = finalize(VALIDATION_RESULT, debug=False).model_dump()
    assert "repairs" not in payload["dsl"]
```

- [ ] **Step 2: Run tests to verify failure**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_validator.py tests/unit/test_finalizer.py -v`

Expected: FAIL because validator and finalizer do not exist.

- [ ] **Step 3: Implement validation rules**

Validate every catalog key, filter/sort capability, allowed operator, value type, period and positive limit. For a group limit with no sort, add deterministic `fund_code ASC` internally and record the repair. Normalize maximum drawdown phrase/decision representation to the negative storage convention. Return a clarification object for ambiguous or intent-changing corrections; finalizer removes all diagnostics from the DSL while optionally returning them beside it.

- [ ] **Step 4: Run tests to verify pass**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_validator.py tests/unit/test_finalizer.py -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add fund-screening-service/app/pipeline/validator.py fund-screening-service/app/pipeline/finalizer.py fund-screening-service/tests/unit
git commit -m "feat: validate and finalize screening dsl"
```

### Task 8: 五段编排、HTTP 与 WebSocket

**Files:**
- Create: `fund-screening-service/app/models/api.py`
- Create: `fund-screening-service/app/pipeline/orchestrator.py`
- Create: `fund-screening-service/app/api/commands.py`
- Create: `fund-screening-service/tests/api/test_commands.py`
- Create: `fund-screening-service/tests/api/test_websocket.py`

**Interfaces:**
- Produces `POST /v1/fund-screening/commands` accepting `{query, previousDsl?, conversationId?, debug}`.
- Produces WebSocket `/v1/fund-screening/ws` events `stage_started`, `stage_completed`, `repair_applied`, `clarification_required`, `finalized`, `failed`.
- Produces `ScreeningOrchestrator.run(request, emit) -> FinalizedCommand`.

- [ ] **Step 1: Write failing API equivalence tests**

```python
async def test_http_returns_final_dsl_for_supported_query(client):
    response = await client.post("/v1/fund-screening/commands", json={"query": QUERY})
    assert response.status_code == 200
    assert response.json()["dsl"]["version"] == "fund-screening/v1"

def test_websocket_emits_all_five_stages_and_same_dsl(client):
    with client.websocket_connect("/v1/fund-screening/ws") as socket:
        socket.send_json({"query": QUERY})
        events = receive_until_finalized(socket)
    assert [event["stage"] for event in events if event["type"] == "stage_completed"] == FIVE_STAGES
    assert events[-1]["dsl"] == HTTP_DSL
```

- [ ] **Step 2: Run tests to verify failure**

Run: `cd fund-screening-service; python -m pytest tests/api/test_commands.py tests/api/test_websocket.py -v`

Expected: FAIL because routes and orchestrator are absent.

- [ ] **Step 3: Implement evented orchestration**

Call the five stages in order, emitting start/completion events around each. For simple edit, emit preprocess then editor decision and still invoke validator/finalizer; for direct/simple/full use the same finalizer. HTTP awaits the orchestrator without exposing stage events. WebSocket sends structured events using the same request and returned DSL. Inject fake pipeline dependencies in API tests.

- [ ] **Step 4: Run tests to verify pass**

Run: `cd fund-screening-service; python -m pytest tests/api/test_commands.py tests/api/test_websocket.py -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add fund-screening-service/app fund-screening-service/tests/api
git commit -m "feat: expose screening orchestration over http and websocket"
```

### Task 9: RAG 评估闭环、README 与全量验证

**Files:**
- Create: `fund-screening-service/app/evaluation/models.py`
- Create: `fund-screening-service/app/evaluation/report.py`
- Create: `fund-screening-service/tests/unit/test_evaluation_report.py`
- Create: `fund-screening-service/README.md`

**Interfaces:**
- Produces `EvaluationCase`, `RecallObservation`, and `render_report(cases) -> dict` with Top-1, Top-3 and error reason counts.
- Documents HTTP, WebSocket, catalog configuration, DSL and local test commands.

- [ ] **Step 1: Write failing evaluation test**

```python
def test_report_counts_top1_top3_and_error_reasons():
    report = render_report([
        EvaluationCase(expected_key="f_return_3y", recalled_keys=["f_return_3y"]),
        EvaluationCase(expected_key="f_info_existingyear", recalled_keys=["x", "f_info_existingyear"], error_reason="alias_missing"),
    ])
    assert report["top1_rate"] == 0.5
    assert report["top3_rate"] == 1.0
    assert report["error_reasons"]["alias_missing"] == 1
```

- [ ] **Step 2: Run test to verify failure**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_evaluation_report.py -v`

Expected: FAIL because the evaluation package is absent.

- [ ] **Step 3: Implement report and operating guide**

Count the expected key's one-based rank as Top-1 or Top-3. Preserve raw case IDs and error categories to distinguish missing aliases, weak embedding text, vector recall and rerank failures. README must use the existing entity-service style, include `python -m pip install -e ".[dev]"`, catalog environment variables, REST/WebSocket samples and `python -m pytest -v`.

- [ ] **Step 4: Run focused and complete tests**

Run: `cd fund-screening-service; python -m pytest tests/unit/test_evaluation_report.py -v; python -m pytest -v`

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add fund-screening-service/app/evaluation fund-screening-service/tests/unit/test_evaluation_report.py fund-screening-service/README.md
git commit -m "feat: add screening rag evaluation workflow"
```

## Plan Self-Review

- **Spec coverage:** Task 2 covers final DSL; Tasks 3–4 cover Candidate Contract and three-way RAG; Task 5 covers direct/simple/full and editing; Tasks 6–7 cover binding, validation and repair; Task 8 covers five-stage observability plus HTTP/WebSocket; Task 9 covers data governance and documentation.
- **Scope:** All units are necessary parts of one independently deployable service; no existing Java subsystem is changed.
- **Type consistency:** `CandidateContract`, `CandidatePool`, `FundScreeningDsl`, `ValidationResult` and `FinalizedCommand` are defined before their consumers.
- **Placeholder scan:** No deferred implementation markers are present; each implementation task includes the expected interface, failing test, command and verification outcome.
