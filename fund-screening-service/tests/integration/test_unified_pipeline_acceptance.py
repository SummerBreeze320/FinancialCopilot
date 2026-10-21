import pytest
from langgraph.checkpoint.memory import MemorySaver
from app.graph.workflow import build_screening_graph
from app.graph.nodes import ScreeningGraphNodes
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl
from app.models.edit_plan import EditPlan, EditOperation
from app.models.semantic_plan import (
    SemanticPlan,
    PlanGroup,
    MetricTree,
    MetricLeaf,
    AdjudicationResult,
    ConditionBinding,
)
from app.pipeline.orchestrator import ScreeningOrchestrator


class MockPipelineLlmService:
    def __init__(self):
        self.plan_calls = 0
        self.edit_calls = 0
        self.adjudicate_calls = 0

    async def generate_semantic_plan(self, query: str, previous_dsl=None):
        self.plan_calls += 1
        return SemanticPlan(
            intent="CREATE",
            groups=[
                PlanGroup(
                    groupId="g1",
                    metricTree=MetricTree(
                        operator="AND",
                        children=[
                            MetricLeaf(conditionId="m1", mention="规模大于10亿", operatorHint=">", valueHint=10.0, unitHint="亿"),
                            MetricLeaf(conditionId="m2", mention="成立年限大于3年", operatorHint=">", valueHint=3.0, unitHint="年"),
                        ],
                    ),
                )
            ],
            limit=20,
        )

    async def generate_edit_plan(self, query: str, previous_summary: dict):
        self.edit_calls += 1
        return EditPlan(
            operations=[
                EditOperation(
                    action="REPLACE",
                    targetConditionId="m1",
                    condition=MetricLeaf(conditionId="m1_rep", mention="规模大于20亿", operatorHint=">", valueHint=20.0, unitHint="亿"),
                ),
                EditOperation(
                    action="ADD",
                    groupId="g1",
                    condition=MetricLeaf(conditionId="m3", mention="收益大于10%", operatorHint=">", valueHint=10.0, unitHint="%"),
                ),
            ]
        )

    async def recall_by_condition(self, plan: SemanticPlan, target_condition_ids: list[str] | None = None):
        c_scale = CandidateContract(domain="metric", key="f_info_fundscale", name="基金规模", supported_operators=(">", ">="))
        c_year = CandidateContract(domain="metric", key="f_info_existingyear", name="成立年限", supported_operators=(">", ">="))
        c_ret = CandidateContract(domain="metric", key="f_return_1y", name="近1年收益率", supported_operators=(">", ">="))

        all_map = {"m1": c_scale, "m1_rep": c_scale, "m2": c_year, "m3": c_ret}
        by_cond = {}
        contract_index = {}
        for cid, cand in all_map.items():
            if target_condition_ids is None or cid in target_condition_ids:
                by_cond[cid] = {"mention": cand.name, "domain": "metric", "candidates": [cand]}
                contract_index[cand.key] = cand
        return by_cond, contract_index

    async def adjudicate_candidates(self, plan: SemanticPlan, by_condition: dict, route: str = "simple"):
        self.adjudicate_calls += 1
        bindings = []
        if "m1" in by_condition:
            bindings.append(ConditionBinding(conditionId="m1", candidateKey="f_info_fundscale", operator=">", value=10.0, unit="亿"))
        if "m1_rep" in by_condition:
            bindings.append(ConditionBinding(conditionId="m1_rep", candidateKey="f_info_fundscale", operator=">", value=20.0, unit="亿"))
        if "m2" in by_condition:
            bindings.append(ConditionBinding(conditionId="m2", candidateKey="f_info_existingyear", operator=">", value=3.0, unit="年"))
        if "m3" in by_condition:
            bindings.append(ConditionBinding(conditionId="m3", candidateKey="f_return_1y", operator=">", value=10.0, unit="%"))
        return AdjudicationResult(conditionBindings=bindings)


@pytest.mark.asyncio
async def test_unified_pipeline_multi_round_lifecycle():
    service = MockPipelineLlmService()
    nodes = ScreeningGraphNodes(analysis=service)
    checkpointer = MemorySaver()
    graph = build_screening_graph(nodes, checkpointer=checkpointer)
    orchestrator = ScreeningOrchestrator(graph=graph)

    conv_id = "test-conv-unified-1"

    # --- Round 1: CREATE ---
    dsl_r1 = await orchestrator.run("规模大于10亿且成立超过3年", conversation_id=conv_id)
    assert isinstance(dsl_r1, FundScreeningDsl)
    assert service.plan_calls == 1
    assert service.edit_calls == 0
    assert service.adjudicate_calls == 1
    assert len(dsl_r1.children[0].metric.children) == 2
    r1_dict = dsl_r1.model_dump(by_alias=True, exclude_none=True)

    # --- Round 2: EDIT_COMPLEX ---
    dsl_r2 = await orchestrator.run(
        "把规模改成20亿，再加一个收益大于10%",
        previous_dsl=r1_dict,
        conversation_id=conv_id,
    )
    assert isinstance(dsl_r2, FundScreeningDsl)
    assert service.plan_calls == 1  # Unchanged
    assert service.edit_calls == 1  # Exactly 1 EditPlan call
    assert service.adjudicate_calls == 2
    pred_metrics = {p.metric: p.value for p in dsl_r2.children[0].metric.children}
    assert pred_metrics["f_info_existingyear"] == 3.0
    assert pred_metrics["f_info_fundscale"] == 20.0
    assert pred_metrics["f_return_1y"] == 10.0
    r2_dict = dsl_r2.model_dump(by_alias=True, exclude_none=True)

    # --- Round 3: EDIT_SIMPLE (Strict Rule Bypass: 0 LLM calls) ---
    dsl_r3 = await orchestrator.run(
        "改成前50只",
        previous_dsl=r2_dict,
        conversation_id=conv_id,
    )
    assert isinstance(dsl_r3, FundScreeningDsl)
    assert service.plan_calls == 1  # 0 new LLM call
    assert service.edit_calls == 1  # 0 new LLM call
    assert service.adjudicate_calls == 2  # 0 new LLM call
    assert dsl_r3.limit == 50
    assert len(dsl_r3.children[0].metric.children) == 3
