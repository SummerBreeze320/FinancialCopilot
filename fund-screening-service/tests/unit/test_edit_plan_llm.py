import pytest
from app.models.edit_plan import EditPlan, EditOperation
from app.models.semantic_plan import MetricLeaf
from app.retrieval.llm import OpenAiSemanticLlm


@pytest.mark.asyncio
async def test_generate_edit_plan_mock():
    llm = OpenAiSemanticLlm()

    class MockRunnable:
        async def ainvoke(self, args):
            return EditPlan(
                operations=[
                    EditOperation(
                        action="REPLACE",
                        targetConditionId="m1",
                        condition=MetricLeaf(conditionId="m1_new", mention="规模大于20亿", operatorHint=">", valueHint=20.0, unitHint="亿"),
                    ),
                    EditOperation(
                        action="ADD",
                        groupId="g1",
                        condition=MetricLeaf(conditionId="m3", mention="收益大于15%", operatorHint=">", valueHint=15.0, unitHint="%"),
                    ),
                ]
            )

    llm.edit_plan_runnable = MockRunnable()
    edit_plan = await llm.generate_edit_plan("把规模改成20亿，再加收益大于15%", {"conditions": [{"conditionId": "m1", "mention": "规模大于10亿"}]})
    assert len(edit_plan.operations) == 2
    assert edit_plan.operations[0].action == "REPLACE"
    assert edit_plan.operations[0].target_condition_id == "m1"
    assert edit_plan.operations[1].action == "ADD"
