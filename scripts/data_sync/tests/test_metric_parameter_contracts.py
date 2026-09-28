import json
import tempfile
import unittest
from pathlib import Path

from scripts.data_sync.validate_metric_parameter_contracts import (
    ValidationReport,
    validate_catalog,
    validate_metric_contract,
)


class TestMetricParameterContracts(unittest.TestCase):
    def setUp(self):
        self.valid_metric = {
            "mnemonic": "f_valid_metric",
            "index_name": "有效测试指标",
            "parameters": [
                {
                    "position": 1,
                    "key": "windCode",
                    "valueType": "STRING",
                    "semanticType": "FUND_CODE",
                    "required": True,
                    "description": "基金 Wind 代码",
                },
                {
                    "position": 2,
                    "key": "endDate",
                    "valueType": "DATE",
                    "format": "yyyyMMdd",
                    "semanticType": "TRADE_DATE",
                    "required": True,
                    "description": "截止日期",
                },
            ],
            "result": {
                "valueType": "DECIMAL",
                "precision": {
                    "totalDigits": 12,
                    "scale": 6,
                    "roundingMode": "HALF_UP",
                },
                "displayScale": 2,
                "unit": "%",
                "supportedOperators": [">", ">=", "<", "<=", "=", "!="],
            },
            "parameter_schema_version": 1,
            "contract_status": "INFERRED",
        }

    def test_valid_two_parameter_contract(self):
        is_valid, errors = validate_metric_contract(self.valid_metric)
        self.assertTrue(is_valid, f"Expected valid, got errors: {errors}")
        self.assertEqual(len(errors), 0)

    def test_array_order_mismatch_with_position(self):
        metric = json.loads(json.dumps(self.valid_metric))
        # Position 2 is placed before Position 1 in the array
        metric["parameters"] = [
            {
                "position": 2,
                "key": "endDate",
                "valueType": "DATE",
                "format": "yyyyMMdd",
                "required": True,
            },
            {
                "position": 1,
                "key": "windCode",
                "valueType": "STRING",
                "required": True,
            },
        ]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("position" in err.lower() or "order" in err.lower() for err in errors),
            f"Expected position order error, got: {errors}",
        )

    def test_position_duplication(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["parameters"] = [
            {
                "position": 1,
                "key": "windCode",
                "valueType": "STRING",
                "required": True,
            },
            {
                "position": 1,
                "key": "endDate",
                "valueType": "DATE",
                "format": "yyyyMMdd",
                "required": True,
            },
        ]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("position" in err.lower() for err in errors),
            f"Expected position error, got: {errors}",
        )

    def test_position_gaps_or_non_one_start(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["parameters"] = [
            {
                "position": 1,
                "key": "windCode",
                "valueType": "STRING",
                "required": True,
            },
            {
                "position": 3,
                "key": "endDate",
                "valueType": "DATE",
                "format": "yyyyMMdd",
                "required": True,
            },
        ]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("position" in err.lower() for err in errors),
            f"Expected position gap error, got: {errors}",
        )

    def test_unknown_value_type(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["parameters"] = [
            {
                "position": 1,
                "key": "ratio",
                "valueType": "FLOAT",  # FLOAT is not allowed, must be DECIMAL
                "required": True,
            }
        ]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("valueType" in err for err in errors),
            f"Expected valueType error, got: {errors}",
        )

    def test_enum_missing_options(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["parameters"] = [
            {
                "position": 1,
                "key": "term",
                "valueType": "ENUM",
                "required": True,
                # options missing
            }
        ]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("options" in err for err in errors),
            f"Expected options error, got: {errors}",
        )

    def test_enum_option_value_non_integer(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["parameters"] = [
            {
                "position": 1,
                "key": "term",
                "valueType": "ENUM",
                "required": True,
                "options": [
                    {"value": "1", "label": "长期"},  # String value instead of int
                    {"value": 2, "label": "短期"},
                ],
            }
        ]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("options" in err or "integer" in err.lower() for err in errors),
            f"Expected non-integer option value error, got: {errors}",
        )

    def test_enum_option_value_duplicate(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["parameters"] = [
            {
                "position": 1,
                "key": "term",
                "valueType": "ENUM",
                "required": True,
                "options": [
                    {"value": 1, "label": "选项A"},
                    {"value": 1, "label": "选项B"},
                ],
            }
        ]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("duplicate" in err.lower() or "unique" in err.lower() for err in errors),
            f"Expected duplicate enum option error, got: {errors}",
        )

    def test_unilateral_pair_with(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["parameters"] = [
            {
                "position": 1,
                "key": "startDate",
                "valueType": "DATE",
                "format": "yyyyMMdd",
                "required": True,
                "pairWith": "endDate",
            },
            {
                "position": 2,
                "key": "endDate",
                "valueType": "DATE",
                "format": "yyyyMMdd",
                "required": True,
                # Missing pairWith: "startDate"
            },
        ]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("pairWith" in err for err in errors),
            f"Expected pairWith error, got: {errors}",
        )

    def test_date_missing_format_yyyymmdd(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["parameters"] = [
            {
                "position": 1,
                "key": "tradeDate",
                "valueType": "DATE",
                "required": True,
                # Missing format: "yyyyMMdd"
            }
        ]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("format" in err.lower() for err in errors),
            f"Expected format error, got: {errors}",
        )

    def test_decimal_missing_precision(self):
        metric = json.loads(json.dumps(self.valid_metric))
        del metric["result"]["precision"]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("precision" in err for err in errors),
            f"Expected missing precision error, got: {errors}",
        )

    def test_non_decimal_with_precision(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["result"]["valueType"] = "INTEGER"
        # precision wrongly left on non-DECIMAL
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("precision" in err for err in errors),
            f"Expected precision error for non-DECIMAL, got: {errors}",
        )

    def test_english_unit_prohibited(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["result"]["unit"] = "PERCENT"  # Prohibited, must be %
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("unit" in err for err in errors),
            f"Expected prohibited unit error, got: {errors}",
        )

        metric["result"]["unit"] = "NONE"  # Prohibited, must be null
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)

    def test_illegal_operator(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["result"]["supportedOperators"] = [">", "CONTAINS"]
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("supportedOperators" in err for err in errors),
            f"Expected illegal operator error, got: {errors}",
        )

    def test_string_result_with_magnitude_operator(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["result"]["valueType"] = "STRING"
        del metric["result"]["precision"]
        metric["result"]["unit"] = None
        metric["result"]["supportedOperators"] = [">", "="]  # Magnitude operator not allowed for STRING
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("magnitude" in err.lower() or "supportedOperators" in err for err in errors),
            f"Expected magnitude operator error on STRING, got: {errors}",
        )

    def test_result_enum_missing_options(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["result"]["valueType"] = "ENUM"
        del metric["result"]["precision"]
        metric["result"]["unit"] = None
        metric["result"]["supportedOperators"] = ["=", "!="]
        # missing options
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("options" in err for err in errors),
            f"Expected options error on ENUM result, got: {errors}",
        )

    def test_invalid_contract_status(self):
        metric = json.loads(json.dumps(self.valid_metric))
        metric["contract_status"] = "DRAFT"  # Allowed: INFERRED, REVIEWED, NEEDS_REVIEW
        is_valid, errors = validate_metric_contract(metric)
        self.assertFalse(is_valid)
        self.assertTrue(
            any("contract_status" in err for err in errors),
            f"Expected contract_status error, got: {errors}",
        )

    def test_missing_required_contract_fields(self):
        for field in [
            "parameters",
            "result",
            "parameter_schema_version",
            "contract_status",
        ]:
            metric = json.loads(json.dumps(self.valid_metric))
            del metric[field]
            is_valid, errors = validate_metric_contract(metric)
            self.assertFalse(is_valid, f"Should fail when missing {field}")
            self.assertTrue(
                any(field in err for err in errors),
                f"Expected missing field '{field}' error, got: {errors}",
            )

    def test_validate_catalog_report(self):
        data = [
            self.valid_metric,
            {
                "mnemonic": "f_invalid_one",
                "parameters": "invalid_not_a_list",
                "result": {"valueType": "DECIMAL"},
                "parameter_schema_version": 1,
                "contract_status": "INFERRED",
            },
        ]
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
            tmp_path = Path(f.name)

        try:
            report = validate_catalog(tmp_path)
            self.assertIsInstance(report, ValidationReport)
            self.assertEqual(report.total, 2)
            self.assertEqual(report.valid, 1)
            self.assertGreater(len(report.errors), 0)
            self.assertTrue(any("f_invalid_one" in err for err in report.errors))
        finally:
            if tmp_path.exists():
                tmp_path.unlink()

    def test_info_family_contracts(self):
        catalog_path = Path("docs/temp/metrics.json")
        self.assertTrue(catalog_path.exists(), f"Missing catalog file: {catalog_path}")
        with catalog_path.open("r", encoding="utf-8") as f:
            catalog = json.load(f)

        info_metrics = [m for m in catalog if m.get("mnemonic", "").startswith("f_info_")]
        self.assertEqual(len(info_metrics), 52, f"Expected 52 f_info_ metrics, found {len(info_metrics)}")

        failures = []
        for m in info_metrics:
            is_valid, errors = validate_metric_contract(m)
            if not is_valid:
                failures.append(f"{m.get('mnemonic')}: {errors}")

        self.assertEqual(
            len(failures),
            0,
            f"Expected all 52 f_info_ metrics to have valid contracts, but {len(failures)} failed:\n"
            + "\n".join(failures[:10]),
        )

    def test_return_market_event_family_contracts(self):
        catalog_path = Path("docs/temp/metrics.json")
        self.assertTrue(catalog_path.exists(), f"Missing catalog file: {catalog_path}")
        with catalog_path.open("r", encoding="utf-8") as f:
            catalog = json.load(f)

        target_prefixes = (
            "f_return_",
            "f_nav_",
            "f_mmf_",
            "f_dq_",
            "f_pq_",
            "s_pq_",
            "f_quot_",
            "f_div_",
            "f_netasset_",
            "f_pchredm_",
            "f_customizedfundornot",
            "f_issue_",
        )
        target_metrics = [
            m for m in catalog
            if any(m.get("mnemonic", "").startswith(p) for p in target_prefixes)
        ]
        self.assertEqual(
            len(target_metrics),
            51,
            f"Expected 51 return/market/event metrics, found {len(target_metrics)}",
        )

        failures = []
        for m in target_metrics:
            is_valid, errors = validate_metric_contract(m)
            if not is_valid:
                failures.append(f"{m.get('mnemonic')}: {errors}")

        self.assertEqual(
            len(failures),
            0,
            f"Expected all 51 return/market/event metrics to have valid contracts, but {len(failures)} failed:\n"
            + "\n".join(failures[:10]),
        )

    def test_risk_family_contracts(self):
        catalog_path = Path("docs/temp/metrics.json")
        self.assertTrue(catalog_path.exists(), f"Missing catalog file: {catalog_path}")
        with catalog_path.open("r", encoding="utf-8") as f:
            catalog = json.load(f)

        risk_metrics = [m for m in catalog if m.get("mnemonic", "").startswith("f_risk_")]
        self.assertEqual(len(risk_metrics), 31, f"Expected 31 f_risk_ metrics, found {len(risk_metrics)}")

        failures = []
        for m in risk_metrics:
            is_valid, errors = validate_metric_contract(m)
            if not is_valid:
                failures.append(f"{m.get('mnemonic')}: {errors}")

        self.assertEqual(
            len(failures),
            0,
            f"Expected all 31 f_risk_ metrics to have valid contracts, but {len(failures)} failed:\n"
            + "\n".join(failures[:10]),
        )

    def test_portfolio_holder_style_family_contracts(self):
        catalog_path = Path("docs/temp/metrics.json")
        self.assertTrue(catalog_path.exists(), f"Missing catalog file: {catalog_path}")
        with catalog_path.open("r", encoding="utf-8") as f:
            catalog = json.load(f)

        target_prefixes = ("f_prt_", "f_holder_", "f_style_")
        target_metrics = [
            m for m in catalog
            if any(m.get("mnemonic", "").startswith(p) for p in target_prefixes)
        ]
        self.assertEqual(
            len(target_metrics),
            35,
            f"Expected 35 portfolio/holder/style metrics, found {len(target_metrics)}",
        )

        failures = []
        for m in target_metrics:
            is_valid, errors = validate_metric_contract(m)
            if not is_valid:
                failures.append(f"{m.get('mnemonic')}: {errors}")

        self.assertEqual(
            len(failures),
            0,
            f"Expected all 35 portfolio/holder/style metrics to have valid contracts, but {len(failures)} failed:\n"
            + "\n".join(failures[:10]),
        )

    def test_full_catalog_all_contracts_valid(self):
        catalog_path = Path("docs/temp/metrics.json")
        report = validate_catalog(catalog_path)
        self.assertEqual(report.total, 169)
        self.assertEqual(
            report.valid,
            169,
            f"Expected 169 valid metrics, got {report.valid}. First errors:\n"
            + "\n".join(report.errors[:10]),
        )


if __name__ == "__main__":
    unittest.main()
