"""显式记忆提取与意图识别引擎

实现规范要求：
1. 触发词提取：用户说“记住”、“牢记”、“不要...”等关键词即刻落库。
2. 自动分类：归入 USER_PROFILE, USER_FEEDBACK, TASK_EPISODIC, RESOURCE_REFERENCE。
3. 冲突特征提取：提取主体、属性、取值，用于旧记忆冲突失效判定。
"""

import logging
import re
from datetime import datetime, timezone
from typing import Any

from app.models.schemas import MemoryCategory

logger = logging.getLogger(__name__)

# 显式记忆触发关键词与前缀模式
EXPLICIT_TRIGGER_PATTERNS = [
    r"(?:请|帮我)?记住[：:\s]*(.*)",
    r"(?:请|帮我)?记下[：:\s]*(.*)",
    r"牢记[：:\s]*(.*)",
    r"(?:我的)?规矩是[：:\s]*(.*)",
    r"(?:我的)?投资原则是[：:\s]*(.*)",
    r"以后(?:每次)?都?(?:要|别|不要)[：:\s]*(.*)",
]

# 自动分类规则特征词库
CATEGORY_RULES: list[tuple[MemoryCategory, list[str]]] = [
    (
        MemoryCategory.USER_FEEDBACK,
        [
            "不要", "别推荐", "禁止", "规矩", "原则", "必须", "务必", "每次回答",
            "格式", "字数", "附带", "限制", "反感", "千万别", "排除",
        ],
    ),
    (
        MemoryCategory.USER_PROFILE,
        [
            "偏好", "风格", "我是", "保守型", "稳健型", "进取型", "平衡型", "激进型",
            "风险承受", "投资经验", "关注板块", "持仓风格", "长线", "短线", "轮动",
        ],
    ),
    (
        MemoryCategory.TASK_EPISODIC,
        [
            "加仓", "减仓", "清仓", "买入", "卖出", "调仓", "决策", "看好",
            "因为", "情境", "大跌时", "大涨时", "复盘", "止损", "止盈", "动机",
        ],
    ),
    (
        MemoryCategory.RESOURCE_REFERENCE,
        [
            "基金代码", "股票代码", "ETF", "估值", "市盈率", "PB", "PE", "分位",
            "研报", "基准", "沪深300", "中证500", "标的", "追踪",
        ],
    ),
]


class ExplicitMemoryEngine:
    """显式记忆分析与意图检测"""

    def is_explicit_trigger(self, text: str) -> bool:
        """判断是否命中显式记忆触发词"""
        if not text:
            return False
        clean_text = text.strip()
        for pat in EXPLICIT_TRIGGER_PATTERNS:
            if re.search(pat, clean_text, re.IGNORECASE):
                return True
        # 常见强指令关键词
        strong_keywords = ["记住我", "记住：", "记住了", "帮我记住", "我的规矩", "牢记"]
        return any(kw in clean_text for kw in strong_keywords)

    def extract_statement(self, text: str) -> str:
        """从触发语句中剥离前缀，提取核心记忆事实"""
        clean_text = text.strip()
        for pat in EXPLICIT_TRIGGER_PATTERNS:
            m = re.search(pat, clean_text, re.IGNORECASE)
            if m and m.group(1).strip():
                return m.group(1).strip()
        
        # 兜底：去除常见前缀
        for prefix in ["请记住", "帮我记住", "记住我", "记住：", "记住:", "记住", "牢记"]:
            if clean_text.startswith(prefix):
                clean_text = clean_text[len(prefix):].lstrip("，,：: ")
        return clean_text

    def infer_category(self, statement: str) -> MemoryCategory:
        """根据语句内容自动推断所属四分类"""
        statement_lower = statement.lower()
        for cat, keywords in CATEGORY_RULES:
            for kw in keywords:
                if kw.lower() in statement_lower:
                    return cat
        return MemoryCategory.USER_PROFILE

    def extract_conflict_dimensions(self, statement: str, category: MemoryCategory) -> dict[str, str]:
        """提取用于冲突检测的语义槽位（主体与取值维度）"""
        dims: dict[str, str] = {}
        # 风险偏好维度
        for risk in ["保守", "稳健", "进取", "平衡", "激进"]:
            if risk in statement:
                dims["risk_level"] = risk

        # 投资期限与风格
        for style in ["长线", "短线", "超短", "定投", "轮动", "波段"]:
            if style in statement:
                dims["horizon"] = style

        # 负向限制与偏好标的
        if "不要" in statement or "别" in statement or "禁止" in statement or "排除" in statement:
            dims["sentiment"] = "NEGATIVE"
            # 提取否定对象
            for target in ["st", "创业板", "科创板", "微盘股", "高杠杆", "衍生品", "量化"]:
                if target in statement.lower():
                    dims[f"disallow_{target}"] = "true"
        elif "可以" in statement or "允许" in statement or "接受" in statement:
            dims["sentiment"] = "POSITIVE"
            for target in ["st", "创业板", "科创板", "微盘股", "高杠杆", "衍生品", "量化"]:
                if target in statement.lower():
                    dims[f"allow_{target}"] = "true"

        return dims


explicit_memory_engine = ExplicitMemoryEngine()
