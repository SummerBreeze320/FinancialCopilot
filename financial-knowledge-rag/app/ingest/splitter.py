"""表格感知切块器 (Table-Aware Splitter)。

确保金融报表、持股名单与财务数据表格跨块时不被腰斩，且每个子块顶部强制复制主表头。
"""

import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class ParsedChunk:
    """解析切块后的标准数据单元。"""
    chunk_index: int
    content: str
    page_number: Optional[int] = None
    section_title: Optional[str] = None
    has_table: bool = False
    metadata: Dict[str, Any] = field(default_factory=dict)


class TableAwareSplitter:
    """金融表格自适应切块器。"""

    def __init__(self, chunk_size: int = 700, chunk_overlap: int = 100):
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self._stock_pattern = re.compile(r"(?<!\d)(?:00|30|60|68)\d{4}(?!\d)")
        self._fund_pattern = re.compile(r"(?<!\d)(?:00|01|15|16|50|51)\d{4}(?!\d)")

    def extract_entities(self, text: str) -> Dict[str, List[str]]:
        """从文本中自动提取关联的股票与基金代码。"""
        stocks = list(set(self._stock_pattern.findall(text)))
        funds = list(set(self._fund_pattern.findall(text)))
        return {"stock_codes": stocks, "fund_codes": funds}

    def _split_table(self, table_lines: List[str], base_meta: dict, chunk_start_idx: int) -> List[ParsedChunk]:
        """切分长表格，确保每个切片头部完整保留表头与分隔符。"""
        if len(table_lines) < 2:
            content = "\n".join(table_lines)
            return [ParsedChunk(chunk_index=chunk_start_idx, content=content, has_table=True, metadata=dict(base_meta))]

        # 表头通常是前两行 (标题行 + 分隔线)
        header_lines = table_lines[:2]
        header_text = "\n".join(header_lines) + "\n"
        data_rows = table_lines[2:]

        chunks: List[ParsedChunk] = []
        current_rows: List[str] = []
        current_len = len(header_text)

        for row in data_rows:
            row_len = len(row) + 1
            if current_rows and (current_len + row_len > self.chunk_size):
                chunk_content = header_text + "\n".join(current_rows)
                meta = dict(base_meta)
                meta.update(self.extract_entities(chunk_content))
                chunks.append(ParsedChunk(
                    chunk_index=chunk_start_idx + len(chunks),
                    content=chunk_content,
                    has_table=True,
                    metadata=meta
                ))
                current_rows = [row]
                current_len = len(header_text) + row_len
            else:
                current_rows.append(row)
                current_len += row_len

        if current_rows:
            chunk_content = header_text + "\n".join(current_rows)
            meta = dict(base_meta)
            meta.update(self.extract_entities(chunk_content))
            chunks.append(ParsedChunk(
                chunk_index=chunk_start_idx + len(chunks),
                content=chunk_content,
                has_table=True,
                metadata=meta
            ))

        return chunks

    def split_text(self, text: str, metadata: Optional[dict] = None) -> List[ParsedChunk]:
        """将包含文本与 Markdown 表格的内容切分为结构化 Chunk 列表。"""
        meta = metadata or {}
        lines = text.splitlines()
        chunks: List[ParsedChunk] = []

        buffer_lines: List[str] = []
        in_table = False
        table_buffer: List[str] = []

        def flush_text_buffer():
            nonlocal buffer_lines
            if not buffer_lines:
                return
            block_text = "\n".join(buffer_lines).strip()
            buffer_lines = []
            if not block_text:
                return

            # 对纯文本段落做滑动窗口切分
            if len(block_text) <= self.chunk_size:
                m = dict(meta)
                m.update(self.extract_entities(block_text))
                chunks.append(ParsedChunk(
                    chunk_index=len(chunks),
                    content=block_text,
                    has_table=False,
                    metadata=m
                ))
            else:
                # 递归按句子切分
                paragraphs = block_text.split("\n\n")
                current_chunk = ""
                for p in paragraphs:
                    if len(current_chunk) + len(p) + 2 <= self.chunk_size:
                        current_chunk = f"{current_chunk}\n\n{p}".strip()
                    else:
                        if current_chunk:
                            m = dict(meta)
                            m.update(self.extract_entities(current_chunk))
                            chunks.append(ParsedChunk(
                                chunk_index=len(chunks),
                                content=current_chunk,
                                has_table=False,
                                metadata=m
                            ))
                        current_chunk = p
                if current_chunk:
                    m = dict(meta)
                    m.update(self.extract_entities(current_chunk))
                    chunks.append(ParsedChunk(
                        chunk_index=len(chunks),
                        content=current_chunk,
                        has_table=False,
                        metadata=m
                    ))

        for line in lines:
            stripped = line.strip()
            # 简单判断表格行 (以 | 开头且以 | 结尾)
            if stripped.startswith("|") and stripped.endswith("|"):
                if not in_table:
                    flush_text_buffer()
                    in_table = True
                table_buffer.append(line)
            else:
                if in_table:
                    # 表格结束
                    table_chunks = self._split_table(table_buffer, meta, len(chunks))
                    chunks.extend(table_chunks)
                    table_buffer = []
                    in_table = False
                buffer_lines.append(line)

        # 处理尾部残留
        if in_table and table_buffer:
            table_chunks = self._split_table(table_buffer, meta, len(chunks))
            chunks.extend(table_chunks)
        else:
            flush_text_buffer()

        return chunks
