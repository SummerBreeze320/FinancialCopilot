# Fund Metric Parameter Contracts Design

## Goal

为 `docs/temp/metrics.json` 中 169 个基金指标建立可执行的有序参数契约，并将契约同步到 PostgreSQL `rag_fund_metric`。基金筛选服务在召回指标后，必须基于该契约让 LLM 填充参数、执行确定性校验，并按位置生成数据引擎函数调用。

## Source of Truth

`docs/temp/metrics.json` 是参数契约唯一数据源。PostgreSQL 是可重建的运行时镜像，不允许从数据库反向覆盖 JSON 文件。

每个指标由人工逐条阅读 `index_name`、`description`、`embedding_text` 后维护。禁止使用按指标前缀批量生成参数的脚本。可以使用脚本进行格式校验、覆盖率统计和数据库同步，但不能用脚本推断或写入参数契约。

由于缺少原始 Wind 函数签名文档，推断契约必须标为 `INFERRED` 或 `NEEDS_REVIEW`；只有经过数据引擎文档或真实调用确认后才能标为 `REVIEWED`。

## Metric Contract

每条指标增加：

```json
{
  "parameters": [
    {
      "position": 1,
      "key": "windCode",
      "valueType": "STRING",
      "semanticType": "FUND_CODE",
      "required": true,
      "description": "基金 Wind 代码"
    }
  ],
  "result": {
    "valueType": "DECIMAL",
    "precision": {
      "totalDigits": 12,
      "scale": 6,
      "roundingMode": "HALF_UP"
    },
    "displayScale": 2,
    "unit": "%",
    "supportedOperators": [">", ">=", "<", "<=", "=", "!="]
  },
  "parameter_schema_version": 1,
  "contract_status": "INFERRED"
}
```

### Ordering

- `parameters` 必须是数组。
- `position` 从 1 开始连续递增，不得重复或跳号。
- 函数序列化只允许按 `position` 排序后输出参数值。
- JSON 数组顺序必须与 `position` 一致；校验器发现不一致时直接失败，不能静默重排源文件。

### Parameter Types

`valueType` 仅允许：`STRING`、`DATE`、`INTEGER`、`DECIMAL`、`BOOLEAN`、`ENUM`。

常用 `semanticType`：

- `FUND_CODE`：基金 Wind 代码，输出字符串，例如 `000001.OF`。
- `BENCHMARK_CODE`：指数或业绩比较基准代码。
- `TRADE_DATE`：交易日期，输出格式 `yyyyMMdd`。
- `REPORT_DATE`：报告期，输出格式 `yyyyMMdd`。
- `HOLDING_RANK`：持仓排名，整数。
- `CURRENCY_CODE`：币种代码。
- `FREQUENCY`：频率枚举。

日期参数必须声明 `format: "yyyyMMdd"`。`startDate` 和 `endDate` 必须互相声明 `pairWith`，并由校验器保证同时存在且 `startDate <= endDate`。

## Result Contract

每个指标必须根据自身描述人工维护 `result`，禁止从指标前缀或 `valueType` 批量套用默认结果契约。

```json
{
  "result": {
    "valueType": "DECIMAL",
    "precision": {
      "totalDigits": 12,
      "scale": 6,
      "roundingMode": "HALF_UP"
    },
    "displayScale": 2,
    "unit": "%",
    "supportedOperators": [">", ">=", "<", "<=", "=", "!="],
    "storageConvention": "NEGATIVE_PERCENT"
  }
}
```

- `valueType` 仅允许 `DECIMAL`、`INTEGER`、`STRING`、`DATE`、`BOOLEAN`、`ENUM`。
- `precision.totalDigits` 和 `precision.scale` 是数据引擎计算/存储精度；只允许出现在 `DECIMAL` 结果中。
- `displayScale` 是默认展示小数位数，不得替代计算精度。
- `unit` 直接保存中文或数学符号，不保存英文单位代码。百分比用 `%`，金额用 `元`、`万元`、`亿元`，时间量用 `天`、`月`、`年`，排名用 `名`；无单位使用 JSON `null`，不得使用 `NONE`。
- `supportedOperators` 必须逐指标维护，且第一版只能使用当前 DSL 支持的 `>`、`>=`、`<`、`<=`、`=`、`!=`。
- 数值和日期指标可按业务语义支持六种比较；字符串、布尔、枚举默认只允许 `=`、`!=`，但仍需逐指标确认。
- `ENUM` 结果必须提供整数 `options[].value`、中文 `label` 和可选 `aliases`。
- 特殊底层口径使用 `storageConvention` 描述，例如最大回撤为 `NEGATIVE_PERCENT`；validator 不得再按 mnemonic 硬编码。
- 如果用户输入单位与底层结果单位不同，`acceptedInputUnits` 显式列出符号和换算倍数，编译器统一换算后再生成 DSL。

示例：

```json
{
  "unit": "元",
  "acceptedInputUnits": [
    {"unit": "元", "multiplier": 1},
    {"unit": "万元", "multiplier": 10000},
    {"unit": "亿元", "multiplier": 100000000}
  ]
}
```

### Enumerations

枚举必须映射为数据引擎实际接收的整数值：

```json
{
  "position": 3,
  "key": "ratingTerm",
  "valueType": "ENUM",
  "required": true,
  "options": [
    {"value": 1, "label": "长期信用评级", "aliases": ["长期", "长期评级"]},
    {"value": 2, "label": "短期信用评级", "aliases": ["短期", "短期评级"]}
  ]
}
```

- `ENUM` 必须提供非空 `options`。
- `options[].value` 必须是整数且在同一参数内唯一。
- 无权威资料支持的枚举编码不得标记为 `REVIEWED`。
- 共享枚举在第一版仍内联存储，避免运行时二次查询；校验器负责检测同名枚举的映射漂移。

### Required and Defaults

- `required: true` 表示函数签名必须获得该位置的值。
- `defaultValue` 只允许用于能确定性求值的符号默认值，如 `LATEST_TRADE_DATE`、`LATEST_REPORT_DATE`、`FUND_BENCHMARK`。
- 必填参数可以有默认值；默认值表示编译器可机械补全，不代表该参数可从函数签名省略。
- 无法确定的必填参数必须触发澄清，不能传空字符串、`null` 或猜测值。

## PostgreSQL Model

`rag_fund_metric` 增加：

```sql
parameters JSONB NOT NULL DEFAULT '[]',
result_contract JSONB NOT NULL DEFAULT '{}',
parameter_schema_version INT NOT NULL DEFAULT 1,
contract_status VARCHAR(32) NOT NULL DEFAULT 'NEEDS_REVIEW',
contract_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
```

约束：

```sql
CHECK (jsonb_typeof(parameters) = 'array')
CHECK (jsonb_typeof(result_contract) = 'object')
CHECK (contract_status IN ('INFERRED', 'REVIEWED', 'NEEDS_REVIEW'))
```

不为 `parameters` 创建 GIN 索引。运行时按向量或 mnemonic 找到指标后整体读取契约，不按 JSON 内部字段反查指标。

现有 Docker volume 不会重新执行 initdb，因此除了更新 `03_rag_schema.sql`，还必须提供独立、幂等的迁移文件。

## Runtime Compilation

1. RAG 召回指标，同时加载完整参数契约。
2. LLM 只能在候选指标的契约范围内输出 `key -> value`，不能创造参数、改变位置、输出未知枚举或使用结果契约不支持的比较符。
3. 确定性校验器检查必填项、类型、日期格式、日期配对、枚举整数、未知参数、结果类型、单位和支持运算符。
4. 可机械补全的默认值由校验器填充并记录 repair event。
5. 缺少不可推导的必填项时返回澄清问题。
6. serializer 按 `position` 产生最终调用，例如：

```text
f_prt_bondbycreditratingtonav(000001.OF,20260630,1,1)
```

外层指标比较保持独立：

```json
{
  "metric": "f_prt_bondbycreditratingtonav",
  "params": ["000001.OF", "20260630", 1, 1],
  "operator": ">",
  "value": 10
}
```

## Acceptance Criteria

- 169/169 指标均包含非空或明确允许为空的有序参数契约、完整 result 契约、schema version 和审核状态。
- 每条 result 均逐指标人工填写类型、计算精度（适用时）、展示精度（适用时）、符号单位或 null、支持运算符；枚举结果包含整数映射。
- 每条指标必须人工审阅；提交历史按指标族分批，便于审核和回滚。
- 校验器拒绝错序、跳号、重复位置、未知类型、非法枚举和不成对日期。
- 数据库迁移可对已有 volume 重复执行。
- dry-run 同步报告新增、更新、未变化和失败数量；正式同步在一个事务内完成且不修改 embedding。
- JSON 与 PostgreSQL 169 条记录的契约内容逐条一致。
- LLM 输出缺参或非法枚举时不能形成最终函数调用。
- 非法结果运算符、错误结果值类型或未声明单位不能形成最终 DSL。
- 示例函数调用严格按位置输出，日期为 `yyyyMMdd`，枚举为整数。

## Non-goals

- 不重新生成 embedding。
- 不建设通用指标计算引擎。
- 不以指标前缀自动生成 169 条参数契约。
- 不把推断契约伪装成权威 Wind 接口定义。
