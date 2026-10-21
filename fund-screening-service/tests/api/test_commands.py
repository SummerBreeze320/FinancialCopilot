from fastapi.testclient import TestClient

from app.main import create_app
from app.models.dsl import FundScreeningDsl
from app.pipeline.compiler import CompileResult
from app.pipeline.validator import ValidationResult


class FakeRagCompiler:
    def __init__(self): self.queries = []
    async def compile(self, query):
        self.queries.append(query)
        dsl = FundScreeningDsl.model_validate({"version":"fund-screening/v1","operator":"AND","children":[{"sector":{"operator":"OR","children":[{"sectorId":"1000022713000000"}]},"metric":{"operator":"AND","children":[{"metric":"f_risk_maxdownside","operator":">=","value":-15,"unit":"PERCENT"}]}}],"limit":20})
        return CompileResult(dsl, {"route":"full"}, ValidationResult(dsl))


class FakeRecallTraceGraph:
    async def ainvoke(self, state, config):
        return {
            "route": "simple",
            "semantic_plan": {"intent": "CREATE", "groupOperator": "AND", "groups": [], "limit": 20},
            "by_condition": {
                "c1": {
                    "mention": "规模大于10亿",
                    "domain": "metric",
                    "candidates": [{"key": "f_netasset_total", "name": "基金资产净值", "score": 0.91}],
                }
            },
            "trace": [{"stage": "recall_by_condition", "status": "completed"}],
        }


def test_http_returns_final_dsl_for_supported_query() -> None:
    response = TestClient(create_app()).post("/v1/fund-screening/commands", json={"query": "成立年限大于10年"})
    assert response.status_code == 200
    assert response.json()["dsl"]["version"] == "fund-screening/v1"


def test_mvp_compiles_active_equity_drawdown_scale_sort_and_limit() -> None:
    query = "筛选最大回撤低于15%、规模大于10亿的主动权益基金，按近3年收益降序取前20只"

    response = TestClient(create_app()).post("/v1/fund-screening/commands", json={"query": query})

    assert response.status_code == 200
    dsl = response.json()["dsl"]
    assert [item["sectorId"] for item in dsl["children"][0]["sector"]["children"]] == [
        "1000002533000000", "1000002535000000"
    ]
    leaves = dsl["children"][0]["metric"]["children"]
    assert (leaves[0]["metric"], leaves[0]["operator"], leaves[0]["value"]) == ("f_risk_maxdownside", ">=", -15)
    assert (leaves[1]["metric"], leaves[1]["operator"], leaves[1]["value"]) == ("f_info_fundscale", ">", 10)
    assert dsl["sort"][0]["metric"] == "f_return_3y"
    assert dsl["limit"] == 20


def test_mvp_edits_previous_dsl_limit_in_second_turn() -> None:
    client = TestClient(create_app())
    first = client.post("/v1/fund-screening/commands", json={"query": "筛选最大回撤低于15%、规模大于10亿的主动权益基金，按近3年收益降序取前20只"}).json()["dsl"]

    second = client.post("/v1/fund-screening/commands", json={"query": "改成前50只", "previousDsl": first})

    assert second.status_code == 200
    assert second.json()["dsl"]["limit"] == 50
    second_children = second.json()["dsl"]["children"]
    first_children = first["children"]
    assert len(second_children) == len(first_children)
    for s_child, f_child in zip(second_children, first_children):
        assert s_child["sector"] == f_child["sector"]
        assert s_child["metric"] == f_child["metric"]


def test_http_uses_injected_rag_compiler() -> None:
    compiler = FakeRagCompiler()
    response = TestClient(create_app(compiler=compiler)).post("/v1/fund-screening/commands", json={"query":"半导体基金回撤低于15%"})
    assert response.json()["dsl"]["children"][0]["sector"]["children"][0]["sectorId"] == "1000022713000000"
    assert compiler.queries == ["半导体基金回撤低于15%"]


def test_rag_trace_uses_condition_mentions_from_the_graph() -> None:
    client = TestClient(create_app(graph=FakeRecallTraceGraph()))

    response = client.post("/v1/fund-screening/rag-trace", json={"query": "规模大于10亿"})

    assert response.status_code == 200
    body = response.json()
    assert body["route"] == "simple"
    assert body["conditions"] == [{
        "conditionId": "c1",
        "mention": "规模大于10亿",
        "domain": "metric",
        "candidates": [{"key": "f_netasset_total", "name": "基金资产净值", "score": 0.91}],
    }]


def test_http_direct_route_fund_code_001938() -> None:
    client = TestClient(create_app())
    response = client.post("/v1/fund-screening/commands", json={"query": "001938"})
    assert response.status_code == 200
    data = response.json()
    assert data["dsl"] is not None
    metric = data["dsl"]["children"][0]["metric"]["children"][0]
    assert metric["metric"] == "f_info_windcode"
    assert metric["value"] in ("001938.OF", "001938")
    assert metric["unit"] == "ENTITY_REF"


def test_http_simple_route_preserves_scale_and_existing_year() -> None:
    client = TestClient(create_app())
    response = client.post("/v1/fund-screening/commands", json={"query": "规模>10亿且成立>5年"})
    assert response.status_code == 200
    data = response.json()
    assert data["dsl"] is not None
    metrics = data["dsl"]["children"][0]["metric"]["children"]
    metric_map = {m["metric"]: m for m in metrics}
    assert "f_info_fundscale" in metric_map
    assert "f_info_existingyear" in metric_map
    assert metric_map["f_info_fundscale"]["unit"] == "BILLION_CNY"
    assert metric_map["f_info_fundscale"]["value"] == 10
    assert metric_map["f_info_existingyear"]["unit"] == "YEAR"
    assert metric_map["f_info_existingyear"]["value"] == 5

