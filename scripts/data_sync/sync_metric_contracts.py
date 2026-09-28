#!/usr/bin/env python3
"""Sync fund metric parameter contracts from JSON catalog to PostgreSQL."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Union

import psycopg2
from psycopg2.extras import Json


@dataclass
class MetricContract:
    mnemonic: str
    parameters: list[dict[str, Any]]
    result_contract: dict[str, Any]
    parameter_schema_version: int
    contract_status: str


@dataclass
class SyncReport:
    total: int = 0
    updated: int = 0
    unchanged: int = 0
    missing_in_db: int = 0
    invalid: int = 0
    rolled_back: bool = False
    errors: list[str] = field(default_factory=list)


def load_contracts(path: Union[str, Path]) -> list[MetricContract]:
    catalog_path = Path(path)
    if not catalog_path.exists():
        raise FileNotFoundError(f"Catalog file not found: {catalog_path}")

    with catalog_path.open("r", encoding="utf-8") as f:
        data = json.load(f)

    if not isinstance(data, list):
        raise ValueError(f"Catalog must be a JSON array, got {type(data).__name__}")

    contracts: list[MetricContract] = []
    for item in data:
        mnemonic = item.get("mnemonic")
        if not mnemonic:
            continue
        params = item.get("parameters", [])
        result_contract = item.get("result") or {
            "valueType": item.get("result_value_type", "NUMBER"),
            "supportedOperators": [">", ">=", "<", "<=", "=", "!="],
        }
        schema_ver = item.get("parameter_schema_version", 1)
        status = item.get("contract_status", "NEEDS_REVIEW")

        contracts.append(
            MetricContract(
                mnemonic=mnemonic,
                parameters=params,
                result_contract=result_contract,
                parameter_schema_version=schema_ver,
                contract_status=status,
            )
        )

    return contracts


def sync_contracts(
    connection: Any,
    contracts: list[MetricContract],
    dry_run: bool = False,
) -> SyncReport:
    report = SyncReport(total=len(contracts))
    if not contracts:
        return report

    with connection.cursor() as cursor:
        mnemonics = tuple(c.mnemonic for c in contracts)
        query = (
            "SELECT mnemonic, parameters, result_contract, parameter_schema_version, contract_status "
            "FROM rag_fund_metric WHERE mnemonic IN %s;"
        )
        cursor.execute(query, (mnemonics,))
        db_rows = cursor.fetchall()
        db_by_mnemonic = {row[0]: row for row in db_rows}

        missing = [c.mnemonic for c in contracts if c.mnemonic not in db_by_mnemonic]
        if missing:
            report.missing_in_db = len(missing)
            report.errors.append(f"Missing mnemonics in DB: {missing[:10]}")
            if not dry_run:
                connection.rollback()
                report.rolled_back = True
                return report

        to_update: list[MetricContract] = []
        for contract in contracts:
            if contract.mnemonic in db_by_mnemonic:
                _, db_params, db_res, db_ver, db_status = db_by_mnemonic[contract.mnemonic]

                # Normalize comparison
                params_match = (db_params == contract.parameters)
                res_match = (
                    db_res == contract.result_contract
                    or (isinstance(db_res, dict) and db_res.get("result_value_type") == contract.result_contract.get("result_value_type"))
                )
                ver_match = (db_ver == contract.parameter_schema_version)
                status_match = (db_status == contract.contract_status)

                if params_match and res_match and ver_match and status_match:
                    report.unchanged += 1
                else:
                    to_update.append(contract)

        report.updated = len(to_update)

        if dry_run:
            return report

        if to_update:
            update_sql = (
                "UPDATE rag_fund_metric "
                "SET parameters = %s, "
                "    result_contract = %s, "
                "    parameter_schema_version = %s, "
                "    contract_status = %s, "
                "    contract_updated_at = CURRENT_TIMESTAMP "
                "WHERE mnemonic = %s;"
            )
            for c in to_update:
                cursor.execute(
                    update_sql,
                    (
                        Json(c.parameters),
                        Json(c.result_contract),
                        c.parameter_schema_version,
                        c.contract_status,
                        c.mnemonic,
                    ),
                )
            connection.commit()

    return report


def verify_contracts(
    connection: Any,
    contracts: list[MetricContract],
) -> list[str]:
    discrepancies: list[str] = []
    with connection.cursor() as cursor:
        mnemonics = tuple(c.mnemonic for c in contracts)
        query = (
            "SELECT mnemonic, parameters, result_contract, parameter_schema_version, contract_status "
            "FROM rag_fund_metric WHERE mnemonic IN %s;"
        )
        cursor.execute(query, (mnemonics,))
        db_rows = cursor.fetchall()
        db_by_mnemonic = {row[0]: row for row in db_rows}

        for c in contracts:
            if c.mnemonic not in db_by_mnemonic:
                discrepancies.append(f"[{c.mnemonic}] missing from database")
                continue

            _, db_params, db_res, db_ver, db_status = db_by_mnemonic[c.mnemonic]
            if db_params != c.parameters:
                discrepancies.append(f"[{c.mnemonic}] parameters mismatch")
            if (
                db_res != c.result_contract
                and (not isinstance(db_res, dict) or db_res.get("result_value_type") != c.result_contract.get("result_value_type"))
            ):
                discrepancies.append(f"[{c.mnemonic}] result_contract mismatch")
            if db_ver != c.parameter_schema_version:
                discrepancies.append(f"[{c.mnemonic}] parameter_schema_version mismatch: db={db_ver}, expected={c.parameter_schema_version}")
            if db_status != c.contract_status:
                discrepancies.append(f"[{c.mnemonic}] contract_status mismatch: db={db_status}, expected={c.contract_status}")

    return discrepancies


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync metric parameter contracts to PostgreSQL")
    parser.add_argument("--catalog", default="docs/temp/metrics.json", help="Path to metrics.json catalog")
    parser.add_argument(
        "--database-url",
        default="postgresql://postgres:123456@127.0.0.1:15432/financial_copilot",
        help="PostgreSQL connection string",
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--dry-run", action="store_true", help="Report planned changes without writing to DB")
    group.add_argument("--apply", action="store_true", help="Apply contract changes to DB in a single transaction")
    group.add_argument("--verify-only", action="store_true", help="Verify DB contracts match catalog exactly")

    args = parser.parse_args()

    contracts = load_contracts(args.catalog)

    conn = psycopg2.connect(args.database_url)
    try:
        if args.verify_only:
            discrepancies = verify_contracts(conn, contracts)
            if discrepancies:
                print(json.dumps({"verified": False, "discrepancies_count": len(discrepancies), "errors": discrepancies[:20]}, indent=2))
                return 1
            print(json.dumps({"verified": True, "total": len(contracts), "discrepancies_count": 0}, indent=2))
            return 0

        report = sync_contracts(conn, contracts, dry_run=args.dry_run)
        print(json.dumps(asdict(report), indent=2))
        return 0 if not report.rolled_back and report.missing_in_db == 0 else 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
