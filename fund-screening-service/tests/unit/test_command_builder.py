from app.models.candidate import CandidateContract
from app.pipeline.analysis import CandidatePool
from app.pipeline.builder import CommandBuilder
import pytest


def test_builder_injects_default_sector_for_metric_only_group() -> None:
    pool = CandidatePool([CandidateContract("metric", "f_info_existingyear", "成立年限")])
    decision = {"operator": "AND", "groups": [{"metrics": [{"metric": "f_info_existingyear", "operator": ">", "value": 10, "unit": "YEAR"}]}]}

    dsl = CommandBuilder().build(decision, pool)

    assert dsl.children[0].sector.children[0].sector_id == "1000019220000000"


def test_builder_binds_company_entity_to_contract_preferred_metric() -> None:
    pool = CandidatePool([
        CandidateContract("entity", "company:eastmoney", "易方达", binding_role="FUND_COMPANY", preferred_metric="f_info_mgrcomp", supported_operators=("=",), value_type="ENTITY_REF"),
    ])
    decision = {"groups": [{"entities": [{"entity": "company:eastmoney"}]}]}

    dsl = CommandBuilder().build(decision, pool)

    leaf = dsl.children[0].metric.children[0]
    assert (leaf.metric, leaf.operator, leaf.value, leaf.unit) == ("f_info_mgrcomp", "=", "company:eastmoney", "ENTITY_REF")


def test_builder_rejects_sector_outside_candidate_pool() -> None:
    pool = CandidatePool([CandidateContract("metric", "f_return_3y", "近3年回报")])
    decision = {"groups": [{"sectorIds": ["hallucinated"], "metrics": [{"metric":"f_return_3y","operator":">","value":1,"unit":"PERCENT"}]}]}
    with pytest.raises(KeyError):
        CommandBuilder().build(decision, pool)
