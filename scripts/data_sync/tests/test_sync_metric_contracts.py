import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, call

from scripts.data_sync.sync_metric_contracts import (
    MetricContract,
    SyncReport,
    load_contracts,
    sync_contracts,
    verify_contracts,
)


class TestSyncMetricContracts(unittest.TestCase):
    def setUp(self):
        self.sample_metrics = [
            {
                "mnemonic": "f_test_1",
                "parameters": [
                    {
                        "position": 1,
                        "key": "windCode",
                        "valueType": "STRING",
                        "required": True,
                    }
                ],
                "result": {
                    "valueType": "DECIMAL",
                    "unit": "%",
                    "supportedOperators": [">", ">=", "<", "<=", "=", "!="],
                },
                "parameter_schema_version": 1,
                "contract_status": "INFERRED",
            },
            {
                "mnemonic": "f_test_2",
                "parameters": [
                    {
                        "position": 1,
                        "key": "windCode",
                        "valueType": "STRING",
                        "required": True,
                    }
                ],
                "result": {
                    "valueType": "STRING",
                    "unit": None,
                    "supportedOperators": ["=", "!="],
                },
                "parameter_schema_version": 1,
                "contract_status": "REVIEWED",
            },
        ]

    def test_load_contracts(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
            json.dump(self.sample_metrics, f, ensure_ascii=False)
            tmp_path = Path(f.name)

        try:
            contracts = load_contracts(tmp_path)
            self.assertEqual(len(contracts), 2)
            self.assertEqual(contracts[0].mnemonic, "f_test_1")
            self.assertEqual(contracts[0].result_contract.get("valueType"), "DECIMAL")
            self.assertEqual(contracts[0].result_contract.get("unit"), "%")
            self.assertEqual(contracts[1].contract_status, "REVIEWED")
        finally:
            if tmp_path.exists():
                tmp_path.unlink()

    def test_dry_run_does_not_modify_database(self):
        contracts = [
            MetricContract(
                mnemonic="f_test_1",
                parameters=[{"position": 1, "key": "windCode"}],
                result_contract={"result_value_type": "PERCENT"},
                parameter_schema_version=1,
                contract_status="INFERRED",
            )
        ]

        conn = MagicMock()
        cursor = MagicMock()
        conn.cursor.return_value.__enter__.return_value = cursor

        # Existing record has empty parameters
        cursor.fetchall.return_value = [
            ("f_test_1", [], {}, 1, "NEEDS_REVIEW")
        ]

        report = sync_contracts(conn, contracts, dry_run=True)
        self.assertEqual(report.total, 1)
        self.assertEqual(report.updated, 1)
        self.assertEqual(report.unchanged, 0)
        self.assertFalse(report.rolled_back)

        # In dry run, no UPDATE should be executed, and conn.commit should NOT be called
        for c in cursor.execute.call_args_list:
            sql = c[0][0].upper()
            self.assertNotIn("UPDATE", sql)
        conn.commit.assert_not_called()

    def test_sync_updates_only_five_columns_and_never_embedding(self):
        contracts = [
            MetricContract(
                mnemonic="f_test_1",
                parameters=[{"position": 1, "key": "windCode"}],
                result_contract={"result_value_type": "PERCENT"},
                parameter_schema_version=1,
                contract_status="INFERRED",
            )
        ]

        conn = MagicMock()
        cursor = MagicMock()
        conn.cursor.return_value.__enter__.return_value = cursor

        cursor.fetchall.return_value = [
            ("f_test_1", [], {}, 1, "NEEDS_REVIEW")
        ]

        report = sync_contracts(conn, contracts, dry_run=False)
        self.assertEqual(report.updated, 1)
        self.assertFalse(report.rolled_back)
        conn.commit.assert_called_once()

        # Check all executed queries: never touch embedding
        executed_sqls = [c[0][0] for c in cursor.execute.call_args_list]
        for sql in executed_sqls:
            self.assertNotIn("embedding", sql.lower())

        # Check update statement updates only contract columns
        update_sqls = [s for s in executed_sqls if "update" in s.lower()]
        self.assertEqual(len(update_sqls), 1)
        up_sql = update_sqls[0].lower()
        self.assertIn("parameters", up_sql)
        self.assertIn("result_contract", up_sql)
        self.assertIn("parameter_schema_version", up_sql)
        self.assertIn("contract_status", up_sql)
        self.assertIn("contract_updated_at", up_sql)

    def test_unknown_mnemonic_fails_and_rolls_back(self):
        contracts = [
            MetricContract(
                mnemonic="f_non_existent",
                parameters=[],
                result_contract={"result_value_type": "PERCENT"},
                parameter_schema_version=1,
                contract_status="INFERRED",
            )
        ]

        conn = MagicMock()
        cursor = MagicMock()
        conn.cursor.return_value.__enter__.return_value = cursor

        # DB has no records for f_non_existent
        cursor.fetchall.return_value = []

        report = sync_contracts(conn, contracts, dry_run=False)
        self.assertEqual(report.missing_in_db, 1)
        self.assertTrue(report.rolled_back)
        conn.rollback.assert_called_once()
        conn.commit.assert_not_called()

    def test_second_sync_reports_all_unchanged(self):
        params = [{"position": 1, "key": "windCode"}]
        res_contract = {"result_value_type": "PERCENT"}
        contracts = [
            MetricContract(
                mnemonic="f_test_1",
                parameters=params,
                result_contract=res_contract,
                parameter_schema_version=1,
                contract_status="INFERRED",
            )
        ]

        conn = MagicMock()
        cursor = MagicMock()
        conn.cursor.return_value.__enter__.return_value = cursor

        # DB already has identical values
        cursor.fetchall.return_value = [
            ("f_test_1", params, res_contract, 1, "INFERRED")
        ]

        report = sync_contracts(conn, contracts, dry_run=False)
        self.assertEqual(report.total, 1)
        self.assertEqual(report.updated, 0)
        self.assertEqual(report.unchanged, 1)


if __name__ == "__main__":
    unittest.main()
