# Fund Metric Parameter Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 169 个基金指标建立人工审阅的有序参数契约和结果契约，将其可靠同步到 PostgreSQL，并为基金筛选服务提供受契约约束的 LLM 补参、结果校验和函数序列化能力。

**Architecture:** `docs/temp/metrics.json` 是唯一数据源，PostgreSQL `rag_fund_metric` 是运行时镜像。输入参数以有序 JSONB 保存并使用显式 `position` 防止错序；输出类型、精度、符号单位、支持运算符和特殊存储口径保存在 result contract。LLM 只负责在给定契约内填值，所有类型、单位、枚举和最终参数顺序由确定性代码控制。

**Tech Stack:** JSON Schema Draft 2020-12、Python 3.11+、PostgreSQL 16、JSONB、asyncpg、LangChain/LangGraph、pytest。

**Spec:** `docs/superpowers/specs/2026-09-29-fund-metric-parameter-contracts-design.md`

## Global Constraints

- 在 `lite-mysql` worktree 完成 Tasks 1–7；不得修改 `fund-screening-agent` worktree。
- `docs/temp/metrics.json` 是唯一数据源；PostgreSQL 只能由 JSON 单向同步。
- 禁止通过指标前缀或脚本批量推断、生成、覆盖参数；169 条必须逐条阅读并人工编辑。
- 禁止按输出类型批量套用结果契约；169 条结果类型、精度、符号单位和支持运算符同样必须逐条分析并写入 JSON。
- 参数顺序以连续的 `position` 为准，JSON 数组顺序必须与 `position` 一致。
- 枚举运行值必须为整数；没有权威依据的编码标记 `NEEDS_REVIEW`，不得标记 `REVIEWED`。
- `unit` 必须是 `%`、`元`、`万元`、`亿元`、`天`、`月`、`年`、`名` 等真实符号/中文单位；无单位使用 `null`，禁止英文单位代码。
- 同步参数元数据时不得更新或重新生成 embedding。
- 每个任务严格执行测试先行并单独提交；Agy 每次只执行一个 Stage，返回证据后等待 Codex 放行。

---

### Task 1: 固化参数契约 JSON Schema 与失败样例

**Files:**
- Create: `docs/temp/metric-parameter-contract.schema.json`
- Create: `scripts/data_sync/tests/test_metric_parameter_contracts.py`
- Create: `scripts/data_sync/validate_metric_parameter_contracts.py`

**Interfaces:**
- Produces: `validate_catalog(path: Path) -> ValidationReport`
- Produces: `ValidationReport(total: int, valid: int, errors: list[str])`
- Consumes: Python 标准库；不得引入 JSON Schema 运行时依赖。

- [ ] **Step 1: 写失败测试，固定结构、顺序和枚举规则**

测试必须覆盖：合法输入和结果契约；数组顺序与 `position` 不一致；位置重复/跳号；未知 `valueType`；输入或结果 `ENUM` 缺少 options；枚举 value 非整数或重复；单边 `pairWith`；日期缺少 `format: yyyyMMdd`；DECIMAL 缺少 precision；非 DECIMAL 错带 precision；英文单位代码；非法运算符；字符串结果使用大小比较；非法 `contract_status`。

- [ ] **Step 2: 运行测试并确认因校验器不存在而失败**

Run: `python -m unittest scripts.data_sync.tests.test_metric_parameter_contracts -v`

Expected: FAIL，原因是 `validate_metric_parameter_contracts` 或 `validate_catalog` 尚不存在。

- [ ] **Step 3: 实现最小校验器与 JSON Schema**

校验器逐条返回带 mnemonic 和 JSON 路径的错误；禁止静默修复或重排。Schema 必须要求 `parameters`、`result`、`parameter_schema_version`、`contract_status`，并限制设计文档中的类型、精度、单位、运算符与状态集合。

- [ ] **Step 4: 运行测试并确认通过**

Run: `python -m unittest scripts.data_sync.tests.test_metric_parameter_contracts -v`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add docs/temp/metric-parameter-contract.schema.json scripts/data_sync/validate_metric_parameter_contracts.py scripts/data_sync/tests/test_metric_parameter_contracts.py
git commit -m "feat(metrics): define ordered parameter contract schema"
```

### Task 2: 人工维护 info 族 52 个指标

**Files:**
- Modify: `docs/temp/metrics.json`
- Test: `scripts/data_sync/tests/test_metric_parameter_contracts.py`

**Interfaces:**
- Consumes: Task 1 的 `validate_catalog`。
- Produces: 52 条 `f_info_*` 指标契约。

- [ ] **Step 1: 增加 info 族覆盖测试并确认失败**

测试筛选 mnemonic 以 `f_info_` 开头的记录，断言数量为 52，且每条都有四个契约字段并通过结构校验。运行后应因字段缺失失败。

- [ ] **Step 2: 逐条人工编辑 52 个指标**

对每条指标单独阅读名称、描述和 embedding text；不得使用生成脚本。当前状态类通常仅接收 `windCode`；历史费率增加有效日期；成立年限增加计算截止日期。每条记录同时人工填写 `result.valueType`、适用的 precision/displayScale、符号 unit 或 null、supportedOperators、枚举整数 options 和特殊 storageConvention。返回枚举/布尔值不得误当作函数入参。每条记录标记 `INFERRED` 或 `NEEDS_REVIEW`。

- [ ] **Step 3: 运行族测试和全目录校验**

Run: `python -m unittest scripts.data_sync.tests.test_metric_parameter_contracts -v`

Run: `python scripts/data_sync/validate_metric_parameter_contracts.py docs/temp/metrics.json`

Expected: info 族 52/52 通过；其余族允许报告为未迁移，但不能出现 info 族结构错误。

- [ ] **Step 4: 人工抽查至少 10 条**

必须包含 `f_info_windcode`、`f_info_fundmanager`、`f_info_existingyear`、`f_info_risklevel`、`f_info_redemptionfee2`、`f_info_managementfeeratio2`、`f_info_trackindexcode`、`f_info_etfornot`、`f_info_pchmstatus`、`f_info_ptmyear`。在提交说明中列出其函数签名。

- [ ] **Step 5: 提交**

```bash
git add docs/temp/metrics.json scripts/data_sync/tests/test_metric_parameter_contracts.py
git commit -m "data(metrics): annotate info parameter contracts"
```

### Task 3: 人工维护收益、行情和事件族 51 个指标

**Files:**
- Modify: `docs/temp/metrics.json`
- Test: `scripts/data_sync/tests/test_metric_parameter_contracts.py`

**Interfaces:**
- Produces: `return(14) + nav(17) + mmf(5) + dq(3) + pq(3) + quot(1) + div(2) + netasset(3) + pchredm(1) + customizedfundornot(1) + issue(1)` 共 51 条契约。

- [ ] **Step 1: 增加上述族精确计数与覆盖测试，运行并确认失败**

- [ ] **Step 2: 逐条人工编辑固定窗口、自然周期和自定义区间参数**

固定窗口指标如 `f_return_1y` 使用 `windCode,endDate`；单月/季度/年度指标增加对应自然周期参数；自定义区间指标使用成对 `startDate,endDate`；指定交易日指标使用 `date`；报告期指标使用 `reportDate`。逐条填写 result；收益率一般为 `%` 但仍须确认，成交金额必须明确底层是 `元`、`万元` 或 `亿元`。不得仅凭前缀复制模板，必须逐条比对描述。

- [ ] **Step 3: 运行校验并抽查边界指标**

抽查 `f_return_1y`、`f_return_m`、`f_return_q`、`f_return_y`、`f_nav_adjustedreturn`、`f_nav_periodreturnranking_y`、`f_mmf_annualizedyield`、`f_mmf_avgannualizedyield`、`f_dq_amount`、`f_pq_amount`、`f_netasset_total_cc`、`f_div_times`。

- [ ] **Step 4: 提交**

```bash
git add docs/temp/metrics.json scripts/data_sync/tests/test_metric_parameter_contracts.py
git commit -m "data(metrics): annotate return and market parameter contracts"
```

### Task 4: 人工维护 risk 族 31 个指标

**Files:**
- Modify: `docs/temp/metrics.json`
- Test: `scripts/data_sync/tests/test_metric_parameter_contracts.py`

**Interfaces:**
- Produces: 31 条 `f_risk_*` 契约。

- [ ] **Step 1: 增加 risk 族 31/31 覆盖测试并确认失败**

- [ ] **Step 2: 逐条人工编辑风险区间和基准参数**

区间风险指标使用 `windCode,startDate,endDate`；只有指标语义确实依赖比较基准时才加入 `indexCode`。`indexCode` 默认值可设为 `FUND_BENCHMARK`，但必须保留函数位置。排名与同类平均类指标必须判断是否还需要分类口径参数。逐条维护 result，最大回撤等负值存储指标必须声明 `storageConvention: NEGATIVE_PERCENT`；无法确认则标记 `NEEDS_REVIEW`。

- [ ] **Step 3: 校验并抽查**

抽查 `f_risk_maxdownside`、`f_risk_maxdownside_date`、`f_risk_sharpe`、`f_risk_beta`、`f_risk_alpha`、`f_risk_trackerror`、`f_risk_inforatio`、`f_risk_maxdownsideranking`。

- [ ] **Step 4: 提交**

```bash
git add docs/temp/metrics.json scripts/data_sync/tests/test_metric_parameter_contracts.py
git commit -m "data(metrics): annotate risk parameter contracts"
```

### Task 5: 人工维护持仓、持有人和风格族 35 个指标

**Files:**
- Modify: `docs/temp/metrics.json`
- Test: `scripts/data_sync/tests/test_metric_parameter_contracts.py`

**Interfaces:**
- Produces: `prt(29) + holder(3) + style(3)` 共 35 条契约。

- [ ] **Step 1: 增加三个族 35/35 覆盖测试并确认失败**

- [ ] **Step 2: 逐条人工编辑报告期、排名和分类枚举参数**

持仓指标通常包含 `windCode,reportDate`；第 N 大持仓增加 `rank`；行业、国家地区、信用评级等维度增加明确枚举参数。所有枚举必须使用整数 value；缺少权威编码时保留推断映射并标记 `NEEDS_REVIEW`，同时在 `contract_notes` 写明假设。金额、比例、数量、名称等结果必须分别维护正确精度、符号单位和运算符。

- [ ] **Step 3: 校验重点枚举与顺序**

至少验证以下签名：

```text
f_prt_topstockname(windCode,reportDate,rank)
f_prt_topstockcode(windCode,reportDate,rank)
f_prt_bondbycreditratingtonav(windCode,reportDate,ratingTerm,creditRating)
```

并抽查行业、国家地区、持有人类型和风格指标。

- [ ] **Step 4: 运行全目录覆盖验收**

Run: `python scripts/data_sync/validate_metric_parameter_contracts.py docs/temp/metrics.json --require-complete`

Expected: total=169、valid=169、missing=0、errors=0；同时输出各 contract_status 数量，不要求全部为 REVIEWED。

- [ ] **Step 5: 提交**

```bash
git add docs/temp/metrics.json scripts/data_sync/tests/test_metric_parameter_contracts.py
git commit -m "data(metrics): annotate portfolio parameter contracts"
```

### Task 6: 增加 PostgreSQL 契约迁移

**Files:**
- Modify: `docker/postgres/initdb/03_rag_schema.sql`
- Create: `docker/postgres/migrations/20260929_add_metric_parameter_contracts.sql`
- Create: `scripts/data_sync/tests/test_metric_contract_migration.py`

**Interfaces:**
- Produces: `rag_fund_metric.parameters/result_contract/parameter_schema_version/contract_status/contract_updated_at`。

- [ ] **Step 1: 写迁移文本测试并确认失败**

测试要求 initdb 与 migration 都包含五列和三个 CHECK；`parameters` 必须为 array，`result_contract` 必须为 object；migration 使用 `ADD COLUMN IF NOT EXISTS`，约束通过 `pg_constraint` 条件块幂等创建。

- [ ] **Step 2: 更新 initdb 并创建幂等迁移**

迁移不能删除表、重建 embedding 或清空现有数据。

- [ ] **Step 3: 在 Docker PostgreSQL 连续执行迁移两次**

Run: `docker exec -i docker_postgres_16 psql -U postgres -d financial_copilot < docker/postgres/migrations/20260929_add_metric_parameter_contracts.sql`

重复运行同一命令，Expected: 两次均成功。

- [ ] **Step 4: 验证列、默认值和约束**

Run: `docker exec docker_postgres_16 psql -U postgres -d financial_copilot -c "SELECT column_name,data_type,is_nullable FROM information_schema.columns WHERE table_name='rag_fund_metric' AND column_name IN ('parameters','result_contract','parameter_schema_version','contract_status','contract_updated_at') ORDER BY column_name;"`

- [ ] **Step 5: 提交**

```bash
git add docker/postgres/initdb/03_rag_schema.sql docker/postgres/migrations/20260929_add_metric_parameter_contracts.sql scripts/data_sync/tests/test_metric_contract_migration.py
git commit -m "feat(postgres): add metric parameter contract metadata"
```

### Task 7: 实现 JSON 到 PostgreSQL 的单向同步和一致性校验

**Files:**
- Create: `scripts/data_sync/sync_metric_contracts.py`
- Create: `scripts/data_sync/tests/test_sync_metric_contracts.py`
- Modify: `scripts/data_sync/requirements.txt`

**Interfaces:**
- Produces: `load_contracts(path: Path) -> list[MetricContract]`
- Produces: `sync_contracts(connection, contracts, dry_run: bool) -> SyncReport`
- Produces: `verify_contracts(connection, contracts) -> list[str]`

- [ ] **Step 1: 写失败测试**

测试 dry-run 不写库；正式同步仅更新五个契约列，其中完整 `result` 写入 `result_contract`；未知 mnemonic 失败并回滚；169 条在单事务内更新；第二次执行显示 unchanged=169；任何 SQL 都不得更新 `embedding`。

- [ ] **Step 2: 实现同步器**

CLI：

```text
python scripts/data_sync/sync_metric_contracts.py \
  --catalog docs/temp/metrics.json \
  --database-url postgresql://postgres:123456@127.0.0.1:15432/financial_copilot \
  --dry-run
```

正式写入必须额外传 `--apply`，且 `--dry-run` 与 `--apply` 互斥。输出 JSON 报告：total、updated、unchanged、missing_in_db、invalid、rolled_back。

- [ ] **Step 3: 运行单元测试和 dry-run**

Run: `python -m unittest scripts.data_sync.tests.test_sync_metric_contracts -v`

Run: `python scripts/data_sync/sync_metric_contracts.py --catalog docs/temp/metrics.json --database-url postgresql://postgres:123456@127.0.0.1:15432/financial_copilot --dry-run`

Expected: total=169、invalid=0、missing_in_db=0。

- [ ] **Step 4: 应用并验证逐条一致**

Run: `python scripts/data_sync/sync_metric_contracts.py --catalog docs/temp/metrics.json --database-url postgresql://postgres:123456@127.0.0.1:15432/financial_copilot --apply`

Run: `python scripts/data_sync/sync_metric_contracts.py --catalog docs/temp/metrics.json --database-url postgresql://postgres:123456@127.0.0.1:15432/financial_copilot --verify-only`

Expected: 169/169 一致，embedding 的非空数量与同步前相同。

- [ ] **Step 5: 提交**

```bash
git add scripts/data_sync/sync_metric_contracts.py scripts/data_sync/tests/test_sync_metric_contracts.py scripts/data_sync/requirements.txt
git commit -m "feat(metrics): sync parameter contracts to postgres"
```

### Task 8: 将契约集成到基金筛选服务（在目录提交进入目标分支后执行）

**Files:**
- Modify: `fund-screening-service/app/models/parameters.py`
- Modify: `fund-screening-service/app/models/candidate.py`
- Modify: `fund-screening-service/app/retrieval/pgvector.py`
- Modify: `fund-screening-service/app/models/semantic.py`
- Modify: `fund-screening-service/app/retrieval/llm.py`
- Modify: `fund-screening-service/app/pipeline/validator.py`
- Create: `fund-screening-service/app/pipeline/function_serializer.py`
- Test: `fund-screening-service/tests/unit/test_metric_function_serializer.py`
- Test: `fund-screening-service/tests/integration/test_metric_parameter_completion.py`

**Interfaces:**
- Consumes: PostgreSQL `parameters` 与 `result_contract` JSONB。
- Produces: `serialize_metric_call(metric: str, contract: MetricContract, values: dict[str, Any]) -> str`。

- [ ] **Step 1: 在目标分支确认 Tasks 1–7 已集成**

运行目录校验与数据库 verify-only；任一失败则停止，不允许复制另一 worktree 的未提交文件。

- [ ] **Step 2: 写 serializer 失败测试**

固定示例：

```python
assert serialize_metric_call(
    "f_prt_bondbycreditratingtonav",
    contract,
    {"creditRating": 1, "windCode": "000001.OF", "ratingTerm": 1, "reportDate": "20260630"},
) == "f_prt_bondbycreditratingtonav(000001.OF,20260630,1,1)"
```

输入 dict 故意打乱顺序，以证明输出只服从 `position`。另测缺必填、未知 key、非法枚举、错误日期格式和 start/end 逆序均失败。

- [ ] **Step 3: 扩展 PGVector 召回契约**

查询必须返回五个新增列，CandidateContract 保存有序参数定义和完整结果契约，不得从指标前缀或结果类型再次推导。

- [ ] **Step 4: 限制 LLM structured output**

Prompt 只提供候选指标、参数契约和结果契约；LLM 返回 `metric`、命名参数值、比较符和比较值。禁止让 LLM 输出最终位置数组或函数字符串。ENUM prompt 同时提供 label、aliases 和整数 value，LLM 输出整数 value；比较符必须来自 result.supportedOperators，比较值必须符合 result.valueType 和 unit。

- [ ] **Step 5: 实现确定性补全、校验与序列化**

机械默认值和单位换算产生 repair event；缺少不可推导的必填参数、非法结果运算符、错误结果类型或未知单位进入 clarification；只有通过校验才调用 serializer。最大回撤转换读取 storageConvention，删除按 mnemonic 判断的硬编码。

- [ ] **Step 6: 运行完整业务测试**

Run: `python -m pytest fund-screening-service/tests/unit/test_metric_function_serializer.py -v`

Run: `python -m pytest fund-screening-service/tests/integration/test_metric_parameter_completion.py -v`

Run: `python -m pytest fund-screening-service -q`

Expected: 全部通过；真实 LLM 用例至少覆盖报告期、区间、基准、排名和枚举五类参数。

- [ ] **Step 7: 提交**

```bash
git add fund-screening-service
git commit -m "feat(screening): compile ordered metric function parameters"
```

### Task 9: 最终数据与业务验收

**Files:**
- Create: `docs/temp/metric-parameter-contract-coverage.json`
- Modify: `fund-screening-service/README.md`

**Interfaces:**
- Produces: 机器可读覆盖报告及运维命令。

- [ ] **Step 1: 生成只读覆盖报告**

报告包含 total=169、按族数量、按参数和结果 valueType 数量、按符号单位数量、按 supportedOperators 组合数量、precision/displayScale 覆盖、INFERRED/REVIEWED/NEEDS_REVIEW 数量、输入/结果枚举清单、默认值和特殊 storageConvention 清单以及零校验错误。报告只能读取目录，不能修改契约。

- [ ] **Step 2: 执行数据库一致性、完整测试和真实业务样例**

至少验证：固定窗口收益、任意区间收益、最大回撤、Beta 基准、重仓股 rank、信用评级双枚举、报告期、最新交易日默认值、多轮补参和非法枚举澄清。

- [ ] **Step 3: 更新 README**

记录迁移、dry-run、apply、verify-only、契约审核状态以及函数序列化规则。

- [ ] **Step 4: 提交**

```bash
git add docs/temp/metric-parameter-contract-coverage.json fund-screening-service/README.md
git commit -m "docs(metrics): document parameter contract operations"
```

## Agy Stage Dispatch Order

按以下顺序逐阶段派发，禁止一次把整个计划交给 Agy：

1. Stage 1 = Task 1：契约 schema 与校验器。
2. Stage 2 = Task 2：info 族人工契约。
3. Stage 3 = Task 3：收益、行情和事件族人工契约。
4. Stage 4 = Task 4：risk 族人工契约。
5. Stage 5 = Task 5：持仓、持有人和风格族人工契约。
6. Stage 6 = Tasks 6–7：数据库迁移与同步器。
7. Stage 7 = Task 8：基金筛选服务集成；只在目录提交已进入目标分支后执行。
8. Stage 8 = Task 9：端到端验收与文档。

每个阶段返回：`status`、`changes`、`verification`、`blockers`、`next-stage recommendation`。若出现未知枚举编码或无法判断的函数签名，返回 `needs-review`，不得擅自假设为已验证。
