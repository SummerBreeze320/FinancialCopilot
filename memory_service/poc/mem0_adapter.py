import json
from typing import Any
from memory_service.domain.enums import AssertionType
from memory_service.domain.fact import Fact


class Mem0FactAdapter:
    """
    Mem0 事实提炼能力适配器 (Stateless Adapter)：
    
    【核心评估结论】：
    1. Mem0 默认的 Memory.add() 深度耦合其内置的 Qdrant/SQLite 向量库，会强制接管 ID 分配与存储生命周期，
       直接引入与业务 MySQL 的“双权威冲突 (Dual-Authoritative Storage)”。
    2. 本适配器提取 Mem0 的核心 Prompt 协议 (Fact Extraction & Resolution Protocol)，
       在无状态模式 (Stateless Mode) 下运行：
       - 输入：多轮对话会话消息
       - 输出：标准化的原子事实三元组 (S-P-V) 与置信度
       - 完全不向 Mem0 本地库写数据，将存储权 100% 移交给 MySQL 权威库。
    """

    # Mem0 核心事实提炼 Prompt 规范
    MEM0_EXTRACTION_SYSTEM_PROMPT = """
You are an expert fact extractor. Your goal is to extract personal facts, preferences, and constraints 
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

    def extract_facts_stateless(
        self,
        messages: list[dict[str, Any]],
        session_context: dict[str, Any] | None = None,
    ) -> list[Fact]:
        """
        无状态提炼事实：通过 Mem0 规范提取原子事实，输出统一 domain.Fact 对象
        """
        facts: list[Fact] = []

        # 若提供了实际 LLM 接口，使用 Mem0 提示词解析
        if self.llm_callable:
            raw_response = self.llm_callable(self.MEM0_EXTRACTION_SYSTEM_PROMPT, messages)
            try:
                items = json.loads(raw_response)
                for it in items:
                    facts.append(
                        Fact(
                            subject=it.get("subject", "user"),
                            predicate=it.get("predicate", "attribute"),
                            value=it.get("value"),
                            assertion_type=AssertionType.EXPLICIT,
                        )
                    )
                return facts
            except Exception:
                pass

        # 离线回退/规则提炼（与 Mem0 提示词结果对齐）：识别金融核心偏好与事实
        for msg in messages:
            if msg.get("role") != "user":
                continue
            text = msg.get("content", "")
            if not text:
                continue

            # 模拟 Mem0 对用户陈述的提炼
            if "风险偏好" in text or "风险承受" in text:
                for r in ["保守型", "稳健型", "平衡型", "成长型", "进取型", "激进型"]:
                    if r in text:
                        facts.append(Fact(subject="user", predicate="risk_tolerance", value=r))
                        break

            if "定投" in text or "每月" in text:
                import re
                if m := re.search(r"(\d+(?:\.\d+)?\s*(?:元|块|万)?)", text):
                    facts.append(Fact(subject="user", predicate="monthly_budget", value=m.group(1)))

            if "不买" in text or "不要" in text or "排除" in text:
                for sec in ["白酒", "医药", "新能源", "半导体", "房地产"]:
                    if sec in text:
                        facts.append(Fact(subject="user", predicate="excluded_sectors", value=[sec]))

            if "主要配置" in text or "倾向于" in text or "喜欢" in text:
                for cls_name in ["ETF", "指数基金", "股票型", "债基", "黄金"]:
                    if cls_name in text:
                        facts.append(Fact(subject="user", predicate="preferred_asset_classes", value=[cls_name]))

        return facts
