from app.evaluation.models import EvaluationCase
from app.evaluation.report import render_report


def test_report_counts_top1_top3_and_error_reasons() -> None:
    report = render_report([
        EvaluationCase("f_return_3y", ["f_return_3y"]),
        EvaluationCase("f_info_existingyear", ["x", "f_info_existingyear"], "alias_missing"),
    ])
    assert report["top1_rate"] == 0.5
    assert report["top3_rate"] == 1.0
    assert report["error_reasons"]["alias_missing"] == 1
