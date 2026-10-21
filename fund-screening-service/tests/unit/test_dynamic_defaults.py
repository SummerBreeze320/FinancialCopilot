from datetime import date
import pytest

from app.pipeline.dynamic_defaults import DynamicDefaultResolver, UnresolvedDynamicDefaultError


class FakeBusinessCalendar:
    def latest_trade_date(self, as_of_date: date) -> date:
        return date(2026, 9, 30)

    def latest_report_date(self, as_of_date: date) -> date:
        return date(2026, 6, 30)


def test_dynamic_default_resolves_to_yyyymmdd():
    resolver = DynamicDefaultResolver(FakeBusinessCalendar())
    assert resolver.resolve("LATEST_TRADE_DATE", date(2026, 9, 30)) == "20260930"
    assert resolver.resolve("LATEST_REPORT_DATE", date(2026, 9, 30)) == "20260630"


def test_unknown_dynamic_symbol_raises_error():
    resolver = DynamicDefaultResolver(FakeBusinessCalendar())
    with pytest.raises(UnresolvedDynamicDefaultError):
        resolver.resolve("LATEST_UNKNOWN_DATE", date(2026, 9, 30))


def test_passthrough_regular_dates():
    resolver = DynamicDefaultResolver(FakeBusinessCalendar())
    assert resolver.resolve("20250101", date(2026, 9, 30)) == "20250101"
