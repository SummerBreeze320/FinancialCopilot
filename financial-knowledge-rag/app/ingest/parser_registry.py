"""解析器注册中心与格式分发工厂。

统一管理多格式文档解析器，并提供自动标签推断 (Auto-Tagger)。
"""

from pathlib import Path
from typing import Dict, List, Optional
from app.api.models import CognitiveLayer
from app.ingest.splitter import ParsedChunk, TableAwareSplitter
from app.ingest.markdown_parser import MarkdownParser
from app.ingest.excel_parser import ExcelParser
from app.ingest.docx_parser import DocxParser
from app.ingest.pdf_parser import PdfParser


class ParserRegistry:
    """文档解析器注册与自动分发中枢。"""

    def __init__(self, splitter: Optional[TableAwareSplitter] = None, ocr_engine: Optional[object] = None):
        self.splitter = splitter or TableAwareSplitter()
        self.ocr_engine = ocr_engine

        self._md_parser = MarkdownParser(self.splitter)
        self._excel_parser = ExcelParser(self.splitter)
        self._docx_parser = DocxParser(self.splitter)
        self._pdf_parser = PdfParser(self.splitter, ocr_engine=self.ocr_engine)

        self._parsers: Dict[str, object] = {
            ".md": self._md_parser,
            ".markdown": self._md_parser,
            ".txt": self._md_parser,
            ".json": self._md_parser,
            ".xlsx": self._excel_parser,
            ".xls": self._excel_parser,
            ".docx": self._docx_parser,
            ".pdf": self._pdf_parser,
        }

    def get_parser_for_extension(self, ext: str) -> Optional[object]:
        """根据文件后缀获取对应解析器。"""
        return self._parsers.get(ext.lower())

    def infer_category(self, filename: str, content_preview: str = "") -> tuple[CognitiveLayer, str]:
        """根据文件名与前缀内容自动推断认知层与分类全路径。"""
        name = filename.lower()
        if any(w in name for w in ["年度报告", "半年度报告", "季度", "一季度", "二季度", "三季度", "四季度", "annual", "quarter", "quarterly"]):
            if "基金" in name:
                return (CognitiveLayer.GROUND_TRUTH, "GROUND_TRUTH.PERIODIC.FUND_PERIODIC")
            return (CognitiveLayer.GROUND_TRUTH, "GROUND_TRUTH.PERIODIC.STOCK_PERIODIC")

        if any(w in name for w in ["公告", "变更", "分红", "停牌", "增发", "质押", "诉讼"]):
            if "基金经理" in name or "变更" in name:
                return (CognitiveLayer.GROUND_TRUTH, "GROUND_TRUTH.EVENT.FUND_EVENT")
            return (CognitiveLayer.GROUND_TRUTH, "GROUND_TRUTH.EVENT.STOCK_EVENT")

        if any(w in name for w in ["深度", "行业", "研报", "点评", "策略", "买入", "增持"]):
            return (CognitiveLayer.OPINION, "OPINION.BROKER.RESEARCH_REPORT")

        if any(w in name for w in ["纪要", "电话会", "调研", "访谈", "投决会"]):
            return (CognitiveLayer.OPINION, "OPINION.MEMO.MEETING_MINUTES")

        if any(w in name for w in ["快讯", "电讯", "盘中", "热点"]):
            return (CognitiveLayer.SIGNAL, "SIGNAL.FLASH.REALTIME_NEWS")

        if any(w in name for w in ["公式", "准则", "指标", "法规", "法条", "口径"]):
            return (CognitiveLayer.AXIOM, "AXIOM.METRIC.FINANCIAL_METRICS")

        return (CognitiveLayer.OPINION, "OPINION.BROKER.GENERAL")

    def parse_file(self, file_path: str, metadata: Optional[dict] = None) -> List[ParsedChunk]:
        """解析任意支持格式的本地文件并返回标准 Chunk 列表。"""
        p = Path(file_path)
        ext = p.suffix.lower()
        parser = self.get_parser_for_extension(ext)
        if not parser:
            raise ValueError(f"不支持的文件格式: {ext}")

        meta = metadata or {}
        cognitive_layer, category_path = self.infer_category(p.name)
        meta.setdefault("cognitive_layer", cognitive_layer.value)
        meta.setdefault("category_path", category_path)
        meta.setdefault("document_title", p.stem)

        chunks = parser.parse_file(str(p), metadata=meta)
        return chunks
