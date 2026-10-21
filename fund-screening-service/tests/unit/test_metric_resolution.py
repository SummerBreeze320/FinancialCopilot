from datetime import date
import pytest
from pydantic import ValidationError

from app.models.semantic_plan import TimeHint
from app.pipeline.metric_resolution import MetricResolutionPolicy, MetricResolution


def test_time_hint_parsing():
    # 覆盖：
    # 本季度 → CALENDAR_QUARTER offset=0
    # 上季度 → CALENDAR_QUARTER offset=-1
    # 近三个月 → TRAILING 3 MONTH
    # 今年以来 → YEAR_TO_DATE
    # 2024年第二季度 → FIXED_QUARTER year=2024 quarter=2
    h_curr_q = TimeHint.model_validate({"type": "CALENDAR_QUARTER", "offset": 0})
    assert h_curr_q.type == "CALENDAR_QUARTER"
    assert h_curr_q.offset == 0

    h_prev_q = TimeHint.model_validate({"type": "CALENDAR_QUARTER", "offset": -1})
    assert h_prev_q.offset == -1

    h_trailing_3m = TimeHint.model_validate({"type": "TRAILING", "value": 3, "unit": "MONTH"})
    assert h_trailing_3m.type == "TRAILING"
    assert h_trailing_3m.value == 3
    assert h_trailing_3m.unit == "MONTH"

    h_ytd = TimeHint.model_validate({"type": "YEAR_TO_DATE"})
    assert h_ytd.type == "YEAR_TO_DATE"

    h_fixed_q = TimeHint.model_validate({"type": "FIXED_QUARTER", "year": 2024, "quarter": 2})
    assert h_fixed_q.type == "FIXED_QUARTER"
    assert h_fixed_q.year == 2024
    assert h_fixed_q.quarter == 2


def test_trailing_one_year_prefers_fixed_metric():
    resolver = MetricResolutionPolicy()
    result = resolver.resolve(TimeHint(type="TRAILING", value=1, unit="YEAR"), as_of="20260930")
    assert result.metric == "f_return_1y"
    assert result.params == {"endDate": "20260930"}
    assert result.strategy == "FIXED_WINDOW_SELECTION"


def test_current_quarter_falls_back_to_interval_return():
    resolver = MetricResolutionPolicy()
    result = resolver.resolve(TimeHint(type="CALENDAR_QUARTER", offset=0), as_of="20260930")
    assert result.metric == "f_nav_adjustedreturn"
    assert result.params == {"startDate": "20260701", "endDate": "20260930"}
    assert result.strategy == "INTERVAL_FALLBACK"


def test_last_quarter_falls_back_to_interval_return():
    resolver = MetricResolutionPolicy()
    result = resolver.resolve(TimeHint(type="CALENDAR_QUARTER", offset=-1), as_of="20260930")
    assert result.metric == "f_nav_adjustedreturn"
    assert result.params == {"startDate": "20260401", "endDate": "20260630"}
    assert result.strategy == "INTERVAL_FALLBACK"


def test_fixed_quarter_falls_back_to_interval_return():
    resolver = MetricResolutionPolicy()
    result = resolver.resolve(TimeHint(type="FIXED_QUARTER", year=2024, quarter=2), as_of="20260930")
    assert result.metric == "f_nav_adjustedreturn"
    assert result.params == {"startDate": "20240401", "endDate": "20240630"}
    assert result.strategy == "INTERVAL_FALLBACK"


def test_year_to_date_prefers_fixed_metric():
    resolver = MetricResolutionPolicy()
    result = resolver.resolve(TimeHint(type="YEAR_TO_DATE"), as_of="20260930")
    assert result.metric == "f_return_ytd"
    assert result.params == {"endDate": "20260930"}
    assert result.strategy == "FIXED_WINDOW_SELECTION"


def test_never_creates_nonexistent_metrics():
    resolver = MetricResolutionPolicy()
    # 比如 trailing 7 month 不存在固定指标，应兜底到区间收益而不是编造 f_return_7m
    result = resolver.resolve(TimeHint(type="TRAILING", value=7, unit="MONTH"), as_of="20260930")
    assert result.metric == "f_nav_adjustedreturn"
    assert result.params.get("startDate") is not None
    assert result.params.get("endDate") == "20260930"
    assert result.strategy == "INTERVAL_FALLBACK"
