"""PDF 文档双轨流式解析器 (原生文本 + 扫描件分流)。

采用生成器逐页流式提取，保障大文件（如 50MB 招股书或年报）内存恒定在低水位。
"""

from typing import Generator, List, Optional
import pymupdf  # PyMuPDF
import pdfplumber
from app.ingest.splitter import TableAwareSplitter, ParsedChunk


class PdfParser:
    """PDF 研报与财报流式解析器。"""

    def __init__(self, splitter: Optional[TableAwareSplitter] = None, ocr_engine: Optional[object] = None):
        self.splitter = splitter or TableAwareSplitter()
        self.ocr_engine = ocr_engine

    def iter_page_text(self, file_path: str) -> Generator[tuple[int, str, bool], None, None]:
        """逐页流式提取文字与表格，返回 (page_number, text, is_scanned)。"""
        with pymupdf.open(file_path) as doc:
            for page_idx in range(len(doc)):
                page = doc[page_idx]
                text = page.get_text().strip()
                page_num = page_idx + 1

                # 探测是否为扫描件（文字极少但存在图片）
                if len(text) < 30 and len(page.get_images()) > 0:
                    # 如果挂接了 OCR 引擎则调用 OCR
                    if self.ocr_engine and hasattr(self.ocr_engine, "recognize_page_image"):
                        pix = page.get_pixmap(dpi=150)
                        ocr_text = self.ocr_engine.recognize_page_image(pix.tobytes())
                        yield (page_num, ocr_text, True)
                    else:
                        yield (page_num, text or f"[扫描页 P.{page_num}]", True)
                else:
                    yield (page_num, text, False)

    def parse_file(self, file_path: str, metadata: Optional[dict] = None) -> List[ParsedChunk]:
        """完整解析 PDF 文件并切分为标准 Chunk 列表。"""
        base_meta = metadata or {}
        chunks: List[ParsedChunk] = []

        # 尝试使用 pdfplumber 提取原生表格
        tables_by_page = {}
        try:
            with pdfplumber.open(file_path) as pdf:
                for idx, p in enumerate(pdf.pages):
                    raw_tables = p.extract_tables()
                    if raw_tables:
                        tables_by_page[idx + 1] = raw_tables
        except Exception:
            pass

        for page_num, page_text, is_scanned in self.iter_page_text(file_path):
            page_meta = dict(base_meta)
            page_meta["page_number"] = page_num
            page_meta["is_scanned"] = is_scanned
            page_meta["filename"] = file_path

            # 如果该页有结构化提取的表格，附加转换为 Markdown
            if page_num in tables_by_page:
                table_md_parts = []
                for tbl in tables_by_page[page_num]:
                    if tbl and len(tbl) >= 2:
                        header = [str(c or "").replace("\n", " ") for c in tbl[0]]
                        col_count = len(header)
                        lines = [
                            "| " + " | ".join(header) + " |",
                            "| " + " | ".join([":---"] * col_count) + " |"
                        ]
                        for row in tbl[1:]:
                            clean_row = [str(c or "").replace("\n", " ") for c in row]
                            padded = clean_row + [""] * (col_count - len(clean_row))
                            lines.append("| " + " | ".join(padded[:col_count]) + " |")
                        table_md_parts.append("\n" + "\n".join(lines) + "\n")

                if table_md_parts:
                    page_text = page_text + "\n\n" + "\n\n".join(table_md_parts)

            page_chunks = self.splitter.split_text(page_text, metadata=page_meta)
            for c in page_chunks:
                c.chunk_index = len(chunks)
                c.page_number = page_num
                chunks.append(c)

        return chunks
