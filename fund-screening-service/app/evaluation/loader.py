from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable
from app.evaluation.schema import GoldenCase


def load_golden_dataset(paths: Iterable[Path] | Path) -> list[GoldenCase]:
    if isinstance(paths, Path):
        file_list = list(paths.glob("*.jsonl")) if paths.is_dir() else [paths]
    else:
        file_list = list(paths)

    cases: list[GoldenCase] = []
    for filepath in sorted(file_list):
        if not filepath.exists():
            continue
        with open(filepath, "r", encoding="utf-8") as f:
            for line_no, line in enumerate(f, 1):
                clean_line = line.strip()
                if not clean_line or clean_line.startswith("#"):
                    continue
                try:
                    data = json.loads(clean_line)
                    cases.append(GoldenCase.model_validate(data))
                except Exception as exc:
                    raise ValueError(f"Failed parsing golden case at {filepath}:{line_no}: {exc}") from exc
    return cases
