"""Excel 报表解析器 (.xlsx, .xls)。

解析多工作表、将表格转换为标准 Markdown 表并调用 TableAwareSplitter 切块。
"""

from typing import List, Optional
import pandas as pd
from app.ingest.splitter import TableAwareSplitter, ParsedChunk


class ExcelParser:
    """Excel 财报与统计表格解析器。"""

    def __init__(self, splitter: Optional[TableAwareSplitter] = None):
        self.splitter = splitter or TableAwareSplitter()

    def parse_file(self, file_path: str, metadata: Optional[dict] = None) -> List[ParsedChunk]:
        base_meta = metadata or {}
        chunks: List[ParsedChunk] = []

        try:
            excel_file = pd.ExcelFile(file_path)
        except Exception as e:
            # 返回空或错误 Chunk
            return [ParsedChunk(chunk_index=0, content=f"[Excel读取错误: {e}]", metadata=base_meta)]

        for sheet_name in excel_file.sheet_names:
            try:
                df = pd.read_excel(excel_file, sheet_name=sheet_name)
            except Exception:
                continue

            if df.empty:
                continue

            # 填充缺失值并重置索引
            df = df.fillna("")
            # 转换为 Markdown 表格格式
            table_md = df.to_markdown(index=False)
            sheet_text = f"### Sheet: {sheet_name}\n\n{table_md}\n"

            sheet_meta = dict(base_meta)
            sheet_meta["sheet_name"] = sheet_name
            sheet_meta["filename"] = file_path

            sheet_chunks = self.splitter.split_text(sheet_text, metadata=sheet_meta)
            for c in sheet_chunks:
                c.chunk_index = len(chunks)
                chunks.append(c)

        return chunks
