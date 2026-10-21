#!/usr/bin/env python3
"""Sync fund screening metrics and sectors catalog to PostgreSQL pgvector tables."""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Optional, Union

import psycopg2
from psycopg2.extras import Json

logger = logging.getLogger("rag_sync")


@dataclass
class MetricRecord:
    mnemonic: str
    index_name: str
    parent_name: str
    description: str
    embedding_text: str
    source_indicator_id: Optional[int]
    supported_usage: list[str]
    applicable_products: str
    aliases: list[str]
    version: int
    enabled: bool
    parameters: list[dict[str, Any]]
    result_contract: dict[str, Any]
    parameter_schema_version: int
    contract_status: str


@dataclass
class SectorRecord:
    sector_id: str
    parent_id: Optional[str]
    name: str
    name_en: str
    aliases: list[str]
    description: str
    embedding_text: str
    is_leaf: bool
    element_type: int
    tree_level: int
    full_path_names: str
    enabled: bool


def generate_metric_embedding_text(item: dict[str, Any]) -> str:
    seen: set[str] = set()
    parts: list[str] = []

    def _add_token(token: Any) -> None:
        if not token:
            return
        token_str = str(token).strip()
        if not token_str:
            return
        for w in token_str.split():
            clean_w = w.strip()
            if clean_w and clean_w.lower() not in seen:
                seen.add(clean_w.lower())
                parts.append(clean_w)

    # 1. 优先加入已有定制 embedding_text
    existing_text = item.get("embedding_text", "")
    if existing_text:
        _add_token(existing_text)

    # 2. 指标名称与助记符
    index_name = item.get("index_name") or item.get("name", "")
    _add_token(index_name)

    mnemonic = item.get("mnemonic", "")
    _add_token(mnemonic)

    # 3. 分类与描述
    parent_name = item.get("parent_name", "")
    _add_token(parent_name)

    desc = item.get("description", "")
    if desc and desc != index_name:
        _add_token(desc)

    # 4. 别名
    aliases = item.get("aliases", []) or []
    for a in aliases:
        _add_token(a)

    # 5. 具有业务语义的单位 (如 元, %, 亿, 年 等)
    result = item.get("result") or {}
    unit = result.get("unit", "")
    if unit and unit not in ("NUMBER", ""):
        _add_token(unit)

    return " ".join(parts).strip()


def generate_sector_embedding_text(item: dict[str, Any], full_path: str, tree_level: int) -> str:
    parts = []
    name = item.get("name", "")
    if name:
        parts.append(str(name))
    aliases = item.get("aliases", [])
    if aliases:
        parts.extend(str(a) for a in aliases if a)
    desc = item.get("description", "")
    if desc:
        parts.append(str(desc))
    if full_path:
        parts.append(f"路径:{full_path}")
    parts.append(f"层级:{tree_level}")
    is_leaf = item.get("is_leaf", True)
    parts.append("叶子节点" if is_leaf else "分类节点")

    return " ".join(parts).strip()


def load_metrics_catalog(path: Union[str, Path]) -> list[MetricRecord]:
    p = Path(path)
    if not p.exists():
        raise FileNotFoundError(f"Metrics catalog file not found: {p}")

    with p.open("r", encoding="utf-8") as f:
        data = json.load(f)

    if not isinstance(data, list):
        raise ValueError(f"Metrics catalog must be a JSON array, got {type(data).__name__}")

    records: list[MetricRecord] = []
    for item in data:
        if not item.get("enabled", True):
            continue

        mnemonic = item.get("mnemonic")
        if not mnemonic:
            continue

        index_name = item.get("index_name") or item.get("name") or mnemonic
        parent_name = item.get("parent_name", "")
        desc = item.get("description", "")
        aliases = item.get("aliases", [])
        supported_usage = item.get("supported_usage", [])
        applicable_products = item.get("applicable_products", "")
        source_indicator_id = item.get("source_indicator_id")
        version = item.get("version", 1)
        enabled = bool(item.get("enabled", True))
        parameters = item.get("parameters", [])
        result_contract = item.get("result") or {
            "valueType": item.get("result_value_type", "NUMBER"),
            "supportedOperators": [">", ">=", "<", "<=", "=", "!="],
        }
        parameter_schema_version = item.get("parameter_schema_version", 1)
        contract_status = item.get("contract_status", "NEEDS_REVIEW")

        embedding_text = generate_metric_embedding_text(item)

        records.append(
            MetricRecord(
                mnemonic=mnemonic,
                index_name=index_name,
                parent_name=parent_name,
                description=desc,
                embedding_text=embedding_text,
                source_indicator_id=source_indicator_id,
                supported_usage=supported_usage,
                applicable_products=applicable_products,
                aliases=aliases,
                version=version,
                enabled=enabled,
                parameters=parameters,
                result_contract=result_contract,
                parameter_schema_version=parameter_schema_version,
                contract_status=contract_status,
            )
        )

    return records


def load_sectors_catalog(path: Union[str, Path]) -> list[SectorRecord]:
    p = Path(path)
    if not p.exists():
        raise FileNotFoundError(f"Sectors catalog file not found: {p}")

    with p.open("r", encoding="utf-8") as f:
        data = json.load(f)

    if not isinstance(data, list):
        raise ValueError(f"Sectors catalog must be a JSON array, got {type(data).__name__}")

    # Index by source_sector_id
    enabled_items: list[dict[str, Any]] = [item for item in data if item.get("enabled", True)]
    by_id = {item.get("source_sector_id"): item for item in enabled_items if item.get("source_sector_id")}

    # Memoized path and level resolution
    levels: dict[str, int] = {}
    paths: dict[str, str] = {}

    def resolve_hierarchy(sid: str, visited: Optional[set[str]] = None) -> tuple[int, str]:
        if sid in levels and sid in paths:
            return levels[sid], paths[sid]

        if visited is None:
            visited = set()
        if sid in visited:
            # Cycle detected, break at level 0
            return 0, by_id[sid].get("name", sid)

        visited.add(sid)
        item = by_id[sid]
        pid = item.get("parent_id")
        cur_name = item.get("name", sid)

        if not pid or pid not in by_id:
            levels[sid] = 0
            paths[sid] = cur_name
            return 0, cur_name

        p_level, p_path = resolve_hierarchy(pid, visited)
        levels[sid] = p_level + 1
        paths[sid] = f"{p_path} > {cur_name}"
        return levels[sid], paths[sid]

    records: list[SectorRecord] = []
    for item in enabled_items:
        sid = item.get("source_sector_id")
        if not sid:
            continue

        tree_level, full_path = resolve_hierarchy(sid)
        embedding_text = generate_sector_embedding_text(item, full_path, tree_level)

        records.append(
            SectorRecord(
                sector_id=sid,
                parent_id=item.get("parent_id"),
                name=item.get("name", sid),
                name_en=item.get("name_en", ""),
                aliases=item.get("aliases", []),
                description=item.get("description", ""),
                embedding_text=embedding_text,
                is_leaf=bool(item.get("is_leaf", True)),
                element_type=int(item.get("element_type", 6)),
                tree_level=tree_level,
                full_path_names=full_path,
                enabled=bool(item.get("enabled", True)),
            )
        )

    return records


class EmbeddingClient:
    """Client for generating 1024-dim embeddings via Ollama OpenAI-compatible /v1/embeddings endpoint."""

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:11434",
        model_name: str = "qwen3-embedding:0.6b",
        target_dims: int = 1024,
        timeout: float = 60.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.model_name = model_name
        self.target_dims = target_dims
        self.timeout = timeout

    def _get_endpoint(self) -> str:
        if self.base_url.endswith("/v1"):
            return f"{self.base_url}/embeddings"
        return f"{self.base_url}/v1/embeddings"

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []

        payload = {
            "model": self.model_name,
            "input": texts,
        }
        data_bytes = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            self._get_endpoint(),
            data=data_bytes,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )

        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                resp_data = json.loads(resp.read().decode("utf-8"))
        except urllib.error.URLError as e:
            raise RuntimeError(f"Embedding request failed to {self._get_endpoint()}: {e}") from e

        data_items = resp_data.get("data", [])
        if len(data_items) != len(texts):
            raise ValueError(f"Expected {len(texts)} embeddings in response, got {len(data_items)}")

        # Sort by index if present
        data_items_sorted = sorted(data_items, key=lambda x: x.get("index", 0))

        vectors: list[list[float]] = []
        for i, item in enumerate(data_items_sorted):
            vec = item.get("embedding", [])
            if len(vec) != self.target_dims:
                raise ValueError(
                    f"Expected {self.target_dims} dimensions, got {len(vec)} for text index {i}"
                )
            vectors.append(vec)

        return vectors

    def embed_single(self, text: str) -> list[float]:
        return self.embed_batch([text])[0]


def format_vector_str(vec: list[float]) -> str:
    return "[" + ",".join(str(x) for x in vec) + "]"


def sync_metrics_to_db(
    connection: Any,
    records: list[MetricRecord],
    vectors: list[list[float]],
    dry_run: bool = False,
) -> tuple[int, int]:
    if not records:
        return 0, 0
    if len(records) != len(vectors):
        raise ValueError(f"Record count ({len(records)}) != vector count ({len(vectors)})")

    with connection.cursor() as cursor:
        mnemonics = tuple(r.mnemonic for r in records)
        cursor.execute("SELECT mnemonic FROM rag_fund_metric WHERE mnemonic IN %s;", (mnemonics,))
        existing_rows = cursor.fetchall()
        existing_keys = set(row[0] for row in existing_rows)

        inserted = sum(1 for r in records if r.mnemonic not in existing_keys)
        updated = sum(1 for r in records if r.mnemonic in existing_keys)

        if dry_run:
            return inserted, updated

        sql = """
            INSERT INTO rag_fund_metric (
                mnemonic, index_name, parent_name, description, embedding_text,
                embedding, source_indicator_id, supported_usage, applicable_products, aliases,
                version, enabled, parameters, result_contract, parameter_schema_version,
                contract_status
            ) VALUES (
                %s, %s, %s, %s, %s,
                %s::vector, %s, %s, %s, %s,
                %s, %s, %s::jsonb, %s::jsonb, %s,
                %s
            )
            ON CONFLICT (mnemonic) DO UPDATE SET
                index_name = EXCLUDED.index_name,
                parent_name = EXCLUDED.parent_name,
                description = EXCLUDED.description,
                embedding_text = EXCLUDED.embedding_text,
                embedding = EXCLUDED.embedding,
                source_indicator_id = EXCLUDED.source_indicator_id,
                supported_usage = EXCLUDED.supported_usage,
                applicable_products = EXCLUDED.applicable_products,
                aliases = EXCLUDED.aliases,
                version = EXCLUDED.version,
                enabled = EXCLUDED.enabled,
                parameters = EXCLUDED.parameters,
                result_contract = EXCLUDED.result_contract,
                parameter_schema_version = EXCLUDED.parameter_schema_version,
                contract_status = EXCLUDED.contract_status,
                contract_updated_at = CURRENT_TIMESTAMP;
        """
        params_list = []
        for r, vec in zip(records, vectors):
            params_list.append((
                r.mnemonic,
                r.index_name,
                r.parent_name,
                r.description,
                r.embedding_text,
                format_vector_str(vec),
                r.source_indicator_id,
                r.supported_usage,
                r.applicable_products,
                r.aliases,
                r.version,
                r.enabled,
                json.dumps(r.parameters, ensure_ascii=False),
                json.dumps(r.result_contract, ensure_ascii=False),
                r.parameter_schema_version,
                r.contract_status,
            ))

        cursor.executemany(sql, params_list)

    return inserted, updated


def sync_sectors_to_db(
    connection: Any,
    records: list[SectorRecord],
    vectors: list[list[float]],
    dry_run: bool = False,
) -> tuple[int, int]:
    if not records:
        return 0, 0
    if len(records) != len(vectors):
        raise ValueError(f"Sector record count ({len(records)}) != vector count ({len(vectors)})")

    with connection.cursor() as cursor:
        sector_ids = tuple(r.sector_id for r in records)
        cursor.execute("SELECT sector_id FROM rag_fund_sector WHERE sector_id IN %s;", (sector_ids,))
        existing_rows = cursor.fetchall()
        existing_keys = set(row[0] for row in existing_rows)

        inserted = sum(1 for r in records if r.sector_id not in existing_keys)
        updated = sum(1 for r in records if r.sector_id in existing_keys)

        if dry_run:
            return inserted, updated

        sql = """
            INSERT INTO rag_fund_sector (
                sector_id, parent_id, name, name_en, aliases,
                description, embedding_text, embedding, is_leaf, element_type,
                tree_level, full_path_names, enabled
            ) VALUES (
                %s, %s, %s, %s, %s,
                %s, %s, %s::vector, %s, %s,
                %s, %s, %s
            )
            ON CONFLICT (sector_id) DO UPDATE SET
                parent_id = EXCLUDED.parent_id,
                name = EXCLUDED.name,
                name_en = EXCLUDED.name_en,
                aliases = EXCLUDED.aliases,
                description = EXCLUDED.description,
                embedding_text = EXCLUDED.embedding_text,
                embedding = EXCLUDED.embedding,
                is_leaf = EXCLUDED.is_leaf,
                element_type = EXCLUDED.element_type,
                tree_level = EXCLUDED.tree_level,
                full_path_names = EXCLUDED.full_path_names,
                enabled = EXCLUDED.enabled;
        """
        params_list = []
        for r, vec in zip(records, vectors):
            params_list.append((
                r.sector_id,
                r.parent_id,
                r.name,
                r.name_en,
                r.aliases,
                r.description,
                r.embedding_text,
                format_vector_str(vec),
                r.is_leaf,
                r.element_type,
                r.tree_level,
                r.full_path_names,
                r.enabled,
            ))

        cursor.executemany(sql, params_list)

    return inserted, updated


def compute_file_sha256(path: Union[str, Path]) -> str:
    p = Path(path)
    h = hashlib.sha256()
    with p.open("rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def verify_sync_integrity(
    connection: Any,
    embedding_client: Optional[EmbeddingClient] = None,
    test_top_k: bool = True,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "passed": False,
        "metrics_count": 0,
        "metrics_embedding_count": 0,
        "metrics_vector_dims": [],
        "sectors_count": 0,
        "sectors_embedding_count": 0,
        "sectors_vector_dims": [],
        "top_k_queries": [],
    }

    with connection.cursor() as cursor:
        cursor.execute("SELECT COUNT(*), COUNT(embedding) FROM rag_fund_metric WHERE enabled = TRUE;")
        row = cursor.fetchone()
        result["metrics_count"] = row[0]
        result["metrics_embedding_count"] = row[1]

        cursor.execute("SELECT DISTINCT vector_dims(embedding) FROM rag_fund_metric WHERE embedding IS NOT NULL;")
        result["metrics_vector_dims"] = [r[0] for r in cursor.fetchall()]

        cursor.execute("SELECT COUNT(*), COUNT(embedding) FROM rag_fund_sector WHERE enabled = TRUE;")
        row = cursor.fetchone()
        result["sectors_count"] = row[0]
        result["sectors_embedding_count"] = row[1]

        cursor.execute("SELECT DISTINCT vector_dims(embedding) FROM rag_fund_sector WHERE embedding IS NOT NULL;")
        result["sectors_vector_dims"] = [r[0] for r in cursor.fetchall()]

        top_k_ok = True
        if test_top_k and embedding_client and result["metrics_count"] > 0 and result["sectors_count"] > 0:
            # Test metric query
            try:
                m_vec = embedding_client.embed_single("近1年收益率")
                cursor.execute(
                    "SELECT mnemonic, index_name FROM rag_fund_metric ORDER BY embedding <=> %s::vector LIMIT 3;",
                    (format_vector_str(m_vec),),
                )
                m_hits = cursor.fetchall()
                m_pass = any("近1年" in h[1] or "f_return_1y" in h[0] for h in m_hits)
                result["top_k_queries"].append({
                    "query": "近1年收益率",
                    "domain": "metric",
                    "top_hits": [f"{h[0]}:{h[1]}" for h in m_hits],
                    "matched": m_pass,
                })
                if not m_pass:
                    top_k_ok = False
            except Exception as e:
                result["top_k_queries"].append({"query": "近1年收益率", "error": str(e), "matched": False})
                top_k_ok = False

            # Test sector query
            try:
                s_vec = embedding_client.embed_single("开放式基金")
                cursor.execute(
                    "SELECT sector_id, name, full_path_names FROM rag_fund_sector ORDER BY embedding <=> %s::vector LIMIT 3;",
                    (format_vector_str(s_vec),),
                )
                s_hits = cursor.fetchall()
                s_pass = any("开放式基金" in h[1] or "开放式基金" in (h[2] or "") for h in s_hits)
                result["top_k_queries"].append({
                    "query": "开放式基金",
                    "domain": "sector",
                    "top_hits": [f"{h[0]}:{h[1]}" for h in s_hits],
                    "matched": s_pass,
                })
                if not s_pass:
                    top_k_ok = False
            except Exception as e:
                result["top_k_queries"].append({"query": "开放式基金", "error": str(e), "matched": False})
                top_k_ok = False

    counts_match = (
        result["metrics_count"] > 0
        and result["metrics_count"] == result["metrics_embedding_count"]
        and result["metrics_vector_dims"] == [1024]
        and result["sectors_count"] > 0
        and result["sectors_count"] == result["sectors_embedding_count"]
        and result["sectors_vector_dims"] == [1024]
    )
    result["passed"] = bool(counts_match and top_k_ok)
    return result


def build_sync_report(
    metrics_file: str,
    sectors_file: str,
    metrics_hash: str,
    sectors_hash: str,
    model_name: str,
    batch_size: int,
    metrics_stats: dict[str, Any],
    sectors_stats: dict[str, Any],
    verification: dict[str, Any],
    elapsed_seconds: float,
    dry_run: bool = False,
) -> dict[str, Any]:
    return {
        "sync_timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "metrics_file": metrics_file,
        "sectors_file": sectors_file,
        "metrics_hash": metrics_hash,
        "sectors_hash": sectors_hash,
        "model_name": model_name,
        "embedding_dims": 1024,
        "batch_size": batch_size,
        "dry_run": dry_run,
        "metrics_stats": metrics_stats,
        "sectors_stats": sectors_stats,
        "verification": verification,
        "elapsed_seconds": round(elapsed_seconds, 2),
    }


def run_rag_sync(
    metrics_path: str = "docs/temp/metrics.json",
    sectors_path: str = "docs/temp/sectors.json",
    database_url: str = "postgresql://postgres:123456@127.0.0.1:15432/financial_copilot",
    embedding_base_url: str = "http://127.0.0.1:11434",
    embedding_model: str = "qwen3-embedding:0.6b",
    batch_size: int = 32,
    report_path: Optional[str] = "docs/temp/rag_sync_report.json",
    dry_run: bool = False,
) -> dict[str, Any]:
    start_time = time.time()
    logger.info("Starting RAG catalog sync...")

    metrics = load_metrics_catalog(metrics_path)
    logger.info(f"Loaded {len(metrics)} enabled metrics from {metrics_path}")

    sectors = load_sectors_catalog(sectors_path)
    logger.info(f"Loaded {len(sectors)} enabled sectors from {sectors_path}")

    metrics_hash = compute_file_sha256(metrics_path)
    sectors_hash = compute_file_sha256(sectors_path)

    client = EmbeddingClient(
        base_url=embedding_base_url,
        model_name=embedding_model,
        target_dims=1024,
    )

    logger.info("Generating metric embeddings in batches...")
    metric_vectors: list[list[float]] = []
    for i in range(0, len(metrics), batch_size):
        batch = [m.embedding_text for m in metrics[i:i + batch_size]]
        vecs = client.embed_batch(batch)
        metric_vectors.extend(vecs)
        logger.info(f"Metrics embedded: {len(metric_vectors)}/{len(metrics)}")

    logger.info("Generating sector embeddings in batches...")
    sector_vectors: list[list[float]] = []
    for i in range(0, len(sectors), batch_size):
        batch = [s.embedding_text for s in sectors[i:i + batch_size]]
        vecs = client.embed_batch(batch)
        sector_vectors.extend(vecs)
        logger.info(f"Sectors embedded: {len(sector_vectors)}/{len(sectors)}")

    logger.info(f"Connecting to database: {database_url} (dry_run={dry_run})")
    conn = psycopg2.connect(database_url)
    try:
        with conn:
            m_ins, m_upd = sync_metrics_to_db(conn, metrics, metric_vectors, dry_run=dry_run)
            s_ins, s_upd = sync_sectors_to_db(conn, sectors, sector_vectors, dry_run=dry_run)

        verification = verify_sync_integrity(conn, embedding_client=client, test_top_k=not dry_run)
    finally:
        conn.close()

    elapsed = time.time() - start_time
    report = build_sync_report(
        metrics_file=str(metrics_path),
        sectors_file=str(sectors_path),
        metrics_hash=metrics_hash,
        sectors_hash=sectors_hash,
        model_name=embedding_model,
        batch_size=batch_size,
        metrics_stats={
            "total_enabled": len(metrics),
            "inserted": m_ins,
            "updated": m_upd,
        },
        sectors_stats={
            "total_enabled": len(sectors),
            "inserted": s_ins,
            "updated": s_upd,
        },
        verification=verification,
        elapsed_seconds=elapsed,
        dry_run=dry_run,
    )

    if report_path:
        out_p = Path(report_path)
        out_p.parent.mkdir(parents=True, exist_ok=True)
        with out_p.open("w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        logger.info(f"Sync report written to {report_path}")

    return report


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    parser = argparse.ArgumentParser(description="Sync fund screening RAG catalogs to PostgreSQL.")
    parser.add_argument("--metrics", default="docs/temp/metrics.json", help="Path to metrics.json")
    parser.add_argument("--sectors", default="docs/temp/sectors.json", help="Path to sectors.json")
    parser.add_argument(
        "--database-url",
        default="postgresql://postgres:123456@127.0.0.1:15432/financial_copilot",
        help="PostgreSQL database URL",
    )
    parser.add_argument(
        "--embedding-base-url",
        default="http://127.0.0.1:11434",
        help="Ollama embedding base URL",
    )
    parser.add_argument(
        "--embedding-model",
        default="qwen3-embedding:0.6b",
        help="Ollama embedding model name",
    )
    parser.add_argument("--batch-size", type=int, default=32, help="Batch size for embeddings")
    parser.add_argument("--report", default="docs/temp/rag_sync_report.json", help="Output report JSON path")
    parser.add_argument("--dry-run", action="store_true", help="Perform dry run without committing database changes")

    args = parser.parse_args()

    report = run_rag_sync(
        metrics_path=args.metrics,
        sectors_path=args.sectors,
        database_url=args.database_url,
        embedding_base_url=args.embedding_base_url,
        embedding_model=args.embedding_model,
        batch_size=args.batch_size,
        report_path=args.report,
        dry_run=args.dry_run,
    )

    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not report.get("verification", {}).get("passed", False) and not args.dry_run:
        sys.exit(1)


if __name__ == "__main__":
    main()
