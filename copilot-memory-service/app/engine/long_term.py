"""长期记忆引擎 — Graphiti 知识图谱 + 4分类体系 + 显式写入 + 冲突失效 + 多因子衰减检索 + 遗忘整理"""

import logging
import math
import uuid
from datetime import datetime, timezone, timedelta
from typing import Any

from graphiti_core.nodes import EpisodeType

from app.core.graphiti_init import get_graphiti
from app.models.schemas import (
    FactResult,
    MemorySearchResponse,
    MemoryCategory,
    ExplicitMemoryResponse,
    MemoryConsolidateResponse,
    MemoryPruneResponse,
)
from app.engine.explicit_memory import explicit_memory_engine

logger = logging.getLogger(__name__)

# 金融同义词与泛化词典（支持隔天测试/同义词表达泛化召回）
FINANCIAL_SYNONYMS: dict[str, list[str]] = {
    "微盘": ["微盘股", "小盘股", "小微盘", "微盘", "市值极小"],
    "创业板": ["创业板", "cyb", "双创"],
    "医药": ["医药", "医疗", "生物医药", "创新药", "医疗etf"],
    "稳健": ["稳健", "防守", "低风险", "保守", "低回撤"],
    "进取": ["进取", "成长", "高弹性", "激进", "高风险"],
    "决策": ["决策", "买入", "加仓", "建仓", "调仓", "理由", "动机"],
    "规则": ["规则", "规矩", "约束", "不要", "禁止", "限制", "偏好"],
    "基金": ["基金", "etf", "净值", "标的", "代码"],
}


class LongTermMemoryEngine:
    def __init__(self):
        self.last_namespace: str | None = None
        # 结构化事实内存库: fact_uuid -> dict
        self._facts_store: dict[str, dict[str, Any]] = {}
        # 用户事实索引: user_id -> list[fact_uuid]
        self._user_facts_index: dict[str, list[str]] = {}

    def _normalize_time(self, dt: datetime | None) -> datetime:
        if dt is None:
            return datetime.now(timezone.utc)
        if dt.tzinfo is None:
            return dt.replace(tzinfo=timezone.utc)
        return dt

    async def add_explicit_memory(
        self,
        user_id: str,
        content: str,
        category: str | MemoryCategory | None = None,
        scenario: str | None = None,
        reason: str | None = None,
        importance: float = 0.8,
        session_id: str | None = None,
        reference_time: datetime | None = None,
    ) -> ExplicitMemoryResponse:
        """
        显式记忆写入：
        1. 提取核心陈述与四分类；
        2. 冲突检测：命中同维度矛盾的旧记忆自动标记 invalid_at = now()；
        3. 新记忆落库 valid_at = now()；
        4. 同步至 Graphiti 时序图谱。
        """
        ref_time = self._normalize_time(reference_time)
        clean_fact = explicit_memory_engine.extract_statement(content)

        # 确定四分类
        if category is not None:
            cat_enum = MemoryCategory(category) if isinstance(category, str) else category
        else:
            cat_enum = explicit_memory_engine.infer_category(clean_fact)

        cat_str = cat_enum.value

        # 提取冲突维度特征
        new_dims = explicit_memory_engine.extract_conflict_dimensions(clean_fact, cat_enum)

        # 冲突检测：扫描该用户同分类现有有效事实
        invalidated_uuids: list[str] = []
        user_fact_ids = self._user_facts_index.get(user_id, [])

        for fid in user_fact_ids:
            existing = self._facts_store.get(fid)
            if not existing or existing.get("invalid_at") is not None:
                continue

            # 仅在同分类下检测冲突
            if existing.get("category") != cat_str:
                continue

            is_conflict = self._detect_conflict(existing, clean_fact, new_dims)
            if is_conflict:
                existing["invalid_at"] = ref_time
                invalidated_uuids.append(fid)
                logger.info(
                    "Conflict detected: invalidating fact %s ('%s') due to new fact ('%s')",
                    fid,
                    existing["fact"],
                    clean_fact,
                )

        # 生成新事实
        new_uuid = str(uuid.uuid4())
        fact_record = {
            "uuid": new_uuid,
            "user_id": user_id,
            "category": cat_str,
            "fact": clean_fact,
            "scenario": scenario,
            "reason": reason,
            "importance": float(importance),
            "valid_at": ref_time,
            "invalid_at": None,
            "last_accessed_at": ref_time,
            "access_count": 0,
            "dimensions": new_dims,
            "session_id": session_id,
        }

        self._facts_store[new_uuid] = fact_record
        if user_id not in self._user_facts_index:
            self._user_facts_index[user_id] = []
        self._user_facts_index[user_id].append(new_uuid)

        # 写入 Graphiti Episode 进行异步索引图谱
        try:
            g = get_graphiti()
            await g.add_episode(
                name=f"explicit:{user_id}:{cat_str}:{new_uuid[:8]}",
                episode_body=f"[{cat_str}] {clean_fact}"
                + (f" (情景: {scenario})" if scenario else "")
                + (f" (理由: {reason})" if reason else ""),
                source=EpisodeType.text,
                source_description=f"explicit memory create: {cat_str}",
                reference_time=ref_time,
                group_id=f"user:{user_id}",
            )
        except Exception as e:
            logger.warning("Graphiti episode write fallback (non-blocking): %s", e)

        logger.info(
            "Explicit memory created for user %s [cat=%s, id=%s], invalidated %d old facts",
            user_id,
            cat_str,
            new_uuid,
            len(invalidated_uuids),
        )

        return ExplicitMemoryResponse(
            uuid=new_uuid,
            fact=clean_fact,
            category=cat_str,
            importance=float(importance),
            valid_at=ref_time,
            invalidated_uuids=invalidated_uuids,
        )

    def _detect_conflict(
        self,
        existing: dict[str, Any],
        new_fact: str,
        new_dims: dict[str, str],
    ) -> bool:
        """冲突判定规则：风险偏好互斥、限制与放开互斥、直接语义矛盾"""
        old_dims = existing.get("dimensions", {})

        # 1. 风险偏好维度互斥判定 (保守 vs 进取/激进)
        if "risk_level" in old_dims and "risk_level" in new_dims:
            if old_dims["risk_level"] != new_dims["risk_level"]:
                return True

        # 2. 投资权限或限制互斥判定 (不要推荐X vs 可以买X)
        for k, v in new_dims.items():
            if k.startswith("allow_"):
                disallow_key = k.replace("allow_", "disallow_")
                if old_dims.get(disallow_key) == "true":
                    return True
            elif k.startswith("disallow_"):
                allow_key = k.replace("disallow_", "allow_")
                if old_dims.get(allow_key) == "true":
                    return True

        # 3. 语义否定与反转判断
        old_fact = existing.get("fact", "")
        # 如果新事实明确指出“改变/不再”
        if ("不再" in new_fact or "不用" in new_fact or "取消" in new_fact) and any(
            w in old_fact for w in ["喜欢", "偏好", "关注", "主要"]
        ):
            return True

        return False

    async def search(
        self,
        user_id_or_query: str | None = None,
        scope: str = "USER",
        query: str = "",
        max_results: int = 10,
        center_node_uuid: str | None = None,
        conversation_id: str | None = None,
        user_id: str | None = None,
        category: str | None = None,
        w_semantic: float = 0.5,
        w_recency: float = 0.3,
        w_importance: float = 0.2,
        half_life_days: float = 30.0,
        include_invalid: bool = False,
        reference_time: datetime | None = None,
    ) -> MemorySearchResponse:
        """
        多因子混合检索与重排：
        Score = w_sim * S_semantic + w_rec * S_recency + w_imp * S_importance
        - 支持时效衰减 (半衰期模型)
        - 支持同义词泛化 (隔天测试)
        - 支持四分类过滤
        """
        # 兼容传参
        if user_id is None and scope in ("USER", "GLOBAL_MARKET", "CONVERSATION") and query:
            actual_user_id = user_id_or_query
            actual_query = query
        elif user_id is not None:
            actual_user_id = user_id
            actual_query = query or user_id_or_query or ""
        else:
            actual_user_id = None
            actual_query = user_id_or_query or query or ""

        scope_str = (scope or "USER").upper()
        if scope_str == "GLOBAL_MARKET":
            namespace = "global:market"
        elif scope_str == "CONVERSATION":
            cid = conversation_id or "default"
            namespace = f"user:{actual_user_id}:conversation:{cid}" if actual_user_id else f"conversation:{cid}"
        else:
            namespace = f"user:{actual_user_id}" if actual_user_id else "global:market"

        self.last_namespace = namespace
        ref_time = self._normalize_time(reference_time)

        # 候选集收集
        candidate_facts: list[dict[str, Any]] = []

        # 1. 从内存事实库获取对应用户事实
        if actual_user_id and actual_user_id in self._user_facts_index:
            for fid in self._user_facts_index[actual_user_id]:
                fact_obj = self._facts_store.get(fid)
                if fact_obj:
                    candidate_facts.append(fact_obj)
        elif not actual_user_id:
            candidate_facts.extend(self._facts_store.values())

        # 2. 从 Graphiti 检索兜底
        graphiti_scores: dict[str, float] = {}
        try:
            g = get_graphiti()
            g_results = await g.search(
                actual_query,
                max_results=max_results * 2,
                center_node_uuid=center_node_uuid,
            )
            for gr in g_results:
                fact_str = getattr(gr, "fact", "")
                gid = str(getattr(gr, "uuid", ""))
                graphiti_scores[fact_str] = 0.8
                # 如果事实库里没有，补充为临时候选
                if not any(c["fact"] == fact_str for c in candidate_facts):
                    candidate_facts.append({
                        "uuid": gid,
                        "user_id": actual_user_id or "default",
                        "category": MemoryCategory.USER_PROFILE.value,
                        "fact": fact_str,
                        "scenario": None,
                        "reason": None,
                        "importance": 0.5,
                        "valid_at": getattr(gr, "valid_at", ref_time),
                        "invalid_at": getattr(gr, "invalid_at", None),
                        "last_accessed_at": ref_time,
                        "access_count": 0,
                    })
        except Exception as e:
            logger.warning("Graphiti search call fallback: %s", e)

        # 3. 过滤与多因子打分
        lambda_decay = math.log(2) / max(1.0, half_life_days * 86400.0)
        scored_facts: list[tuple[float, FactResult, dict[str, Any]]] = []

        for f in candidate_facts:
            # 分类过滤
            if category and f.get("category") != category:
                continue

            valid_at = self._normalize_time(f.get("valid_at"))
            invalid_at = f.get("invalid_at")
            if invalid_at is not None:
                invalid_at = self._normalize_time(invalid_at)

            # 有效性过滤
            if not include_invalid:
                if valid_at > ref_time:
                    continue
                if invalid_at is not None and invalid_at <= ref_time:
                    continue

            # a. 语义相似度得分 S_semantic
            s_semantic = self._calculate_similarity(actual_query, f["fact"])
            if f["fact"] in graphiti_scores:
                s_semantic = max(s_semantic, graphiti_scores[f["fact"]])

            # b. 时间衰减得分 S_recency
            delta_sec = max(0.0, (ref_time - valid_at).total_seconds())
            s_recency = math.exp(-lambda_decay * delta_sec)

            # c. 重要性得分 S_importance
            s_importance = min(1.0, max(0.0, float(f.get("importance", 0.5))))

            # 综合重排总分
            final_score = (
                w_semantic * s_semantic
                + w_recency * s_recency
                + w_importance * s_importance
            )

            fact_res = FactResult(
                uuid=f["uuid"],
                fact=f["fact"],
                category=f.get("category", MemoryCategory.USER_PROFILE.value),
                importance=s_importance,
                scenario=f.get("scenario"),
                reason=f.get("reason"),
                valid_at=valid_at,
                invalid_at=invalid_at,
                last_accessed_at=f.get("last_accessed_at"),
                access_count=f.get("access_count", 0),
                score=round(final_score, 4),
                similarity=round(s_semantic, 4),
                recency_score=round(s_recency, 4),
                importance_score=round(s_importance, 4),
            )
            scored_facts.append((final_score, fact_res, f))

        # 按总得分倒序排序
        scored_facts.sort(key=lambda x: x[0], reverse=True)
        top_results = scored_facts[:max_results]

        # 更新被召回事实的访问统计
        results: list[FactResult] = []
        for _, fres, raw_f in top_results:
            raw_f["access_count"] = raw_f.get("access_count", 0) + 1
            raw_f["last_accessed_at"] = ref_time
            fres.access_count = raw_f["access_count"]
            fres.last_accessed_at = ref_time
            results.append(fres)

        logger.info(
            "Long-term search '%s' (cat=%s, ns=%s) → %d facts matched",
            actual_query[:40],
            category,
            namespace,
            len(results),
        )
        return MemorySearchResponse(results=results, total=len(results))

    def _calculate_similarity(self, query: str, fact: str) -> float:
        """多重相似度计算：包含精确匹配、字词交集、金融同义词泛化匹配"""
        if not query or not fact:
            return 0.1

        q = query.lower()
        f = fact.lower()

        # 包含匹配
        if q in f:
            return 0.95
        if f in q:
            return 0.85

        # 同义词与概念匹配（如微盘股 <=> 小盘股，医药 <=> 医疗）
        synonym_hit = False
        for _, group in FINANCIAL_SYNONYMS.items():
            q_has = any(term in q for term in group)
            f_has = any(term in f for term in group)
            if q_has and f_has:
                synonym_hit = True
                break

        # 字符集合交集 (Jaccard) 与包含比
        q_chars = set(q)
        f_chars = set(f)
        common = q_chars & f_chars
        jaccard = len(common) / max(1, len(q_chars | f_chars))
        containment = len(common) / max(1, min(len(q_chars), len(f_chars)))

        score = max(jaccard, containment * 0.85)
        if synonym_hit:
            score = max(score, 0.78)
            if containment >= 0.6:
                score = max(score, 0.88)

        return min(1.0, max(0.05, score))

    async def search_by_time(
        self,
        query: str,
        at_time: datetime,
        max_results: int = 10,
        user_id: str | None = None,
    ) -> MemorySearchResponse:
        """时点回溯查询：返回在指定时间点有效的事实"""
        return await self.search(
            query=query,
            user_id=user_id,
            max_results=max_results,
            reference_time=at_time,
            include_invalid=False,
        )

    async def consolidate(
        self,
        user_id: str,
        category: str | None = None,
    ) -> MemoryConsolidateResponse:
        """
        后台定期记忆整理：
        1. 扫描指定用户的记忆；
        2. 归并相同类型的记忆；
        3. 对高度相似的重复记忆去重并提升重要性。
        """
        user_fact_ids = self._user_facts_index.get(user_id, [])
        active_facts = [
            self._facts_store[fid]
            for fid in user_fact_ids
            if fid in self._facts_store
            and self._facts_store[fid].get("invalid_at") is None
            and (category is None or self._facts_store[fid].get("category") == category)
        ]

        now = datetime.now(timezone.utc)
        merged_count = 0
        dedup_count = 0

        # 按事实相似度进行去重与提炼
        for i in range(len(active_facts)):
            f1 = active_facts[i]
            if f1.get("invalid_at") is not None:
                continue

            for j in range(i + 1, len(active_facts)):
                f2 = active_facts[j]
                if f2.get("invalid_at") is not None:
                    continue

                if f1.get("category") != f2.get("category"):
                    continue

                sim = self._calculate_similarity(f1["fact"], f2["fact"])
                if sim >= 0.75:
                    # 发现高度重合记忆：将较旧的标为失效，将较新的重要性适当提高
                    f2["invalid_at"] = now
                    f1["importance"] = min(1.0, round(float(f1.get("importance", 0.5)) + 0.1, 4))
                    dedup_count += 1
                    merged_count += 1

        logger.info(
            "Consolidated memory for user %s: processed=%d, dedup=%d",
            user_id,
            len(active_facts),
            dedup_count,
        )
        return MemoryConsolidateResponse(
            user_id=user_id,
            processed_count=len(active_facts),
            merged_count=merged_count,
            deduplicated_count=dedup_count,
        )

    async def prune(
        self,
        user_id: str | None = None,
        max_idle_days: int = 90,
        min_importance: float = 0.3,
        prune_invalidated: bool = True,
    ) -> MemoryPruneResponse:
        """
        清理遗忘机制：
        1. 长期未检索修剪 (cold memories)
        2. 被新事实推翻修剪 (invalidated memories)
        3. 低价值记忆修剪 (low-importance memories)
        """
        now = datetime.now(timezone.utc)
        idle_threshold = now - timedelta(days=max_idle_days)

        uuids_to_check = (
            self._user_facts_index.get(user_id, [])
            if user_id
            else list(self._facts_store.keys())
        )

        p_invalid = 0
        p_cold = 0
        p_low = 0
        pruned_uuids = set()

        for fid in list(uuids_to_check):
            fact = self._facts_store.get(fid)
            if not fact:
                continue

            # 1. 已被推翻的失效记忆
            if prune_invalidated and fact.get("invalid_at") is not None:
                pruned_uuids.add(fid)
                p_invalid += 1
                continue

            # 2. 长期未访问冷记忆 (access_count == 0 且 超过idle天数)
            last_access = self._normalize_time(fact.get("last_accessed_at"))
            if fact.get("access_count", 0) == 0 and last_access < idle_threshold:
                pruned_uuids.add(fid)
                p_cold += 1
                continue

            # 3. 低价值记忆 (低于指定重要性阈值且非高频访问)
            if float(fact.get("importance", 0.5)) < min_importance and fact.get("access_count", 0) <= 1:
                pruned_uuids.add(fid)
                p_low += 1
                continue

        # 执行修剪
        for pid in pruned_uuids:
            self._facts_store.pop(pid, None)

        if user_id and user_id in self._user_facts_index:
            self._user_facts_index[user_id] = [
                fid for fid in self._user_facts_index[user_id] if fid not in pruned_uuids
            ]

        logger.info(
            "Pruned memory: total=%d (invalid=%d, cold=%d, low_imp=%d)",
            len(pruned_uuids),
            p_invalid,
            p_cold,
            p_low,
        )
        return MemoryPruneResponse(
            pruned_count=len(pruned_uuids),
            pruned_invalidated=p_invalid,
            pruned_cold=p_cold,
            pruned_low_importance=p_low,
        )

    async def forget(
        self,
        user_id: str | None = None,
        fact_uuid: str | None = None,
        category: str | None = None,
        query: str | None = None,
    ) -> int:
        """精准或条件遗忘"""
        cleared = 0
        if fact_uuid and fact_uuid in self._facts_store:
            self._facts_store.pop(fact_uuid, None)
            cleared += 1
            return cleared

        targets = (
            self._user_facts_index.get(user_id, [])
            if user_id
            else list(self._facts_store.keys())
        )

        to_remove = []
        for fid in targets:
            fact = self._facts_store.get(fid)
            if not fact:
                continue
            if category and fact.get("category") != category:
                continue
            if query and query.lower() not in fact.get("fact", "").lower():
                continue
            to_remove.append(fid)

        for fid in to_remove:
            self._facts_store.pop(fid, None)
            cleared += 1

        if user_id and user_id in self._user_facts_index:
            self._user_facts_index[user_id] = [
                fid for fid in self._user_facts_index[user_id] if fid not in to_remove
            ]

        return cleared


long_term_engine = LongTermMemoryEngine()
