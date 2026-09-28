from app.models.candidate import CandidateContract
from app.pipeline.analysis import CandidatePool
from app.pipeline.builder import CommandBuilder


def test_builder_injects_default_sector_for_metric_only_group() -> None:
    pool = CandidatePool([CandidateContract("metric", "f_info_existingyear", "成立年限")])
    decision = {"operator": "AND", "groups": [{"metrics": [{"metric": "f_info_existingyear", "operator": ">", "value": 10, "unit": "YEAR"}]}]}

    dsl = CommandBuilder().build(decision, pool)

    assert dsl.children[0].sector.children[0].sector_id == "1000019220000000"
