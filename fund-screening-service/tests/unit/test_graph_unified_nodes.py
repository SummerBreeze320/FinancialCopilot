import pytest
from app.graph.nodes import ScreeningGraphNodes
from app.graph.state import empty_state
from app.models.candidate import CandidateContract
from app.models.edit_plan import EditPlan, EditOperation
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    MetricLeaf,
    AdjudicationResult,
    ConditionBinding,
)


class MockUnifiedAnalysis:
    def __init__(self):
        self.plan_called = 0
        self.edit_plan_called = 0
        self.recall_conditions_called = []

    async def generate_semantic_plan(self, query: str, previous_dsl: dict | None = None):
        self.plan_called += 1
        return SemanticPlan(
            intent="CREATE",
            groups=[
                PlanGroup(
                    groupId="g1",
                    metricTree=MetricTree(
                        operator="AND",
                        children=[
                            MetricLeaf(conditionId="m1", mention="规模大于10亿"),
                            MetricLeaf(conditionId="m2", mention="成立大于3年"),
                        ],
                    ),
                )
            ],
        )

    async def generate_edit_plan(self, query: str, previous_summary: dict):
        self.edit_plan_called += 1
        return EditPlan(
            operations=[
                EditOperation(
                    action="ADD",
                    groupId="g1",
                    condition=MetricLeaf(conditionId="m3", mention="收益大于15%"),
                )
            ]
        )

    async def recall_by_condition(self, plan: SemanticPlan, target_condition_ids: list[str] | None = None):
        self.recall_conditions_called.append(target_condition_ids)
        c3 = CandidateContract(
            domain="metric",
            key="f_return_1y",
            name="近1年收益率",
            supported_operators=(">", ">="),
        )
        by_condition = {
            "m3": {"mention": "收益大于15%", "domain": "metric", "candidates": [c3]}
        }
        contract_index = {"f_return_1y": c3}
        return by_condition, contract_index

    async def adjudicate_candidates(self, plan, by_condition, route="simple"):
        return AdjudicationResult(
            conditionBindings=[
                ConditionBinding(conditionId="m3", candidateKey="f_return_1y", operator=">", value=15.0, unit="%")
            ]
        )


@pytest.mark.asyncio
async def test_rebuild_complex_invokes_edit_plan_and_partial_recall():
    analysis = MockUnifiedAnalysis()
    nodes = ScreeningGraphNodes(analysis=analysis)

    prev_plan = SemanticPlan(
        intent="CREATE",
        groups=[
            PlanGroup(
                groupId="g1",
                metricTree=MetricTree(
                    operator="AND",
                    children=[
                        MetricLeaf(conditionId="m1", mention="规模大于10亿"),
                        MetricLeaf(conditionId="m2", mention="成立大于3年"),
                    ],
                ),
            )
        ],
    )
    state = empty_state("再加一个收益大于15%", previous_dsl={"version": "v1"})
    state["intent"] = "EDIT"
    state["edit_complexity"] = "COMPLEX"
    state["semantic_plan"] = prev_plan.model_dump(by_alias=True)
    state["candidate_contract_index"] = {
        "f_scale": {"key": "f_scale", "domain": "metric", "name": "规模", "supported_operators": [">"]}
    }

    # 1. semantic_plan node in EDIT_COMPLEX
    sem_res = await nodes.semantic_plan(state)
    state = {**state, **sem_res}
    assert analysis.edit_plan_called == 1
    assert state.get("conditions_to_recall") == ["m3"]
    assert len(state["semantic_plan"]["groups"][0]["metricTree"]["children"]) == 3

    # 2. recall_by_condition node
    rec_res = await nodes.recall_by_condition(state)
    state = {**state, **rec_res}
    assert analysis.recall_conditions_called == [["m3"]]
    assert "f_scale" in state["candidate_contract_index"]
    assert "f_return_1y" in state["candidate_contract_index"]
