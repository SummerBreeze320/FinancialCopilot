"""GPU 神经重排器 (Neural Cross-Encoder) 与金融业务加权引擎。

支持远程独立 Docker 重排微服务 (HTTP POST)、本地进程加载以及 RRF 倒数排名融合降级。
"""

import logging
import math
import re
from typing import Any, Dict, List, Optional, Set
import httpx

from app.config import settings

logger = logging.getLogger(__name__)

# 金融表格与关键指标检索意图关键词
TABLE_INTENT_KEYWORDS: Set[str] = {
    "财务",
    "指标",
    "出货量",
    "拆解",
    "表格",
    "数据",
    "营收",
    "毛利",
    "利润",
    "净利",
    "资产负债",
    "现金流",
    "市盈率",
    "pe",
    "pb",
    "roe",
    "同比",
    "环比",
}

# 6 位数字股票与基金代码正则
CODE_REGEX = re.compile(r"\b([0-9]{6})\b")


class NeuralReranker:
    """基于 Cross-Encoder 全注意力机制的精排器，融合金融领域确定性加权与 RRF 兜底。"""

    def __init__(
        self,
        model_name: Optional[str] = None,
        device: Optional[str] = None,
        backend: Optional[str] = None,
        service_url: Optional[str] = None,
        timeout: Optional[float] = None,
    ) -> None:
        """初始化重排器。

        :param model_name: 模型路径，默认使用配置的 bge-reranker-large
        :param device: 推理设备，默认 cuda 优先，无显卡自动降级为 cpu
        :param backend: 重排后端: 'service' (远程Docker服务), 'in_process' (本地显存加载), 'rrf' (纯算法)
        :param service_url: 远程 Docker 重排服务接口 URL (例如 http://127.0.0.1:8088/v1/rerank)
        :param timeout: 远程调用超时秒数
        """
        self.model_name = model_name or settings.reranker_model_path
        self.device = device or settings.reranker_device
        self.backend = backend or settings.reranker_backend
        self.service_url = (service_url or settings.reranker_service_url).rstrip("/")
        self.timeout = timeout or settings.reranker_timeout
        self._model = None

    def _ensure_model(self) -> Any:
        """延迟懒加载进程内重排模型。"""
        if self._model is None:
            try:
                from sentence_transformers import CrossEncoder
                import torch

                actual_device = "cuda" if torch.cuda.is_available() and self.device == "cuda" else "cpu"
                logger.info("加载 CrossEncoder 本地模型: %s, 设备: %s", self.model_name, actual_device)
                self._model = CrossEncoder(self.model_name, device=actual_device)
            except Exception as e:
                logger.warning("未能初始化本地 CrossEncoder 模型 (%s): %s", self.model_name, e)
                self._model = None
        return self._model

    def _score_via_service(self, query: str, texts: List[str]) -> List[float]:
        """通过远程 Docker 重排微服务计算语义相关性得分。"""
        payload = {
            "model": self.model_name,
            "query": query,
            "texts": texts,
        }
        with httpx.Client(timeout=self.timeout) as client:
            resp = client.post(self.service_url, json=payload)
            resp.raise_for_status()
            data = resp.json()

        # 支持列表返回格式 [{"index": 0, "score": 0.89}] 或字典包裹格式
        results_list = data if isinstance(data, list) else data.get("results") or data.get("data") or []
        scores_by_idx: Dict[int, float] = {}
        for idx, item in enumerate(results_list):
            item_idx = item.get("index", idx)
            score_val = item.get("score") or item.get("relevance_score") or 0.0
            scores_by_idx[item_idx] = float(score_val)

        return [scores_by_idx.get(i, 0.5) for i in range(len(texts))]

    def _score_in_process(self, query: str, texts: List[str]) -> List[float]:
        """批量在本地显存/CPU进程内执行预测。"""
        model = self._ensure_model()
        if model is None or not texts:
            return [0.5] * len(texts)

        pairs = [[query, text] for text in texts]
        raw_scores = model.predict(pairs)
        scores: List[float] = []
        for s in raw_scores:
            val = float(s)
            normalized = 1.0 / (1.0 + math.exp(-val)) if abs(val) < 20 else (1.0 if val > 0 else 0.0)
            scores.append(round(normalized, 4))
        return scores

    def _score_pairs(self, query: str, texts: List[str]) -> List[float]:
        """统一分发重排打分入口。"""
        if not texts:
            return []
        if self.backend == "service":
            return self._score_via_service(query, texts)
        return self._score_in_process(query, texts)

    def _extract_query_codes(self, query: str) -> Set[str]:
        """从用户查询词中提取 6 位证券或基金代码。"""
        return set(CODE_REGEX.findall(query))

    def _has_table_intent(self, query: str) -> bool:
        """判断查询词中是否含有表格/报表/数值拆解意图。"""
        q_lower = query.lower()
        return any(kw in q_lower for kw in TABLE_INTENT_KEYWORDS)

    def rerank(
        self,
        query: str,
        candidates: List[Dict[str, Any]],
        top_k: int = 5,
        score_threshold: float = 0.35,
    ) -> List[Dict[str, Any]]:
        """执行神经网络重排打分，并叠加金融业务规则加权。

        若远程微服务不可用，自动平滑降级为 RRF 倒数排名融合，保障系统高可用。
        """
        if not candidates:
            return []

        if self.backend == "rrf":
            return self.rerank_rrf(query, candidates, top_k)

        texts = [c.get("content", "") for c in candidates]

        try:
            base_scores = self._score_pairs(query, texts)
        except Exception as e:
            logger.warning(
                "远程重排服务或本地模型打分失败 (%s)，平滑降级为 RRF 倒数排名融合打分",
                e,
            )
            return self.rerank_rrf(query, candidates, top_k)

        query_codes = self._extract_query_codes(query)
        has_table_intent = self._has_table_intent(query)

        scored_candidates: List[Dict[str, Any]] = []

        for candidate, base_score in zip(candidates, base_scores):
            item = dict(candidate)
            final_score = base_score

            # 1. 代码硬匹配加权 (+0.15)
            c_codes = set(item.get("stock_codes") or []) | set(item.get("fund_codes") or [])
            meta = item.get("metadata") or {}
            c_codes |= set(meta.get("stock_codes") or []) | set(meta.get("fund_codes") or [])
            if query_codes and (query_codes & c_codes):
                final_score += 0.15

            # 2. 表格意图加权 (+0.10)
            has_table = bool(item.get("has_table") or meta.get("has_table"))
            if has_table_intent and has_table:
                final_score += 0.10

            final_score = round(final_score, 4)
            item["score"] = final_score
            item["base_rerank_score"] = base_score

            if final_score >= score_threshold:
                scored_candidates.append(item)

        # 降序排序并截取 Top-K
        scored_candidates.sort(key=lambda x: x["score"], reverse=True)
        return scored_candidates[:top_k]

    def rerank_rrf(
        self,
        query: str,
        candidates: List[Dict[str, Any]],
        top_k: int = 5,
        k_const: int = 60,
    ) -> List[Dict[str, Any]]:
        """Reciprocal Rank Fusion (RRF) 倒数排名融合算法（CPU 纯算法兜底）。"""
        scored_candidates: List[Dict[str, Any]] = []
        for candidate in candidates:
            item = dict(candidate)
            score = 0.0
            if "vector_rank" in item and item["vector_rank"] is not None:
                score += 1.0 / (k_const + item["vector_rank"])
            if "fulltext_rank" in item and item["fulltext_rank"] is not None:
                score += 1.0 / (k_const + item["fulltext_rank"])

            item["score"] = round(score, 6)
            scored_candidates.append(item)

        scored_candidates.sort(key=lambda x: x["score"], reverse=True)
        return scored_candidates[:top_k]
