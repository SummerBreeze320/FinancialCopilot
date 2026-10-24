import pytest
from app.config import Settings

def test_settings_defaults():
    settings = Settings()
    assert settings.port == 8820
    assert settings.embedding_dim == 1024
    assert settings.llm_base_url == "http://127.0.0.1:9090/v1"
    assert settings.llm_model == "gemini-3.8-flash-low"
    assert settings.top_k_default == 5
    assert settings.candidate_pool_size == 30
    assert settings.reranker_type in ("neural", "rrf", "hybrid")
