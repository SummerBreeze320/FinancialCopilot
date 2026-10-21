# fund-screening-service/tests/unit/test_rag_sync_embedding.py
import json
import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock
import sys

repo_root = Path(__file__).resolve().parent.parent.parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

from scripts.data_sync.sync_fund_screening_rag import EmbeddingClient


def test_embedding_client_success_1024_dims():
    client = EmbeddingClient(base_url="http://127.0.0.1:11434", model_name="qwen3-embedding:0.6b")
    mock_resp = {
        "data": [
            {"embedding": [0.1] * 1024},
            {"embedding": [0.2] * 1024},
        ]
    }
    with patch("urllib.request.urlopen") as mock_url:
        mock_ctx = MagicMock()
        mock_ctx.read.return_value = json.dumps(mock_resp).encode("utf-8")
        mock_ctx.__enter__.return_value = mock_ctx
        mock_url.return_value = mock_ctx

        vectors = client.embed_batch(["text 1", "text 2"])
        assert len(vectors) == 2
        assert len(vectors[0]) == 1024
        assert len(vectors[1]) == 1024


def test_embedding_client_rejects_non_1024_dims():
    client = EmbeddingClient(base_url="http://127.0.0.1:11434", model_name="qwen3-embedding:0.6b")
    mock_resp = {
        "data": [
            {"embedding": [0.1] * 768},  # wrong dims
        ]
    }
    with patch("urllib.request.urlopen") as mock_url:
        mock_ctx = MagicMock()
        mock_ctx.read.return_value = json.dumps(mock_resp).encode("utf-8")
        mock_ctx.__enter__.return_value = mock_ctx
        mock_url.return_value = mock_ctx

        with pytest.raises(ValueError, match="Expected 1024 dimensions"):
            client.embed_batch(["text 1"])
