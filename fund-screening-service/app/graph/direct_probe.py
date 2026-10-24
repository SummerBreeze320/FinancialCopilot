from __future__ import annotations

import re
from typing import Sequence
from app.models.candidate import CandidateContract
from app.models.dsl import FundScreeningDsl, BusinessGroup, SectorExpression, SectorLeaf
from app.models.dsl_nodes import MetricLogicNode, MetricPredicate


DEFAULT_SECTOR_ID = "1000019220000000"


def probe_strict_direct(
    query: str,
    candidates: Sequence[CandidateContract],
    previous_dsl: dict | None = None,
) -> tuple[bool, FundScreeningDsl | None, str]:
    """严格判定是否可跳过所有生成式 LLM 直接构建 DSL。"""
    clean_q = query.strip()

    # 0. 如果存在上一轮 DSL，检测是否为可直接 patch 的简单编辑（0 LLM）
    if previous_dsl:
        from app.pipeline.editor import classify_edit, apply_simple_edit, EditKind
        kind = classify_edit(clean_q, previous_dsl)
        if kind == EditKind.SIMPLE:
            edited_dsl = apply_simple_edit(FundScreeningDsl.model_validate(previous_dsl), clean_q)
            return True, edited_dsl, "SIMPLE_EDIT_DIRECT"

    # 1. 纯基金代码判定 (001938 / 001938.OF)
    if re.fullmatch(r"\d{6}(\.[A-Za-z]+)?", clean_q):
        code_key = clean_q if "." in clean_q else f"{clean_q}.OF"
        dsl = FundScreeningDsl(
            version="fund-screening/v1",
            operator="AND",
            children=[
                BusinessGroup(
                    sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId=DEFAULT_SECTOR_ID)]),
                    metric=MetricLogicNode(
                        operator="AND",
                        children=[
                            MetricPredicate(
                                operator="=",
                                metric="f_info_windcode",
                                value=code_key,
                                unit="ENTITY_REF",
                                params=[],
                            )
                        ],
                    ),
                    sort=[],
                    limit=20,
                )
            ],
            sort=[],
            limit=20,
        )
        return True, dsl, "EXACT_FUND_CODE"

    # 2. 精确单个实体匹配且无操作符修饰 (例如仅输入 "易方达蓝筹精选" 或全名，以及 "易方达管理的基金", "重仓沪电股份的基金", "跟踪沪深300的基金")
    has_operator = any(op in clean_q for op in (">", "<", "=", "大于", "小于", "超过", "低于", "高于"))
    if not has_operator:
        from app.catalog.defaults import DEFAULT_ENTITIES
        candidate_entities = [c for c in candidates if c.domain == "entity"]
        combined_entities = list(candidate_entities) + [
            e for e in DEFAULT_ENTITIES if not any(c.key == e.key for c in candidate_entities)
        ]

        target_entity = None
        exact_matches = [
            c for c in combined_entities
            if (c.name == clean_q or clean_q in c.aliases)
        ]
        if len(exact_matches) == 1:
            target_entity = exact_matches[0]
        else:
            pat = re.fullmatch(r"(?:重仓|跟踪)?(.+?)(?:管理|旗下|重仓|跟踪)?的?基金", clean_q)
            if pat:
                ent_name = pat.group(1).strip()
                pat_matches = [
                    c for c in combined_entities
                    if (c.name == ent_name or ent_name in c.aliases)
                ]
                if len(pat_matches) == 1:
                    target_entity = pat_matches[0]

        if target_entity and target_entity.preferred_metric:
            params = [{"role": target_entity.binding_role}] if target_entity.binding_role else []
            dsl = FundScreeningDsl(
                version="fund-screening/v1",
                operator="AND",
                children=[
                    BusinessGroup(
                        sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId=DEFAULT_SECTOR_ID)]),
                        metric=MetricLogicNode(
                            operator="AND",
                            children=[
                                MetricPredicate(
                                    operator="=",
                                    metric=target_entity.preferred_metric,
                                    value=target_entity.key,
                                    unit=target_entity.value_type,
                                    params=params,
                                )
                            ],
                        ),
                        sort=[],
                        limit=20,
                    )
                ],
                sort=[],
                limit=20,
            )
            return True, dsl, "EXACT_ENTITY_MATCH"

    # 3. 复杂逻辑判定 (OR / NOT)
    if any(k in clean_q for k in ("或者", "或", "OR", "排除", "不是", "NOT", "且（", "且(")):
        return False, None, "OR_OR_NOT_DETECTED"

    # 3.1 连词与多条件判定
    if any(k in clean_q for k in ("且", "与", "、")):
        return False, None, "LOGICAL_CONNECTORS_DETECTED"

    # 4. 模糊或排序主观词检测
    if any(k in clean_q for k in ("好", "优", "低风险", "稳健", "高收益", "排行", "前十", "前10", "TOP", "top", "排名")):
        return False, None, "FUZZY_OR_RANKING_TERM_DETECTED"

    # 5. 多分句判定
    if len(re.split(r"[;；\n]+", clean_q)) > 1:
        return False, None, "MULTI_SEGMENT_DETECTED"

    # 5. 单指标严格表达式判定 (例如 "规模大于10亿", "成立超过5年")
    strict_metric_match = re.fullmatch(
        r"([^\d><=大于小于超过低于高于\s]+)\s*(>=|<=|>|<|=|大于等于|小于等于|大于|小于|超过|低于|高于)\s*(\d+(?:\.\d+)?)\s*(亿|万|年|%|元)?",
        clean_q,
    )
    if strict_metric_match:
        metric_name = strict_metric_match.group(1).strip()
        op_str = strict_metric_match.group(2).strip()
        val_str = strict_metric_match.group(3).strip()
        unit_str = strict_metric_match.group(4) or ""

        op_map = {
            ">": ">", ">=": ">=", "<": "<", "<=": "<=", "=": "=",
            "大于": ">", "大于等于": ">=", "超过": ">", "高于": ">",
            "小于": "<", "小于等于": "<=", "低于": "<",
        }
        std_op = op_map.get(op_str, "=")
        val = float(val_str) if "." in val_str else int(val_str)

        from app.catalog.defaults import DEFAULT_METRICS
        combined_candidates = list(candidates) + [
            m for m in DEFAULT_METRICS if not any(c.key == m.key for c in candidates)
        ]
        matching_metrics = [
            c for c in combined_candidates
            if c.domain == "metric" and (c.name == metric_name or metric_name in c.aliases or c.key == metric_name)
        ]
        if len(matching_metrics) == 1:
            metric_cand = matching_metrics[0]
            if not metric_cand.supported_operators or std_op in metric_cand.supported_operators:
                dsl = FundScreeningDsl(
                    version="fund-screening/v1",
                    operator="AND",
                    children=[
                        BusinessGroup(
                            sector=SectorExpression(operator="OR", children=[SectorLeaf(sectorId=DEFAULT_SECTOR_ID)]),
                            metric=MetricLogicNode(
                                operator="AND",
                                children=[
                                    MetricPredicate(
                                        operator=std_op,
                                        metric=metric_cand.key,
                                        value=val,
                                        unit=unit_str or metric_cand.value_type or "NUMBER",
                                        params=[],
                                    )
                                ],
                            ),
                            sort=[],
                            limit=20,
                        )
                    ],
                    sort=[],
                    limit=20,
                )
                return True, dsl, "STRICT_SINGLE_METRIC"

    return False, None, "REQUIRES_SEMANTIC_PLAN"
