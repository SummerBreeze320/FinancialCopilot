"""Word 文档解析器 (.docx)。

解析大纲层级与嵌入表格，支持将表格结构化输出为 Markdown 表。
"""

from typing import List, Optional
import docx
from app.ingest.splitter import TableAwareSplitter, ParsedChunk


class DocxParser:
    """Word 研报与调研备忘录解析器。"""

    def __init__(self, splitter: Optional[TableAwareSplitter] = None):
        self.splitter = splitter or TableAwareSplitter()

    def parse_file(self, file_path: str, metadata: Optional[dict] = None) -> List[ParsedChunk]:
        base_meta = metadata or {}
        try:
            doc = docx.Document(file_path)
        except Exception as e:
            return [ParsedChunk(chunk_index=0, content=f"[Word读取错误: {e}]", metadata=base_meta)]

        content_parts: List[str] = []

        # 遍历所有段落与表格
        for element in doc.element.body:
            if element.tag.endswith("p"):
                # 段落
                p = docx.text.paragraph.Paragraph(element, doc)
                text = p.text.strip()
                if not text:
                    continue
                if p.style.name.startswith("Heading"):
                    content_parts.append(f"\n### {text}\n")
                else:
                    content_parts.append(text)
            elif element.tag.endswith("tbl"):
                # 表格
                tbl = docx.table.Table(element, doc)
                table_rows: List[List[str]] = []
                for row in tbl.rows:
                    row_data = [cell.text.strip().replace("\n", " ") for cell in row.cells]
                    table_rows.append(row_data)

                if table_rows:
                    header = table_rows[0]
                    col_count = len(header)
                    md_table_lines = [
                        "| " + " | ".join(header) + " |",
                        "| " + " | ".join([":---"] * col_count) + " |"
                    ]
                    for row_data in table_rows[1:]:
                        # 确保列数对齐
                        padded = row_data + [""] * (col_count - len(row_data))
                        md_table_lines.append("| " + " | ".join(padded[:col_count]) + " |")

                    content_parts.append("\n" + "\n".join(md_table_lines) + "\n")

        full_text = "\n\n".join(content_parts)
        meta = dict(base_meta)
        meta["filename"] = file_path
        return self.splitter.split_text(full_text, metadata=meta)
