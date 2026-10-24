from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from app.evaluation.schema import ExpectedCondition, GoldenCase
from app.models.candidate import CandidateContract


def generate_metric_cases(metrics: list[CandidateContract]) -> list[GoldenCase]:
    cases = []
    for m in metrics:
        name = m.name
        alias = m.aliases[0] if m.aliases else name
        keys = [m.key]

        # Determine realistic query and threshold
        if "收益" in name or "回报" in name:
            op_hint = ">"
            val_str = "10%"
            val = 10.0
            query = f"筛选{alias}大于{val_str}的基金"
            mention = f"{alias}大于{val_str}"
        elif "规模" in name:
            op_hint = ">"
            val_str = "10亿"
            val = 10.0
            query = f"筛选{alias}大于{val_str}的基金"
            mention = f"{alias}大于{val_str}"
        elif "年限" in name or "时间" in name or "年" in name:
            op_hint = ">"
            val_str = "3年"
            val = 3.0
            query = f"筛选{alias}大于{val_str}的基金"
            mention = f"{alias}大于{val_str}"
        elif "回撤" in name:
            op_hint = "<"
            val_str = "15%"
            val = 15.0
            query = f"筛选{alias}小于{val_str}的基金"
            mention = f"{alias}小于{val_str}"
        else:
            op_hint = ">"
            val_str = "0"
            val = 0.0
            query = f"筛选{alias}大于{val_str}的基金"
            mention = f"{alias}大于{val_str}"

        cases.append(
            GoldenCase(
                id=f"metric-{m.key}",
                category="metric",
                query=query,
                difficulty="clear",
                expected_outcome="DSL",
                expected_conditions=[
                    ExpectedCondition(
                        mention=mention,
                        domain="metric",
                        relevant_candidate_keys=keys,
                        primary_candidate_key=m.key,
                    )
                ],
                expected_dsl={
                    "version": "fund-screening/v1",
                    "operator": "AND",
                    "children": [
                        {
                            "metric": {
                                "operator": "AND",
                                "children": [
                                    {"metric": m.key, "operator": op_hint, "value": val}
                                ],
                            }
                        }
                    ],
                    "limit": 20,
                },
            )
        )
    return cases


def generate_sector_cases(sectors: list[CandidateContract], top_n: int = 100) -> list[GoldenCase]:
    cases = []
    for s in sectors[:top_n]:
        alias = s.aliases[0] if s.aliases else s.name
        cases.append(
            GoldenCase(
                id=f"sector-{s.key}",
                category="sector",
                query=f"筛选{alias}",
                difficulty="clear",
                expected_outcome="DSL",
                expected_conditions=[
                    ExpectedCondition(
                        mention=alias,
                        domain="scope",
                        relevant_candidate_keys=[s.key],
                        primary_candidate_key=s.key,
                    )
                ],
                expected_dsl={
                    "version": "fund-screening/v1",
                    "operator": "AND",
                    "children": [
                        {
                            "sector": {
                                "operator": "OR",
                                "children": [{"sectorId": s.key}],
                            }
                        }
                    ],
                    "limit": 20,
                },
            )
        )
    return cases


def generate_entity_cases(entities: list[CandidateContract], top_n: int = 100) -> list[GoldenCase]:
    cases = []
    for e in entities[:top_n]:
        query = e.name if e.name else e.key
        cases.append(
            GoldenCase(
                id=f"entity-{e.key}",
                category="entity",
                query=query,
                difficulty="clear",
                expected_outcome="DSL",
                expected_conditions=[
                    ExpectedCondition(
                        mention=query,
                        domain="entity",
                        relevant_candidate_keys=[e.key],
                        primary_candidate_key=e.key,
                    )
                ],
                expected_dsl={
                    "version": "fund-screening/v1",
                    "operator": "AND",
                    "children": [
                        {
                            "metric": {
                                "operator": "AND",
                                "children": [
                                    {"entity": e.key, "operator": "="}
                                ],
                            }
                        }
                    ],
                    "limit": 20,
                },
            )
        )
    return cases


def generate_scenario_cases() -> list[GoldenCase]:
    return [
        GoldenCase(
            id="scenario-vague-return-clarification",
            category="scenario",
            query="收益好的基金",
            difficulty="ambiguous",
            expected_outcome="CLARIFICATION",
            expected_conditions=[
                ExpectedCondition(
                    mention="收益好的",
                    domain="metric",
                    relevant_candidate_keys=["f_return_1y", "f_return_3y", "f_return_5y"],
                )
            ],
            expected_clarification={
                "code": "AMBIGUOUS_METRIC",
                "message": "请明确收益计算周期（例如近1年收益、近3年收益或年化收益率）",
            },
        ),
        GoldenCase(
            id="scenario-vague-risk-clarification",
            category="scenario",
            query="风险低的固收基金",
            difficulty="ambiguous",
            expected_outcome="CLARIFICATION",
            expected_conditions=[
                ExpectedCondition(
                    mention="风险低的",
                    domain="metric",
                    relevant_candidate_keys=["f_risk_maxdownside", "f_risk_stdev_1y"],
                )
            ],
            expected_clarification={
                "code": "AMBIGUOUS_METRIC",
                "message": "请明确风险指标类型（例如最大回撤或年化波动率）",
            },
        ),
        GoldenCase(
            id="scenario-multi-condition-and",
            category="scenario",
            query="规模大于10亿且成立年限大于3年，按近1年收益降序取前20只",
            difficulty="complex",
            expected_outcome="DSL",
            expected_conditions=[
                ExpectedCondition(
                    mention="规模大于10亿",
                    domain="metric",
                    relevant_candidate_keys=["f_info_fundscale"],
                    primary_candidate_key="f_info_fundscale",
                ),
                ExpectedCondition(
                    mention="成立年限大于3年",
                    domain="metric",
                    relevant_candidate_keys=["f_info_existingyear"],
                    primary_candidate_key="f_info_existingyear",
                ),
                ExpectedCondition(
                    mention="近1年收益",
                    domain="metric",
                    relevant_candidate_keys=["f_return_1y"],
                    primary_candidate_key="f_return_1y",
                ),
            ],
            expected_dsl={
                "version": "fund-screening/v1",
                "operator": "AND",
                "children": [
                    {
                        "metric": {
                            "operator": "AND",
                            "children": [
                                {"metric": "f_info_fundscale", "operator": ">", "value": 10.0, "unit": "亿"},
                                {"metric": "f_info_existingyear", "operator": ">", "value": 3.0, "unit": "年"},
                            ],
                        },
                        "sort": [{"metric": "f_return_1y", "direction": "DESC"}],
                        "limit": 20,
                    }
                ],
                "sort": [{"metric": "f_return_1y", "direction": "DESC"}],
                "limit": 20,
            },
        ),
        GoldenCase(
            id="scenario-negative-max-drawdown",
            category="scenario",
            query="最大回撤小于15%",
            difficulty="clear",
            expected_outcome="DSL",
            expected_conditions=[
                ExpectedCondition(
                    mention="最大回撤小于15%",
                    domain="metric",
                    relevant_candidate_keys=["f_risk_maxdownside"],
                    primary_candidate_key="f_risk_maxdownside",
                )
            ],
            expected_dsl={
                "version": "fund-screening/v1",
                "operator": "AND",
                "children": [
                    {
                        "metric": {
                            "operator": "AND",
                            "children": [
                                {"metric": "f_risk_maxdownside", "operator": ">=", "value": -15.0, "unit": "%"}
                            ],
                        }
                    }
                ],
                "limit": 20,
            },
        ),
        GoldenCase(
            id="scenario-date-interval-return",
            category="scenario",
            query="2024年以来区间收益率大于10%",
            difficulty="clear",
            expected_outcome="DSL",
            expected_conditions=[
                ExpectedCondition(
                    mention="2024年以来区间收益率大于10%",
                    domain="metric",
                    relevant_candidate_keys=["f_nav_adjustedreturn"],
                    primary_candidate_key="f_nav_adjustedreturn",
                )
            ],
            expected_dsl={
                "version": "fund-screening/v1",
                "operator": "AND",
                "children": [
                    {
                        "metric": {
                            "operator": "AND",
                            "children": [
                                {
                                    "metric": "f_nav_adjustedreturn",
                                    "operator": ">",
                                    "value": 10.0,
                                    "unit": "%",
                                    "params": [{"key": "startDate", "value": "20240101"}],
                                }
                            ],
                        }
                    }
                ],
                "limit": 20,
            },
        ),
    ]


def generate_composite_scenario_cases() -> list[GoldenCase]:
    """生成覆盖组合逻辑的投研问句骨架，供人工审核后作为金标基线。"""
    metrics = (
        ("f_info_fundscale", "基金规模", ">", 10.0, "亿"),
        ("f_info_existingyear", "成立年限", ">", 3.0, "年"),
        ("f_return_3y", "近3年回报", ">", 20.0, "%"),
        ("f_risk_maxdownside", "最大回撤", "<", 15.0, "%"),
    )
    scopes = (
        ("1000002533000000", "股票型基金"),
        ("1000002535000000", "偏股混合基金"),
        ("1000022713000000", "半导体行业基金"),
        ("1000019220000000", "全市场基金"),
    )
    entities = (
        ("161725.OF", "招商中证白酒指数"),
        ("manager:zhangkun", "张坤管理的基金"),
    )

    def condition(metric: tuple[str, str, str, float, str]) -> ExpectedCondition:
        key, name, operator, value, unit = metric
        wording = "小于" if operator == "<" else "大于"
        return ExpectedCondition(
            mention=f"{name}{wording}{value:g}{unit}",
            domain="metric",
            relevant_candidate_keys=[key],
            primary_candidate_key=key,
        )

    def predicate(metric: tuple[str, str, str, float, str]) -> dict[str, Any]:
        key, _, operator, value, unit = metric
        return {"metric": key, "operator": operator, "value": value, "unit": unit}

    def metric_group(*chosen: tuple[str, str, str, float, str]) -> dict[str, Any]:
        return {"metric": {"operator": "AND", "children": [predicate(item) for item in chosen]}}

    cases: list[GoldenCase] = []

    # 20 条多指标 AND：覆盖阈值、回撤、排序和 limit 的组合。
    for index in range(20):
        first, second = metrics[index % len(metrics)], metrics[(index + 1) % len(metrics)]
        first_text, second_text = condition(first).mention, condition(second).mention
        cases.append(GoldenCase(
            id=f"composite-metric-and-{index + 1:02d}", category="scenario",
            query=f"筛选{first_text}且{second_text}的基金，按近3年回报降序取前{10 + index % 3 * 10}只",
            difficulty="complex", expected_outcome="DSL",
            expected_conditions=[condition(first), condition(second), ExpectedCondition(
                mention="近3年回报", domain="metric", relevant_candidate_keys=["f_return_3y"], primary_candidate_key="f_return_3y",
            )],
            expected_dsl={"version": "fund-screening/v1", "operator": "AND", "children": [
                {**metric_group(first, second), "sort": [{"metric": "f_return_3y", "direction": "DESC"}], "limit": 10 + index % 3 * 10}
            ], "sort": [{"metric": "f_return_3y", "direction": "DESC"}], "limit": 10 + index % 3 * 10},
        ))

    # 20 条板块/类型 + 指标：验证跨域条件在同一业务组内结合。
    for index in range(20):
        scope_id, scope_name = scopes[index % len(scopes)]
        first, second = metrics[index % len(metrics)], metrics[(index + 2) % len(metrics)]
        cases.append(GoldenCase(
            id=f"composite-scope-metric-{index + 1:02d}", category="scenario",
            query=f"筛选{scope_name}中{condition(first).mention}且{condition(second).mention}的基金",
            difficulty="complex", expected_outcome="DSL",
            expected_conditions=[
                ExpectedCondition(mention=scope_name, domain="scope", relevant_candidate_keys=[scope_id], primary_candidate_key=scope_id),
                condition(first), condition(second),
            ],
            expected_dsl={"version": "fund-screening/v1", "operator": "AND", "children": [{
                "sector": {"operator": "OR", "children": [{"sectorId": scope_id}]}, **metric_group(first, second),
            }], "limit": 20},
        ))

    # 10 条 OR 与排除：验证多组和 NOT/排除语义均不会退化成扁平 AND。
    for index in range(10):
        left_id, left_name = scopes[index % len(scopes)]
        right_id, right_name = scopes[(index + 1) % len(scopes)]
        metric = metrics[index % len(metrics)]
        cases.append(GoldenCase(
            id=f"composite-scope-or-{index + 1:02d}", category="scenario",
            query=f"筛选{left_name}或者{right_name}，并要求{condition(metric).mention}，排除规模过小的基金",
            difficulty="complex", expected_outcome="DSL",
            expected_conditions=[
                ExpectedCondition(mention=left_name, domain="scope", relevant_candidate_keys=[left_id], primary_candidate_key=left_id),
                ExpectedCondition(mention=right_name, domain="scope", relevant_candidate_keys=[right_id], primary_candidate_key=right_id),
                condition(metric), condition(metrics[0]),
            ],
            expected_dsl={"version": "fund-screening/v1", "operator": "OR", "children": [
                {"sector": {"operator": "OR", "children": [{"sectorId": left_id}]}, **metric_group(metric)},
                {"sector": {"operator": "OR", "children": [{"sectorId": right_id}]}, **metric_group(metric)},
            ], "limit": 20},
        ))

    # 10 条实体 + 指标：验证实体、指标、排序的混合召回与绑定。
    for index in range(10):
        entity_id, entity_name = entities[index % len(entities)]
        metric = metrics[index % len(metrics)]
        cases.append(GoldenCase(
            id=f"composite-entity-metric-{index + 1:02d}", category="scenario",
            query=f"在{entity_name}相关基金中，筛选{condition(metric).mention}，按近3年回报降序",
            difficulty="complex", expected_outcome="DSL",
            expected_conditions=[
                ExpectedCondition(mention=entity_name, domain="entity", relevant_candidate_keys=[entity_id], primary_candidate_key=entity_id),
                condition(metric), ExpectedCondition(mention="近3年回报", domain="metric", relevant_candidate_keys=["f_return_3y"], primary_candidate_key="f_return_3y"),
            ],
            expected_dsl={"version": "fund-screening/v1", "operator": "AND", "children": [{
                "metric": {"operator": "AND", "children": [
                    {"entity": entity_id, "operator": "="}, predicate(metric),
                ]}, "sort": [{"metric": "f_return_3y", "direction": "DESC"}],
            }], "sort": [{"metric": "f_return_3y", "direction": "DESC"}], "limit": 20},
        ))

    # 10 条局部澄清与 10 条多轮编辑。它们保留可确定条件，不允许模型隐式猜测模糊叶子。
    for index in range(10):
        scope_id, scope_name = scopes[index % len(scopes)]
        fixed = metrics[index % len(metrics)]
        cases.append(GoldenCase(
            id=f"composite-partial-clarification-{index + 1:02d}", category="scenario",
            query=f"筛选{scope_name}中{condition(fixed).mention}且收益好的基金",
            difficulty="ambiguous", expected_outcome="CLARIFICATION",
            expected_conditions=[
                ExpectedCondition(mention=scope_name, domain="scope", relevant_candidate_keys=[scope_id], primary_candidate_key=scope_id),
                condition(fixed), ExpectedCondition(mention="收益好", domain="metric", relevant_candidate_keys=["f_return_3y"]),
            ],
            expected_clarification={"code": "AMBIGUOUS_METRIC", "blockedConditionIds": ["收益好"]},
        ))
    for index in range(10):
        previous, replacement = metrics[index % len(metrics)], metrics[(index + 1) % len(metrics)]
        cases.append(GoldenCase(
            id=f"composite-rebuild-{index + 1:02d}", category="scenario",
            query=f"上一轮保留{condition(previous).mention}，把另一个条件替换为{condition(replacement).mention}，并改成前20只",
            difficulty="complex", expected_outcome="DSL",
            expected_conditions=[condition(previous), condition(replacement)],
            expected_dsl={"version": "fund-screening/v1", "operator": "AND", "children": [metric_group(previous, replacement)], "limit": 20},
        ))

    return cases


def export_cases_to_jsonl(cases: list[GoldenCase], target_path: Path) -> None:
    target_path.parent.mkdir(parents=True, exist_ok=True)
    with open(target_path, "w", encoding="utf-8") as f:
        for c in cases:
            f.write(c.model_dump_json(by_alias=True, exclude_none=True) + "\n")
