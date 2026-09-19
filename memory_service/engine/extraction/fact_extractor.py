import re
from typing import Any
from memory_service.domain.enums import AssertionType, EvidenceType
from memory_service.domain.fact import Fact
from memory_service.domain.evidence import Evidence
from memory_service.engine.extraction.mem0_extractor import Mem0FactExtractor


class FactExtractor:
    """
    事实与偏好提炼器：
    从输入会话数据中提炼原子事实 (Fact) 与支持证据 (Evidence)。
    深度集成开源 Mem0 (mem0ai) 提取协议与金融专精规则。
    """

    def __init__(self, mem0_extractor: Mem0FactExtractor | None = None):
        self.mem0_extractor = mem0_extractor or Mem0FactExtractor()


    FINANCIAL_PATTERNS = [
        {
            "predicate": "risk_tolerance",
            "regex": r"(?:我的风险承受能力是|风险偏好|偏好保守|稳健型|进取型|激进型|保守型|平衡型)",
            "extractor": lambda text: next(
                (p for p in ["保守型", "稳健型", "平衡型", "成长型", "进取型", "激进型"] if p in text),
                "稳健型"
            ),
            "assertion_type": AssertionType.EXPLICIT,
        },
        {
            "predicate": "investment_horizon",
            "regex": r"(?:投资期限|打算投资|持有时间|短期|中期|长期|\d+\s*(?:年|个月|天))",
            "extractor": lambda text: (
                m.group(0) if (m := re.search(r"\d+\s*(?:年|个月|天)", text))
                else ("长期" if "长期" in text else "短期" if "短期" in text else "中期")
            ),
            "assertion_type": AssertionType.EXPLICIT,
        },
        {
            "predicate": "monthly_budget",
            "regex": r"(?:每月定投|每月打算定投|每月预算|每月投入)\s*(\d+(?:\.\d+)?)\s*(?:元|块|k|万)?",
            "extractor": lambda text: (
                (m.group(1) + (m.group(2) or "元"))
                if (m := re.search(r"(?:每月定投|每月打算定投|每月预算|每月投入)\s*(\d+(?:\.\d+)?)\s*(元|块|万)?", text))
                else "未指定"
            ),
            "assertion_type": AssertionType.EXPLICIT,
        },
        {
            "predicate": "excluded_sectors",
            "regex": r"(?:不买|不要|避开|排除|不考虑)\s*([^\s,，。]+(?:行业|板块|股票|基金|白酒|医药|新能源|半导体))",
            "extractor": lambda text: [
                p for p in ["白酒", "医药", "新能源", "半导体", "房地产", "军工"] if p in text
            ],
            "assertion_type": AssertionType.EXPLICIT,
        },
        {
            "predicate": "preferred_asset_classes",
            "regex": r"(?:喜欢|倾向于|主要配置|重点看)\s*([^\s,，。]+(?:债基|指数基金|ETF|股票型|混合型|黄金))",
            "extractor": lambda text: [
                p for p in ["债基", "指数基金", "ETF", "股票型", "混合型", "黄金", "货币基金"] if p in text
            ],
            "assertion_type": AssertionType.EXPLICIT,
        },
    ]

    def extract_from_session(
        self,
        session_id: str,
        user_id: str,
        session_data: dict[str, Any],
    ) -> tuple[list[Fact], list[Evidence]]:
        """
        从会话输入中提取事实列表与证据列表
        """
        facts: list[Fact] = []
        evidences: list[Evidence] = []

        # 1. 检查是否有上游已识别的原始事实候选 (例如 Mem0 / LLM extraction 输出)
        raw_candidates = session_data.get("extracted_facts", [])
        if raw_candidates:
            for item in raw_candidates:
                val = item.get("value", item.get("object_value"))
                a_type_str = item.get("assertion_type", "EXPLICIT")
                a_type = AssertionType.INFERRED if a_type_str == "INFERRED" else AssertionType.EXPLICIT
                fact = Fact(
                    subject=item.get("subject", "user"),
                    predicate=item.get("predicate", "attribute"),
                    value=val,
                    assertion_type=a_type,
                )
                evidence = Evidence(
                    memory_id="",
                    version=1,
                    evidence_type=EvidenceType.USER_STATEMENT,
                    source_ref=f"session:{session_id}",
                    payload={"raw": item},
                    confidence=float(item.get("confidence", 0.9)),
                )
                facts.append(fact)
                evidences.append(evidence)

        # 2. 从会话文本消息或 context 列表中根据 Mem0 与金融领域模式提炼
        messages = session_data.get("messages", [])
        if not messages and "context" in session_data:
            ctx = session_data["context"]
            if isinstance(ctx, list):
                for item in ctx:
                    if isinstance(item, str):
                        if item.startswith("USER:"):
                            messages.append({"role": "user", "content": item[5:].strip()})
                        elif item.startswith("ASSISTANT:"):
                            messages.append({"role": "assistant", "content": item[10:].strip()})
                        else:
                            messages.append({"role": "user", "content": item.strip()})

        # 调用 Mem0 提炼器
        mem0_facts, mem0_evidences = self.mem0_extractor.extract_facts(session_id, messages, session_data)
        for mf, me in zip(mem0_facts, mem0_evidences):
            if not any(f.predicate == mf.predicate for f in facts):
                facts.append(mf)
                evidences.append(me)

        for msg_idx, msg in enumerate(messages):

            if msg.get("role") != "user":
                continue
            text = msg.get("content", "")
            if not text:
                continue

            for pattern_spec in self.FINANCIAL_PATTERNS:
                if re.search(pattern_spec["regex"], text):
                    extracted_val = pattern_spec["extractor"](text)
                    if not extracted_val or extracted_val == []:
                        continue

                    predicate = pattern_spec["predicate"]
                    if any(f.predicate == predicate for f in facts):
                        continue

                    fact = Fact(
                        subject="user",
                        predicate=predicate,
                        value=extracted_val,
                        assertion_type=pattern_spec["assertion_type"],
                    )
                    evidence = Evidence(
                        memory_id="",
                        version=1,
                        evidence_type=EvidenceType.USER_STATEMENT,
                        source_ref=f"session:{session_id}#msg_{msg_idx}",
                        payload={"user_message": text, "pattern": pattern_spec["regex"]},
                        confidence=0.85,
                    )
                    facts.append(fact)
                    evidences.append(evidence)

        return facts, evidences
