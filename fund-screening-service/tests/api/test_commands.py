from fastapi.testclient import TestClient

from app.main import create_app


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
    assert second.json()["dsl"]["children"] == first["children"]
