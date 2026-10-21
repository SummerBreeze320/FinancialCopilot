import pytest
from langchain_core.runnables import RunnableLambda
from app.models.intent import IntentModelOutput
from app.models.semantic_plan import SemanticPlan, PlanGroup, ScopeTree, ScopeLeaf, MetricTree, MetricLeaf
from app.retrieval.llm import OpenAiSemanticLlm


class MockStructuredModel:
    def __init__(self, outputs):
        self.outputs = outputs
        self.calls = []

    def with_structured_output(self, schema_cls, *args, **kwargs):
        model = self
        async def _call(inp, **kwargs):
            model.calls.append((schema_cls, inp))
            return model.outputs[schema_cls]
        return RunnableLambda(_call)


@pytest.mark.asyncio
async def test_semantic_plan_llm_generates_plan_tree():
    sample_plan = SemanticPlan(
        intent="CREATE",
        group_operator="AND",
        groups=[
            PlanGroup(
                group_id="g1",
                scope_tree=ScopeTree(
                    operator="OR",
                    children=[ScopeLeaf(condition_id="s1", mention="消费行业", domain="scope")],
                ),
                metric_tree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(
                            condition_id="m1",
                            mention="规模大于10亿",
                            domain="metric",
                            operator_hint=">",
                            value_hint=10,
                            unit_hint="亿",
                        )
                    ],
                ),
            )
        ],
        limit=30,
    )
    mock_model = MockStructuredModel({SemanticPlan: sample_plan})
    llm = OpenAiSemanticLlm(chat_model=mock_model)
    plan = await llm.generate_semantic_plan("消费行业规模大于10亿，按近一年收益排序取前30")

    assert isinstance(plan, SemanticPlan)
    assert len(plan.groups) == 1
    assert plan.groups[0].metric_tree.children[0].condition_id == "m1"
    assert plan.groups[0].metric_tree.children[0].operator_hint == ">"
    assert plan.groups[0].metric_tree.children[0].value_hint == 10
    assert plan.groups[0].scope_tree.children[0].condition_id == "s1"
    assert plan.limit == 30
    assert mock_model.calls[0][0] is SemanticPlan


@pytest.mark.asyncio
async def test_semantic_llm_classifies_context_intent_with_structured_output():
    output = IntentModelOutput(
        intent="EDIT",
        confidence=0.91,
        evidence=["再严格一点"],
        create_probability=0.05,
        edit_probability=0.91,
    )
    llm = OpenAiSemanticLlm(chat_model=MockStructuredModel({IntentModelOutput: output}))

    decision = await llm.classify_context_intent(
        "条件再严格一点",
        {"version": "fund-screening/v1", "limit": 20},
        None,
    )

    assert decision.intent == "EDIT"
    assert decision.source == "CONTEXT_MODEL"
    assert decision.evidence == ["再严格一点"]
