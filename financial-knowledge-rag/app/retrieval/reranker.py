"""GPU 神经重排器 (Neural Cross-Encoder) 与金融业务加权引擎。"""

import os
os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"

import logging
import math
import re
from typing import Any, Dict, List, Optional, Set

try:
    import torch
    from sentence_transformers import CrossEncoder
    TORCH_AVAILABLE = True
except Exception as _torch_err:
    torch = None
    CrossEncoder = None
    TORCH_AVAILABLE = False

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
    "份额",
    "总额",
    "重仓",
    "持仓",
    "前十",
    "前五",
    "前三",
    "变动",
    "明细",
    "费率",
    "管理费",
    "托管费",
    "比例",
    "资产配置",
    "公允价值",
    "组合",
    "股票投资",
    "净值",
    "规模",
}

# 6 位数字股票与基金代码正则
CODE_REGEX = re.compile(r"\b([0-9]{6})\b")


class NeuralReranker:
    """基于 Cross-Encoder 全注意力机制的精排器，融合金融领域确定性加权与 RRF 兜底。"""

    def __init__(
        self,
        model_name: Optional[str] = None,
        device: Optional[str] = None,
    ) -> None:
        """初始化重排器。

        :param model_name: HuggingFace 或本地模型路径，默认使用配置的 bge-reranker-large
        :param device: 推理设备，默认 cuda 优先，无显卡自动降级为 cpu
        """
        self.model_name = model_name or settings.reranker_model_path
        self.device = device or settings.reranker_device
        self._model = None

    def _ensure_model(self) -> Any:
        """延迟懒加载重排模型，支持 ModelScope 自动镜像与 NVIDIA GPU 显卡加速。"""
        if self._model is None:
            if not TORCH_AVAILABLE:
                logger.warning("当前环境未安装 PyTorch 或 sentence-transformers，降级为中立重排")
                return None
            try:
                actual_device = "cuda" if torch.cuda.is_available() and self.device == "cuda" else "cpu"
                model_path = self.model_name
                
                # 若为远程模型名，优先直接匹配 ModelScope 本地完整缓存，避免任何网络请求
                clean_id = self.model_name.replace('/', '--')
                ms_dir = os.path.expanduser(f"~/.cache/modelscope/models/{clean_id}/snapshots/master")
                if os.path.exists(os.path.join(ms_dir, "model.safetensors")):
                    model_path = ms_dir
                elif not os.path.exists(model_path):
                    try:
                        from modelscope import snapshot_download
                        ms_path = snapshot_download(
                            self.model_name,
                            allow_file_pattern=["*.json", "*.safetensors", "*.txt", "*sentencepiece*", "tokenizer*"]
                        )
                        if os.path.exists(ms_path):
                            model_path = ms_path
                    except Exception as ms_err:
                        logger.debug("ModelScope 路径检查: %s", ms_err)

                gpu_name = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "None"
                logger.info("🔥 [NeuralReranker] 加载 CrossEncoder: %s (硬件: %s, GPU: %s)", model_path, actual_device, gpu_name)
                self._model = CrossEncoder(model_path, max_length=512, device=actual_device)
                logger.info("✅ [NeuralReranker] CrossEncoder 重排模型成功加载至 %s！", actual_device)
            except Exception as e:
                import traceback
                logger.error("❌ [NeuralReranker] 初始化模型失败: %s\n%s", e, traceback.format_exc())
                self._model = None
        return self._model

    def _score_pairs(self, query: str, texts: List[str]) -> List[float]:
        """批量预测 Query 与各切片文本对的原始语义相似度得分 (带长度安全截断，防止 BERT 位置越界崩溃)。"""
        model = self._ensure_model()
        if model is None or not texts:
            return [0.5] * len(texts)

        # 严格截断文本长度至 480 字符，规避 RoBERTa/BERT 512 最大位置编码的越界段错误
        clean_query = query[:128].strip()
        safe_pairs = [[clean_query, (str(t)[:480] if len(str(t or '')) > 480 else str(t or ''))] for t in texts]
        
        try:
            raw_scores = model.predict(safe_pairs, batch_size=16)
        except Exception as e:
            logger.warning("CrossEncoder 预测推理异常，安全回退为中立得分: %s", e)
            return [0.5] * len(texts)

        # 将原始 Logit 分数通过 Sigmoid 归一化到 [0, 1]
        scores: List[float] = []
        for s in raw_scores:
            val = float(s)
            normalized = 1.0 / (1.0 + math.exp(-val)) if abs(val) < 20 else (1.0 if val > 0 else 0.0)
            scores.append(round(normalized, 4))
        return scores

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

        加权规则：
        1. 股票/基金代码硬匹配：若查询词包含代码且切片元数据中完全命中，加权 +0.15；
        2. 表格数据意图加权：若查询词命中指标表格词且切片 has_table 为 True，加权 +0.10；
        3. 阈值截断：过滤低于 score_threshold 的无关切片。
        """
        if not candidates:
            return []

        texts = [c.get("content", "") for c in candidates]
        base_scores = self._score_pairs(query, texts)

        query_codes = self._extract_query_codes(query)
        has_table_intent = self._has_table_intent(query)

        scored_candidates: List[Dict[str, Any]] = []

        for candidate, base_score in zip(candidates, base_scores):
            item = dict(candidate)
            final_score = base_score

            # 1. 代码硬匹配加权 (+0.15)
            c_codes = set(item.get("stock_codes") or []) | set(item.get("fund_codes") or [])
            meta = item.get("metadata") or {}
            if isinstance(meta, str):
                try:
                    import json
                    meta = json.loads(meta)
                except Exception:
                    meta = {}
            item["metadata"] = meta
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
        top_results = scored_candidates[:top_k]

        # 3. 表格保底路由：若查询具备明确的表格/指标/明细意图，而 Top-K 均为纯文本，尝试调入候选集最优表格切片保底
        if has_table_intent and top_results and not any(x.get("has_table") for x in top_results):
            table_candidates = [c for c in scored_candidates[top_k:] if c.get("has_table")]
            if table_candidates:
                top_results[-1] = table_candidates[0]

        return top_results

    def rerank_rrf(
        self,
        query: str,
        candidates: List[Dict[str, Any]],
        top_k: int = 5,
        k_const: int = 60,
    ) -> List[Dict[str, Any]]:
        """Reciprocal Rank Fusion (RRF) 倒数排名融合算法（CPU 纯算法兜底）。"""
        has_table_intent = self._has_table_intent(query)
        scored_candidates: List[Dict[str, Any]] = []
        for candidate in candidates:
            item = dict(candidate)
            score = 0.0
            if "vector_rank" in item and item["vector_rank"] is not None:
                score += 1.0 / (k_const + item["vector_rank"])
            if "fulltext_rank" in item and item["fulltext_rank"] is not None:
                score += 1.0 / (k_const + item["fulltext_rank"])

            # 若具备表格意图且切片为表格，叠加轻微 RRF 加权
            if has_table_intent and (item.get("has_table") or (item.get("metadata") or {}).get("has_table")):
                score *= 1.25

            item["score"] = round(score, 6)
            scored_candidates.append(item)

        scored_candidates.sort(key=lambda x: x["score"], reverse=True)
        top_results = scored_candidates[:top_k]

        if has_table_intent and top_results and not any(x.get("has_table") for x in top_results):
            table_candidates = [c for c in scored_candidates[top_k:] if c.get("has_table")]
            if table_candidates:
                top_results[-1] = table_candidates[0]

        return top_results
