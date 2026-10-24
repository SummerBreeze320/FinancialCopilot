from datetime import date
from app.api.models import (
    CognitiveLayer,
    FilterParams,
    RetrieveRequest,
    RetrieveResponse,
    ChunkDTO,
    DiagnosticsDTO,
    DocumentUploadResponse,
    DocumentStatusResponse,
)


def test_cognitive_layer_enum():
    assert CognitiveLayer.AXIOM == "AXIOM"
    assert CognitiveLayer.GROUND_TRUTH == "GROUND_TRUTH"
    assert CognitiveLayer.OPINION == "OPINION"
    assert CognitiveLayer.SIGNAL == "SIGNAL"


def test_filter_params_defaults():
    filters = FilterParams(
        stock_codes=["300750"],
        cognitive_layers=[CognitiveLayer.GROUND_TRUTH],
        category_paths=["GROUND_TRUTH.PERIODIC.STOCK_ANNUAL"],
    )
    assert "300750" in filters.stock_codes
    assert filters.cognitive_layers == [CognitiveLayer.GROUND_TRUTH]


def test_retrieve_request_validation():
    req = RetrieveRequest(query="宁德时代出货量", top_k=5, score_threshold=0.4)
    assert req.query == "宁德时代出货量"
    assert req.top_k == 5
    assert req.rerank_strategy == "neural"
    assert req.explain is False


def test_chunk_dto_serialization():
    chunk = ChunkDTO(
        chunk_id="chunk_01",
        document_id="doc_01",
        document_title="宁德时代研报",
        cognitive_layer=CognitiveLayer.GROUND_TRUTH,
        category_path="GROUND_TRUTH.PERIODIC.STOCK_ANNUAL",
        institution="中金公司",
        publish_date=date(2024, 10, 20),
        page_number=3,
        section_title="盈利拆解",
        content="2024Q3动力电池出货260GWh...",
        score=0.91,
        recall_sources=["VECTOR", "FULLTEXT"],
        has_table=True,
        metadata={"stock_codes": ["300750"]}
    )
    assert chunk.has_table is True
    assert chunk.cognitive_layer == "GROUND_TRUTH"
    assert "300750" in chunk.metadata["stock_codes"]


def test_retrieve_response_with_diagnostics():
    diag = DiagnosticsDTO(
        latency_breakdown_ms={"total_latency": 35.5},
        recall_funnel={"union_candidates": 42},
        warnings=["[Warning: Test]"]
    )
    resp = RetrieveResponse(
        query="测试",
        total_candidates=42,
        chunks=[],
        diagnostics=diag
    )
    assert resp.total_candidates == 42
    assert resp.diagnostics.recall_funnel["union_candidates"] == 42
