from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Any, Protocol


class BusinessCalendarPort(Protocol):
    def latest_trade_date(self, as_of_date: date) -> date:
        ...

    def latest_report_date(self, as_of_date: date) -> date:
        ...


class DefaultBusinessCalendar:
    """默认简单日历：若未指定具体交易日源，使用工作日估算/季末估算作为 fallback"""

    def latest_trade_date(self, as_of_date: date) -> date:
        d = as_of_date
        while d.weekday() >= 5:
            d = d - timedelta(days=1)
        return d

    def latest_report_date(self, as_of_date: date) -> date:
        year = as_of_date.year
        month = as_of_date.month
        if month < 4:
            return date(year - 1, 12, 31)
        elif month < 7:
            return date(year, 3, 31)
        elif month < 10:
            return date(year, 6, 30)
        else:
            return date(year, 9, 30)


class UnresolvedDynamicDefaultError(ValueError):
    pass


class DynamicDefaultResolver:
    def __init__(self, calendar: Any | None = None) -> None:
        self._calendar = calendar or DefaultBusinessCalendar()

    def resolve(self, value: Any, as_of_date: date | str | None = None) -> str:
        if not isinstance(value, str):
            return value

        if not value.startswith("LATEST_"):
            return value

        ref_date: date
        if as_of_date is None:
            ref_date = date.today()
        elif isinstance(as_of_date, str):
            clean_str = as_of_date.replace("-", "")
            ref_date = datetime.strptime(clean_str, "%Y%m%d").date()
        elif isinstance(as_of_date, datetime):
            ref_date = as_of_date.date()
        else:
            ref_date = as_of_date

        if value == "LATEST_TRADE_DATE":
            resolved_d = self._calendar.latest_trade_date(ref_date)
            return resolved_d.strftime("%Y%m%d")
        elif value == "LATEST_REPORT_DATE":
            resolved_d = self._calendar.latest_report_date(ref_date)
            return resolved_d.strftime("%Y%m%d")
        else:
            raise UnresolvedDynamicDefaultError(f"Unknown dynamic date symbol: {value}")
