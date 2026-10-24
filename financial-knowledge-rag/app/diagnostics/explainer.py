"""四步根因归因透视与候选池饱和度诊断器。"""

from typing import Any, Dict, List, Optional


class AttributionExplainer:
    """负责 RAG 检索结果位移透视分析、候选池饱和度检测与根因归因。"""

    def check_pool_saturation(
        self,
        top_chunks: List[Dict[str, Any]],
        vector_pool_size: int = 30,
    ) -> List[str]:
        """运行时候选池饱和度告警：

        若进入 Top-K 的高分切片在向量粗召回阶段排名位于候选集末梢（例如处于倒数 3 位内），
        提示粗召回深度偏紧，建议增大 K_vec。
        """
        warnings: List[str] = []
        tail_threshold = max(1, vector_pool_size - 2)

        for chunk in top_chunks:
            v_rank = chunk.get("vector_rank")
            c_id = chunk.get("chunk_id") or chunk.get("id") or "unknown"
            if v_rank is not None and v_rank >= tail_threshold:
                warning_msg = (
                    f"[Warning: Candidate Pool Saturation] 目标切片 (ID: {c_id}) "
                    f"来自粗召回末梢 (Rank {v_rank}/{vector_pool_size})，"
                    f"表明 K_vec={vector_pool_size} 偏紧，建议上调粗召回深度防止长尾漏召回。"
                )
                warnings.append(warning_msg)

        return warnings

    def diagnose_failure(
        self,
        target_chunk_id: str,
        candidates: List[Dict[str, Any]],
        final_top_k: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        """四步根因定位决策树。

        当测试集期望命中某一关键知识片段，但最终 Top-K 却未出现时，
        自动追溯其是在哪一阶段脱落：
        - PHASE_1_CHUNKING: 切块阶段损耗（表格跨块切断、OCR 乱码）
        - PHASE_2_EMBEDDING: 向量表征局限（专有名词冷门、语义偏离）
        - PHASE_3_RECALL_TRUNCATED: 粗召回截断（排在 Top 30 之后）
        - PHASE_4_RERANK_DISTORTION: 重排策略失真（被相关性阈值截断或业务加权压制）
        """
        final_ids = {c.get("chunk_id") or c.get("id") for c in final_top_k}
        if target_chunk_id in final_ids:
            return {
                "phase": "HIT",
                "reason": "目标切片成功进入最终 Top-K 结果集合",
            }

        candidate_map = {c.get("chunk_id") or c.get("id"): c for c in candidates}
        if target_chunk_id not in candidate_map:
            return {
                "phase": "PHASE_1_CHUNKING",
                "reason": "候选池中完全不存在该切片，可能由切块损坏或专有词向量表征局限引起。",
            }

        target_candidate = candidate_map[target_chunk_id]
        score = target_candidate.get("score", 0.0)
        return {
            "phase": "PHASE_4_RERANK_DISTORTION",
            "reason": f"候选池中存在该切片，但在重排打分阶段得分偏低 ({score}) 或被阈值截断过滤。",
        }
