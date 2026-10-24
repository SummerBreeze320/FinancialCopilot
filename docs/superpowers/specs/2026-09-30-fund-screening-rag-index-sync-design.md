# 基金筛选 RAG 索引同步设计

## 目标

将 `docs/temp/metrics.json` 与 `docs/temp/sectors.json` 作为审核后的目录来源，幂等同步到本机 PostgreSQL 的 `rag_fund_metric`、`rag_fund_sector`，用本机 Ollama `qwen3-embedding:0.6b` 生成 1024 维向量，并在同步后给出可审计校验报告。

## 范围和安全边界

- 本次允许填充两张当前为空的 RAG 表；不清空、删除或修改其他表。
- 同步以 `mnemonic` 和 `source_sector_id`/`sector_id` 为 upsert 键；重复执行应产生相同结果。
- 指标的参数契约、result contract、别名、支持用法和 contract status 从目录原样映射，不由脚本推断或改写。
- 使用 `qwen3-embedding:0.6b`，每个写入向量必须为 1024 维；维度错误立即失败并不提交该批次。

## 同步流程

```text
读取 JSON 目录
  -> schema/必填字段预校验
  -> 生成 embedding_text
  -> 分批调用 http://127.0.0.1:11434/v1/embeddings
  -> PostgreSQL UPSERT
  -> 行数、非空向量、维度、固定 Top-K 查询校验
  -> JSON 同步报告
```

指标 embedding 文本由中文名、父类、description、aliases、支持用法、参数名及 result contract 的可读摘要组成。板块 embedding 文本由名称、别名、description、全路径、层级和节点类型组成。不得将用户数据、账户、对话或秘钥写入文本或报告。

## 组件

新增 `scripts/data_sync/sync_fund_screening_rag.py`：提供 CLI 参数 `--metrics`、`--sectors`、`--database-url`、`--embedding-base-url`、`--embedding-model`、`--batch-size`、`--report` 和 `--dry-run`。使用现有 HTTP embedding 协议和 PostgreSQL SQL，不依赖服务应用启动。

新增单元测试覆盖目录映射、embedding 文本、1024 维拒绝、幂等 SQL 参数；新增可选 integration test，在明确环境变量启用时对本机数据库执行同步与 Top-K 验证。

## 验收

1. 空表同步后指标数与启用目录指标数相等，板块数与启用板块数相等。
2. 两表 `count(embedding) = count(*)`，且 `vector_dims(embedding) = 1024`。
3. 第二次同步不增加行数。
4. 固定指标与板块问句能从各自表返回预期 Top-K 候选。
5. 输出报告记录来源文件 hash、模型、批次统计、插入/更新数、校验结果和耗时。
