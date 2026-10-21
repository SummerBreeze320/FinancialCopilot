import pytest

from app.models.intent import IntentDecision
from app.pipeline.intent_funnel import IntentFunnel


@pytest.mark.asyncio
async def test_rule_layer_defaults_no_previous_dsl_to_create() -> None:
    funnel = IntentFunnel()
    decision = await funnel.classify("筛选规模大于10亿的主动权益基金", previous_dsl=None)
    assert decision.intent == "CREATE"
    assert decision.source == "RULE"
    assert "NO_PREVIOUS_DSL" in decision.reason_codes


@pytest.mark.asyncio
async def test_rule_layer_defaults_independent_request_to_create() -> None:
    funnel = IntentFunnel()
    decision = await funnel.classify(
        "筛选规模大于10亿的主动权益基金",
        previous_dsl={"version": "fund-screening/v1"},
    )
    assert decision.intent == "CREATE"
    assert decision.source == "RULE"
    assert "DEFAULT_CREATE" in decision.reason_codes


@pytest.mark.asyncio
async def test_context_layer_accepts_high_confidence_edit() -> None:
    async def context_classifier(query, previous_dsl, clarification):
        return IntentDecision(
            intent="EDIT",
            confidence=0.92,
            evidence=["再严格一点"],
            source="CONTEXT_MODEL",
            create_probability=0.05,
            edit_probability=0.92,
        )

    funnel = IntentFunnel(context_classifier=context_classifier)
    decision = await funnel.classify("条件再严格一点", previous_dsl={"version": "fund-screening/v1"})
    assert decision.intent == "EDIT"
    assert decision.source == "CONTEXT_MODEL"


@pytest.mark.asyncio
async def test_complex_layer_handles_low_confidence_context_decision() -> None:
    async def context_classifier(query, previous_dsl, clarification):
        return IntentDecision(intent="CREATE", confidence=0.60, evidence=["那个"], source="CONTEXT_MODEL")

    async def complex_classifier(query, previous_dsl, clarification):
        return IntentDecision(
            intent="EDIT",
            confidence=0.94,
            evidence=["刚才那个"],
            source="COMPLEX_MODEL",
            create_probability=0.03,
            edit_probability=0.94,
        )

    funnel = IntentFunnel(context_classifier=context_classifier, complex_classifier=complex_classifier)
    decision = await funnel.classify("刚才那个", previous_dsl={"version": "fund-screening/v1"})
    assert decision.intent == "EDIT"
    assert decision.source == "COMPLEX_MODEL"


@pytest.mark.asyncio
async def test_ambiguous_context_defaults_to_create_after_all_layers() -> None:
    async def uncertain_classifier(query, previous_dsl, clarification):
        return IntentDecision(intent="EDIT", confidence=0.55, evidence=[], source="CONTEXT_MODEL")

    funnel = IntentFunnel(
        context_classifier=uncertain_classifier,
        complex_classifier=uncertain_classifier,
    )
    decision = await funnel.classify("那个", previous_dsl={"version": "fund-screening/v1"})
    assert decision.intent == "CREATE"
    assert "AMBIGUOUS_CONTEXT_DEFAULT_CREATE" in decision.reason_codes


@pytest.mark.asyncio
async def test_explicit_simple_and_complex_edits() -> None:
    funnel = IntentFunnel()
    prev = {"version": "fund-screening/v1", "limit": 20, "children": []}

    # 改成前10只 -> EDIT / EXPLICIT_SIMPLE_EDIT
    d1 = await funnel.classify("改成前10只", previous_dsl=prev)
    assert d1.intent == "EDIT"
    assert "EXPLICIT_SIMPLE_EDIT" in d1.reason_codes

    # 增加最大回撤条件 -> EDIT / EXPLICIT_COMPLEX_EDIT
    d2 = await funnel.classify("增加最大回撤条件", previous_dsl=prev)
    assert d2.intent == "EDIT"
    assert "EXPLICIT_COMPLEX_EDIT" in d2.reason_codes

    # 不要之前的，筛选债券基金 -> EDIT / EXPLICIT_COMPLEX_EDIT
    d3 = await funnel.classify("不要之前的，筛选债券基金", previous_dsl=prev)
    assert d3.intent == "EDIT"
    assert "EXPLICIT_COMPLEX_EDIT" in d3.reason_codes
