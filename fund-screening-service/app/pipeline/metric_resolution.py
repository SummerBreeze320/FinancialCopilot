from __future__ import annotations

import calendar
from datetime import date, datetime, timedelta
from typing import Any, Literal
from pydantic import BaseModel, Field

from app.models.semantic_plan import TimeHint

FIXED_RETURN_METRICS: dict[tuple[str, Any, Any], str] = {
    ("TRAILING", 1, "WEEK"): "f_return_1w",
    ("TRAILING", 1, "MONTH"): "f_return_1m",
    ("TRAILING", 3, "MONTH"): "f_return_3m",
    ("TRAILING", 6, "MONTH"): "f_return_6m",
    ("TRAILING", 1, "YEAR"): "f_return_1y",
    ("TRAILING", 2, "YEAR"): "f_return_2y",
    ("TRAILING", 3, "YEAR"): "f_return_3y",
    ("TRAILING", 5, "YEAR"): "f_return_5y",
    ("YEAR_TO_DATE", None, None): "f_return_ytd",
}

INTERVAL_RETURN_METRIC = "f_nav_adjustedreturn"


class MetricResolution(BaseModel):
    metric: str
    params: dict[str, Any] = Field(default_factory=dict)
    strategy: Literal["FIXED_WINDOW_SELECTION", "INTERVAL_FALLBACK"] = "FIXED_WINDOW_SELECTION"


class MetricResolutionPolicy:
    """时间语义与收益指标解析策略。

    确定性规则：
    1. 优先映射到目录中已存在的固定周期指标 (f_return_1m, 1y, ytd 等)。
    2. 对于自然季度 (CALENDAR_QUARTER / FIXED_QUARTER) 或非标准滚动周期，一律兜底到区间收益指标 f_nav_adjustedreturn。
    3. 严禁凭空创造目录不存在的指标 (如 f_return_q 等)。
    """

    def resolve(self, time_hint: TimeHint, as_of: date | str | None = None) -> MetricResolution:
        ref_date: date
        if as_of is None:
            ref_date = date.today()
        elif isinstance(as_of, str):
            clean_str = as_of.replace("-", "")
            ref_date = datetime.strptime(clean_str, "%Y%m%d").date()
        elif isinstance(as_of, datetime):
            ref_date = as_of.date()
        else:
            ref_date = as_of

        # 1. 滚动周期 TRAILING
        if time_hint.type == "TRAILING":
            key = ("TRAILING", time_hint.value, time_hint.unit)
            if key in FIXED_RETURN_METRICS:
                return MetricResolution(
                    metric=FIXED_RETURN_METRICS[key],
                    params={"endDate": ref_date.strftime("%Y%m%d")},
                    strategy="FIXED_WINDOW_SELECTION",
                )
            # 非标准滚动周期，兜底到区间收益指标
            val = time_hint.value or 1
            unit = time_hint.unit or "MONTH"
            if unit == "YEAR":
                start_d = ref_date.replace(year=ref_date.year - val)
            elif unit == "MONTH":
                y = ref_date.year
                m = ref_date.month - val
                while m < 1:
                    y -= 1
                    m += 12
                max_d = calendar.monthrange(y, m)[1]
                start_d = date(y, m, min(ref_date.day, max_d))
            elif unit == "WEEK":
                start_d = ref_date - timedelta(weeks=val)
            elif unit == "DAY":
                start_d = ref_date - timedelta(days=val)
            else:
                start_d = ref_date
            return MetricResolution(
                metric=INTERVAL_RETURN_METRIC,
                params={
                    "startDate": start_d.strftime("%Y%m%d"),
                    "endDate": ref_date.strftime("%Y%m%d"),
                },
                strategy="INTERVAL_FALLBACK",
            )

        # 2. 今年以来 YEAR_TO_DATE
        if time_hint.type == "YEAR_TO_DATE":
            key = ("YEAR_TO_DATE", None, None)
            if key in FIXED_RETURN_METRICS:
                return MetricResolution(
                    metric=FIXED_RETURN_METRICS[key],
                    params={"endDate": ref_date.strftime("%Y%m%d")},
                    strategy="FIXED_WINDOW_SELECTION",
                )
            start_d = date(ref_date.year, 1, 1)
            return MetricResolution(
                metric=INTERVAL_RETURN_METRIC,
                params={
                    "startDate": start_d.strftime("%Y%m%d"),
                    "endDate": ref_date.strftime("%Y%m%d"),
                },
                strategy="INTERVAL_FALLBACK",
            )

        # 3. 相对自然季度 CALENDAR_QUARTER
        if time_hint.type == "CALENDAR_QUARTER":
            offset = time_hint.offset if time_hint.offset is not None else 0
            curr_q = (ref_date.month - 1) // 3 + 1
            curr_y = ref_date.year
            total_q = curr_y * 4 + (curr_q - 1) + offset
            target_y = total_q // 4
            target_q = (total_q % 4) + 1

            start_m = (target_q - 1) * 3 + 1
            end_m = target_q * 3
            start_d = date(target_y, start_m, 1)
            max_d = calendar.monthrange(target_y, end_m)[1]
            q_end_d = date(target_y, end_m, max_d)

            # 若为当前季度且基准日在季度内，取基准日与季度末的较早值
            if offset == 0 and ref_date < q_end_d:
                end_d = ref_date
            else:
                end_d = q_end_d

            return MetricResolution(
                metric=INTERVAL_RETURN_METRIC,
                params={
                    "startDate": start_d.strftime("%Y%m%d"),
                    "endDate": end_d.strftime("%Y%m%d"),
                },
                strategy="INTERVAL_FALLBACK",
            )

        # 4. 固定季度 FIXED_QUARTER
        if time_hint.type == "FIXED_QUARTER":
            target_y = time_hint.year or ref_date.year
            target_q = time_hint.quarter or 1
            start_m = (target_q - 1) * 3 + 1
            end_m = target_q * 3
            start_d = date(target_y, start_m, 1)
            max_d = calendar.monthrange(target_y, end_m)[1]
            end_d = date(target_y, end_m, max_d)
            return MetricResolution(
                metric=INTERVAL_RETURN_METRIC,
                params={
                    "startDate": start_d.strftime("%Y%m%d"),
                    "endDate": end_d.strftime("%Y%m%d"),
                },
                strategy="INTERVAL_FALLBACK",
            )

        # 默认兜底
        return MetricResolution(
            metric=INTERVAL_RETURN_METRIC,
            params={"endDate": ref_date.strftime("%Y%m%d")},
            strategy="INTERVAL_FALLBACK",
        )
