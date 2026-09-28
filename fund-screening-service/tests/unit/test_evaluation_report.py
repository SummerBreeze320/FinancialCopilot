from app.evaluation.models import ErrorReason, EvaluationCase
from app.evaluation.report import classify_failure, render_report


def test_report_counts_top1_top3_and_error_reasons() -> None:
    report = render_report([
        EvaluationCase("f_return_3y", ["f_return_3y"]),
        EvaluationCase("f_info_existingyear", ["x", "f_info_existingyear"], "alias_missing"),
    ])
    assert report["top1_rate"] == 0.5
    assert report["top3_rate"] == 1.0
    assert report["error_reasons"]["alias_missing"] == 1


def test_classifies_recall_failure_into_actionable_governance_reason() -> None:
    case = EvaluationCase("f_return_3y", [], query="近三年收益", expected_alias="近三年收益")
    assert classify_failure(case) is ErrorReason.ALIAS_MISSING


def test_report_includes_change_backlog_by_reason() -> None:
    report = render_report([EvaluationCase("f_return_3y", [], query="近三年收益", expected_alias="近三年收益")])
    assert report["change_backlog"]["alias_missing"] == ["f_return_3y"]
