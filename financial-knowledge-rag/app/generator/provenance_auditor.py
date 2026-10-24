"""生成器全链路事实溯源与忠诚性/事实性双轨幻觉审计器。"""

import logging
import re
from typing import Any, Dict, List, Optional, Set, Tuple
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)

# 匹配句末或句中引用标注，如 [1], [2], [chunk_xxx]
CITATION_REGEX = re.compile(r"\[(\d+)\]")

# 匹配关键财务数值与百分比，如 31.17%, 922.78, 150GWh
METRIC_REGEX = re.compile(r"\b\d+(?:\.\d+)?%?|\d+(?:\.\d+)?(?:亿元|GWh|元/Wh|万)?")


class ClaimVerificationDTO(BaseModel):
    """单个事实断言的证据溯源与忠诚度审计结果。"""

    claim_text: str = Field(..., description="回答中的单句事实断言")
    cited_chunk_id: Optional[str] = Field(default=None, description="引用的候选切片ID")
    exact_quote: Optional[str] = Field(default=None, description="在切片中匹配到的精确原文证据")
    hallucination_type: str = Field(
        default="NONE",
        description="NONE(忠实), FAITHFULNESS_HALLUCINATION(忠诚性幻觉-无据编造), FACTUALITY_CONFLICT(事实冲突-与法定事实相左)",
    )
    is_faithful: bool = Field(default=True, description="该断言是否严格忠实于所给知识切片")
    confidence: float = Field(default=1.0, description="断言可信度得分")


class ProvenanceReportDTO(BaseModel):
    """全量回答的事实溯源与幻觉审计报告。"""

    faithfulness_score: float = Field(..., description="忠诚度得分 (0.0~1.0)，忠实断言占比")
    total_claims: int = Field(default=0, description="抽取的事实断言总数")
    faithful_claims_count: int = Field(default=0, description="严格忠实的断言数量")
    claims: List[ClaimVerificationDTO] = Field(default_factory=list, description="逐句断言的校验明细")
    warnings: List[str] = Field(default_factory=list, description="事实冲突或幻觉预警")


class ProvenanceAuditor:
    """断言级事实溯源审计器，精准区分【忠诚性幻觉】与【事实性幻觉】。"""

    def __init__(self) -> None:
        pass

    def extract_claims(self, text: str) -> List[Tuple[str, Optional[int]]]:
        """将生成文本拆解为独立断言句子，并解析其附带的引用角标编号。"""
        # 按中英文句号、感叹号、分号或换行分句
        raw_sentences = re.split(r"(?<=[。！？；\n])", text)
        claims: List[Tuple[str, Optional[int]]] = []

        for s in raw_sentences:
            cleaned = s.strip()
            # 过滤过短句子或标题行
            if len(cleaned) < 5 or cleaned.startswith("#") or cleaned.startswith("###"):
                continue

            # 提取第一个引用角标 [1]
            citation_match = CITATION_REGEX.search(cleaned)
            citation_idx = int(citation_match.group(1)) if citation_match else None

            # 剔除角标后的纯文本断言
            pure_claim = CITATION_REGEX.sub("", cleaned).strip()
            if pure_claim:
                claims.append((pure_claim, citation_idx))

        return claims

    def _extract_numbers(self, text: str) -> Set[str]:
        """提取文本中出现的关键财务数字、浮点数与百分比。"""
        raw_nums = re.findall(r"\d+(?:\.\d+)?%?", text)
        # 过滤单一无意义数字
        return {n for n in raw_nums if n not in ["0", "1", "2", "3", "4", "5"]}

    def audit_claim(
        self,
        claim_text: str,
        chunk: Optional[Dict[str, Any]],
    ) -> ClaimVerificationDTO:
        """针对单个断言校验其是否忠实于对应切片。"""
        if chunk is None:
            # 引用了不存在的切片，或完全未标注引用
            return ClaimVerificationDTO(
                claim_text=claim_text,
                cited_chunk_id=None,
                exact_quote=None,
                hallucination_type="FAITHFULNESS_HALLUCINATION",
                is_faithful=False,
                confidence=0.0,
            )

        chunk_id = chunk.get("chunk_id") or chunk.get("id") or "unknown"
        content = chunk.get("content", "")

        claim_numbers = self._extract_numbers(claim_text)
        chunk_numbers = self._extract_numbers(content)

        # 核心判定：若断言中出现具体数字，但该数字在切片中完全不存在，定性为【忠诚性幻觉】
        missing_numbers = [n for n in claim_numbers if n not in chunk_numbers and n not in content]
        if missing_numbers:
            logger.warning(
                "检测到忠诚性幻觉: 断言数值 %s 在切片 (%s) 中完全不存在！",
                missing_numbers,
                chunk_id,
            )
            return ClaimVerificationDTO(
                claim_text=claim_text,
                cited_chunk_id=chunk_id,
                exact_quote=None,
                hallucination_type="FAITHFULNESS_HALLUCINATION",
                is_faithful=False,
                confidence=0.0,
            )

        # 尝试在切片中寻找最相近的原文支撑片段
        matched_quote = None
        for line in content.splitlines():
            line_str = line.strip()
            if any(n in line_str for n in claim_numbers):
                matched_quote = line_str
                break

        return ClaimVerificationDTO(
            claim_text=claim_text,
            cited_chunk_id=chunk_id,
            exact_quote=matched_quote or (content[:100] + "..."),
            hallucination_type="NONE",
            is_faithful=True,
            confidence=1.0,
        )

    def _check_factuality_conflicts(
        self,
        chunks: List[Dict[str, Any]],
    ) -> List[str]:
        """检查切片池中是否存在观点预测 (OPINION) 与法定真实财报 (GROUND_TRUTH) 的事实冲突。"""
        warnings: List[str] = []
        has_opinion = any(c.get("cognitive_layer") == "OPINION" for c in chunks)
        has_truth = any(c.get("cognitive_layer") == "GROUND_TRUTH" for c in chunks)

        if has_opinion and has_truth:
            warnings.append(
                "[Factuality Conflict] 提示：检索上下文中同时存在观点层预测研报 (OPINION) "
                "与法定事实层定期报告 (GROUND_TRUTH)，请核实回答引述的预测数据是否已被法定披露事实覆盖。"
            )

        return warnings

    def audit_response(
        self,
        generated_text: str,
        chunks: List[Dict[str, Any]],
    ) -> ProvenanceReportDTO:
        """执行全量生成回答的事实溯源与双轨幻觉审计。"""
        claims_with_idx = self.extract_claims(generated_text)
        if not claims_with_idx:
            return ProvenanceReportDTO(
                faithfulness_score=1.0,
                total_claims=0,
                faithful_claims_count=0,
                claims=[],
                warnings=[],
            )

        chunk_map = {idx: c for idx, c in enumerate(chunks, start=1)}
        verified_claims: List[ClaimVerificationDTO] = []

        faithful_count = 0
        for claim_text, cite_idx in claims_with_idx:
            target_chunk = chunk_map.get(cite_idx) if cite_idx else (chunks[0] if chunks else None)
            res = self.audit_claim(claim_text, target_chunk)
            verified_claims.append(res)
            if res.is_faithful:
                faithful_count += 1

        total_claims = len(verified_claims)
        faithfulness_score = round(faithful_count / total_claims, 4) if total_claims > 0 else 1.0

        warnings = self._check_factuality_conflicts(chunks)
        if faithfulness_score < 1.0:
            warnings.append(
                f"[Faithfulness Warning] 检测到 {total_claims - faithful_count} 处断言脱离了参考切片上下文，"
                "存在忠诚性幻觉风险。"
            )

        return ProvenanceReportDTO(
            faithfulness_score=faithfulness_score,
            total_claims=total_claims,
            faithful_claims_count=faithful_count,
            claims=verified_claims,
            warnings=warnings,
        )
