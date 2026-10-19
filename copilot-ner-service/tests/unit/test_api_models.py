import jsonschema
import pytest
from pydantic import ValidationError

from app.models.api import (
    AmbiguousMention,
    Category,
    RecognitionResponse,
    ResolvedMention,
)


def candidate(entity_id: str, category: str, rank: int, confidence: float) -> dict:
    return {
        "rank": rank,
        "entity": {
            "id": entity_id,
            "category": category,
            "canonical_value": entity_id.split(":", 1)[1],
            "metadata": {},
        },
        "match": {"method": "exact_alias", "confidence": confidence},
    }


def test_resolved_and_ambiguous_mentions_are_discriminated():
    resolved = ResolvedMention.model_validate({
        "mention": "华夏成长",
        "span": {"start": 0, "end": 4},
        "resolution": "resolved",
        "entity": {
            "id": "fund_name:000001.OF",
            "category": "fund_name",
            "canonical_value": "华夏成长",
            "metadata": {"fund_code": "000001.OF"},
        },
        "match": {"method": "exact_alias", "confidence": 0.99},
    })
    assert resolved.entity.category == Category.FUND_NAME

    ambiguous = AmbiguousMention.model_validate({
        "mention": "000001",
        "span": {"start": 0, "end": 6},
        "resolution": "ambiguous",
        "ambiguity": {
            "reason": "cross-category",
            "context_used": "000001",
            "context_needed": ["资产类型"],
        },
        "candidates": [
            candidate("fund_code:000001.OF", "fund_code", 1, 0.90),
            candidate("stock_code:000001.SZ", "stock_code", 2, 0.88),
        ],
    })
    assert len(ambiguous.candidates) == 2


@pytest.mark.parametrize("span", [{"start": 1, "end": 1}, {"start": 2, "end": 1}])
def test_span_requires_end_after_start(span):
    payload = {
        "mention": "x",
        "span": span,
        "resolution": "resolved",
        "entity": {"id": "stock_name:x", "category": "stock_name", "canonical_value": "x", "metadata": {}},
        "match": {"method": "exact_alias", "confidence": 0.9},
    }
    with pytest.raises(ValidationError):
        ResolvedMention.model_validate(payload)


def test_ambiguous_mention_requires_two_candidates():
    with pytest.raises(ValidationError):
        AmbiguousMention.model_validate({
            "mention": "x",
            "span": {"start": 0, "end": 1},
            "resolution": "ambiguous",
            "ambiguity": {"reason": "weak", "context_used": "x", "context_needed": []},
            "candidates": [candidate("stock_name:x", "stock_name", 1, 0.5)],
        })


def test_response_schema_is_valid_draft_2020_12():
    jsonschema.Draft202012Validator.check_schema(RecognitionResponse.model_json_schema())
