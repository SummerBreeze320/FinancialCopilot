from collections import Counter


def render_report(cases) -> dict:
    total = len(cases)
    top1 = sum(case.recalled_keys[:1] == [case.expected_key] for case in cases)
    top3 = sum(case.expected_key in case.recalled_keys[:3] for case in cases)
    return {"top1_rate": top1 / total if total else 0, "top3_rate": top3 / total if total else 0, "error_reasons": dict(Counter(case.error_reason for case in cases if case.error_reason))}
