import pytest
from app.catalog.parameter_rules import infer_parameters


@pytest.mark.parametrize(("mnemonic", "expected_keys"), [
    ("f_info_fullname", ["windCode"]),
    ("f_return_3y", ["windCode", "endDate"]),
    ("f_risk_maxdownside", ["windCode", "startDate", "endDate"]),
    ("f_risk_alpha", ["windCode", "startDate", "endDate", "indexCode"]),
    ("f_prt_stocktonav", ["windCode", "reportDate"]),
])
def test_infer_parameters_by_metric_family(mnemonic: str, expected_keys: list[str]) -> None:
    keys = [p["key"] for p in infer_parameters({"mnemonic": mnemonic})]
    assert keys == expected_keys
