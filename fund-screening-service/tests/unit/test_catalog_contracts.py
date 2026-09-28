from pathlib import Path

from app.catalog.loader import Catalog


def test_metric_contract_keeps_mnemonic_and_supported_usage() -> None:
    catalog = Catalog.from_files(Path("tests/fixtures/metrics-small.json"), Path("tests/fixtures/sectors-small.json"), Path("tests/fixtures/entities-small.json"))

    contract = catalog.metric_contract("f_risk_maxdownside")

    assert contract.domain == "metric"
    assert "sort" in contract.supported_usage


def test_catalog_flattens_entity_document_with_binding_contracts() -> None:
    catalog = Catalog.from_files(Path("tests/fixtures/metrics-small.json"), Path("tests/fixtures/sectors-small.json"), Path("tests/fixtures/entities-document-small.json"))
    bindings = {item.name: (item.binding_role, item.preferred_metric) for item in catalog.entities}
    assert bindings["易方达基金"] == ("FUND_COMPANY", "f_info_mgrcomp")
    assert bindings["张坤"] == ("FUND_MANAGER", "f_info_fundmanager")
    assert bindings["沪深300"] == ("TRACK_INDEX", "f_info_trackindex")
    assert bindings["沪电股份"] == ("STOCK", "f_info_heavystock")
