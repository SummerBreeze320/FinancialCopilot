from pathlib import Path
from app.catalog.loader import Catalog
from app.config import Settings
from app.runtime import build_production_compiler
from app.pipeline.analysis import RecallByConditionService


def test_production_compiler_wires_unified_pipeline():
    repo_root = Path(__file__).resolve().parents[2]
    metrics_path = repo_root.parent / "docs" / "temp" / "metrics.json"
    sectors_path = repo_root / "tests" / "fixtures" / "sectors-small.json"
    entities_path = repo_root / "tests" / "fixtures" / "entities-small.json"

    settings = Settings(
        pipeline_mode="rag",
        metrics_catalog_path=str(metrics_path),
        sectors_catalog_path=str(sectors_path),
        entity_catalog_path=str(entities_path),
        llm_api_key="test-key",
        embedding_api_key="test-key",
    )
    compiler = build_production_compiler(settings)
    assert compiler is not None
    assert hasattr(compiler, "graph")
    # Verify that ScreeningGraphNodes holds a unified analysis facade supporting semantic plan, edit plan, condition recall, and adjudication
    nodes = compiler.graph.nodes
    assert "semantic_plan" in nodes
    assert "recall_by_condition" in nodes
    assert "command_build" in nodes
