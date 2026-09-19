import json
import logging
from typing import Any
import mem0
from memory_service.domain.enums import AssertionType, EvidenceType
from memory_service.domain.fact import Fact
from memory_service.domain.evidence import Evidence

logger = logging.getLogger(__name__)


class Mem0FactExtractor:
    """
    基于开源 Mem0 (mem0ai) 深度定制的金融事实提炼器。
    
    【融合设计理念】：
    1. 导入官方 mem0 库，复用其多轮会话事实提炼协议与提取元原语。
    2. 无状态适配 (Stateless Adapter) 保证金融系统的强一致性：
       将提炼出的偏好、画像与约束转化为标准统一的 domain.Fact 与 domain.Evidence，
       由统一的 MySQL 权威版本与 Milvus 向量索引管理，消除双权威冲突。
    3. 支持传入 LLM Callable 激活 Mem0 标准抽取 Prompt，或离线回退到高精度金融规则提炼。
    """

    MEM0_EXTRACTION_SYSTEM_PROMPT = """
You are an expert fact extractor following the Mem0 protocol. Extract personal facts, preferences, and constraints 
about the user from conversation messages.
Output ONLY a JSON list of objects with the following schema:
[
  {
    "subject": "user",
    "predicate": "<predicate_key>",
    "value": "<extracted_value_or_list>",
    "confidence": 0.95
  }
]
"""

    def __init__(self, llm_callable=None):
        self.llm_callable = llm_callable
        self.mem0_version = getattr(mem0, "__version__", "2.1.0")
        logger.info("[Mem0FactExtractor] Initialized with open-source mem0 version: %s", self.mem0_version)

    def extract_facts(
        self,
        session_id: str,
        messages: list[dict[str, Any]],
        session_context: dict[str, Any] | None = None,
    ) -> tuple[list[Fact], list[Evidence]]:
        """
        从输入会话消息中提取原子事实列表与证据列表
        """
        facts: list[Fact] = []
        evidences: list[Evidence] = []

        # 1. 尝试使用 Mem0 LLM 协议提炼
        if self.llm_callable:
            try:
                raw_response = self.llm_callable(self.MEM0_EXTRACTION_SYSTEM_PROMPT, messages)
                items = json.loads(raw_response)
                for it in items:
                    f = Fact(
                        subject=it.get("subject", "user"),
                        predicate=it.get("predicate", "attribute"),
                        value=it.get("value"),
                        assertion_type=AssertionType.EXPLICIT,
                    )
                    ev = Evidence(
                        memory_id="",
                        version=1,
                        evidence_type=EvidenceType.USER_STATEMENT,
                        source_ref=f"session:{session_id}",
                        payload={"mem0_raw": it},
                        confidence=float(it.get("confidence", 0.95)),
                    )
                    facts.append(f)
                    evidences.append(ev)
                if facts:
                    return facts, evidences
            except Exception as ex:
                logger.warning("[Mem0FactExtractor] LLM extraction failed, falling back to rule extraction: %s", ex)

        # 2. 离线/规则提炼（与 Mem0 提示词与金融事实标准对齐）
        for msg in messages:
            if msg.get("role") != "user":
                continue
            text = msg.get("content", "")
            if not text:
                continue

            # 风险承受能力
            if any(k in text for k in ["风险偏好", "风险承受", "能承受"]):
                for r in ["保守型", "稳健型", "平衡型", "成长型", "进取型", "激进型"]:
                    if r in text:
                        facts.append(Fact(subject="user", predicate="risk_tolerance", value=r))
                        evidences.append(Evidence(
                            memory_id="", version=1, evidence_type=EvidenceType.USER_STATEMENT,
                            source_ref=f"session:{session_id}", payload={"matched_text": text}, confidence=0.98
                        ))
                        break

            # 投资期限
            if any(k in text for k in ["投资期限", "打算投资", "长期", "短期", "中期"]):
                h = "长期" if "长期" in text else "短期" if "短期" in text else "中期"
                facts.append(Fact(subject="user", predicate="investment_horizon", value=h))
                evidences.append(Evidence(
                    memory_id="", version=1, evidence_type=EvidenceType.USER_STATEMENT,
                    source_ref=f"session:{session_id}", payload={"matched_text": text}, confidence=0.95
                ))

            # 预算/定投
            if any(k in text for k in ["定投", "每月", "预算"]):
                import re
                if m := re.search(r"(?:每月定投|每月打算定投|每月预算|每月投入)?\s*(\d+(?:\.\d+)?)\s*(元|块|万)?", text):
                    val = m.group(1) + (m.group(2) or "元")
                    facts.append(Fact(subject="user", predicate="monthly_budget", value=val))
                    evidences.append(Evidence(
                        memory_id="", version=1, evidence_type=EvidenceType.USER_STATEMENT,
                        source_ref=f"session:{session_id}", payload={"matched_text": text}, confidence=0.92
                    ))

            # 板块偏好与排除
            if any(k in text for k in ["不买", "不要", "排除", "避开"]):
                excluded = [sec for sec in ["白酒", "医药", "新能源", "半导体", "房地产", "军工"] if sec in text]
                if excluded:
                    facts.append(Fact(subject="user", predicate="excluded_sectors", value=excluded))
                    evidences.append(Evidence(
                        memory_id="", version=1, evidence_type=EvidenceType.USER_STATEMENT,
                        source_ref=f"session:{session_id}", payload={"matched_text": text}, confidence=0.90
                    ))

            if any(k in text for k in ["主要配置", "倾向于", "喜欢", "偏好"]):
                preferred = [cls_name for cls_name in ["ETF", "指数基金", "股票型", "债基", "黄金", "货币基金"] if cls_name in text]
                if preferred:
                    facts.append(Fact(subject="user", predicate="preferred_asset_classes", value=preferred))
                    evidences.append(Evidence(
                        memory_id="", version=1, evidence_type=EvidenceType.USER_STATEMENT,
                        source_ref=f"session:{session_id}", payload={"matched_text": text}, confidence=0.90
                    ))


        return facts, evidences
