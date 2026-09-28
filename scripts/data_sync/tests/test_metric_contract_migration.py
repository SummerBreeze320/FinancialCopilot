import unittest
from pathlib import Path


class TestMetricContractMigration(unittest.TestCase):
    def setUp(self):
        self.initdb_path = Path("docker/postgres/initdb/03_rag_schema.sql")
        self.migration_path = Path("docker/postgres/migrations/20260929_add_metric_parameter_contracts.sql")

    def test_initdb_contains_contract_columns_and_constraints(self):
        self.assertTrue(self.initdb_path.exists(), f"Missing initdb file: {self.initdb_path}")
        content = self.initdb_path.read_text(encoding="utf-8")

        required_columns = [
            "parameters",
            "result_contract",
            "parameter_schema_version",
            "contract_status",
            "contract_updated_at",
        ]
        for col in required_columns:
            self.assertIn(col, content, f"initdb missing column: {col}")

        required_checks = [
            "jsonb_typeof(parameters) = 'array'",
            "jsonb_typeof(result_contract) = 'object'",
            "'INFERRED'",
            "'REVIEWED'",
            "'NEEDS_REVIEW'",
        ]
        for chk in required_checks:
            self.assertIn(chk, content, f"initdb missing check requirement: {chk}")

    def test_migration_is_idempotent_and_safe(self):
        self.assertTrue(self.migration_path.exists(), f"Missing migration file: {self.migration_path}")
        content = self.migration_path.read_text(encoding="utf-8")

        # Must not contain destructive operations
        upper_content = content.upper()
        self.assertNotIn("DROP TABLE", upper_content)
        self.assertNotIn("TRUNCATE", upper_content)
        self.assertNotIn("DROP COLUMN", upper_content)

        # Must use ADD COLUMN IF NOT EXISTS for each column
        required_columns = [
            "parameters",
            "result_contract",
            "parameter_schema_version",
            "contract_status",
            "contract_updated_at",
        ]
        for col in required_columns:
            self.assertIn(
                f"ADD COLUMN IF NOT EXISTS {col}".lower(),
                content.lower(),
                f"Migration should use ADD COLUMN IF NOT EXISTS for {col}",
            )

        # Must check pg_constraint for idempotent constraint creation
        self.assertIn("pg_constraint", content.lower())
        self.assertIn("jsonb_typeof(parameters) = 'array'", content)
        self.assertIn("jsonb_typeof(result_contract) = 'object'", content)
        self.assertIn("'INFERRED'", content)
        self.assertIn("'REVIEWED'", content)
        self.assertIn("'NEEDS_REVIEW'", content)


if __name__ == "__main__":
    unittest.main()
