from pathlib import Path

from app.catalog.loader import Catalog


def test_metric_contract_keeps_mnemonic_and_supported_usage() -> None:
    catalog = Catalog.from_files(Path("tests/fixtures/metrics-small.json"), Path("tests/fixtures/sectors-small.json"), Path("tests/fixtures/entities-small.json"))

    contract = catalog.metric_contract("f_risk_maxdownside")

    assert contract.domain == "metric"
    assert "sort" in contract.supported_usage
