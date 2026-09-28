from pathlib import Path

import pytest

from app.catalog.loader import Catalog
from app.retrieval.lexical import LexicalEntityRetriever


@pytest.mark.asyncio
async def test_lexical_entity_search_prefers_exact_alias() -> None:
    catalog = Catalog.from_files(Path("tests/fixtures/metrics-small.json"), Path("tests/fixtures/sectors-small.json"), Path("tests/fixtures/entities-small.json"))

    result = await LexicalEntityRetriever(catalog.entities).search("张坤管理的基金", top_k=3)

    assert result[0].binding_role == "FUND_MANAGER"
