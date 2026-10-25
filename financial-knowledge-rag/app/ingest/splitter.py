"""大纲层级与块元素感知切块器 (Hierarchical & Block-Element Splitter)。

顺着标题、章节、条款、列表与表格边界进行结构化切分，
彻底摒弃无语义固定滑动窗口，保障财务报表与表格跨块时不被腰斩，且子块顶部强制保留表头。
"""

import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class Block:
    """提取的富语义块元素。"""
    btype: str  # 'H1', 'H2', 'H3', 'TABLE', 'CHART', 'PARAGRAPH', 'LIST_ITEM'
    content: str
    page: int
    meta: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ParsedChunk:
    """解析切块后的标准数据单元。"""
    chunk_index: int
    content: str
    page_number: Optional[int] = None
    section_title: Optional[str] = None
    has_table: bool = False
    has_chart: bool = False
    metadata: Dict[str, Any] = field(default_factory=dict)


class TableAwareSplitter:
    """金融大纲层级、块元素与表格保真自适应切块器。"""

    def __init__(self, chunk_size: int = 1200, chunk_overlap: int = 150):
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self._stock_pattern = re.compile(r"(?<!\d)(?:00|30|60|68)\d{4}(?!\d)")
        self._fund_pattern = re.compile(r"(?<!\d)(?:00|01|15|16|50|51)\d{4}(?!\d)")

    def extract_entities(self, text: str) -> Dict[str, List[str]]:
        """从文本中自动提取关联的 6 位股票与基金代码。"""
        stocks = list(set(self._stock_pattern.findall(text)))
        funds = list(set(self._fund_pattern.findall(text)))
        return {"stock_codes": stocks, "fund_codes": funds}

    def _split_long_table(
        self,
        table_content: str,
        base_meta: dict,
        section_title: str,
        page_num: int,
        chunk_start_idx: int,
        intro_text: str = "",
    ) -> List[ParsedChunk]:
        """对超长表格按数据行切分，确保每个切片头部完整复制小节导引说明与主表头。"""
        lines = [l for l in table_content.splitlines() if l.strip()]
        doc_title = base_meta.get("doc_title") or base_meta.get("title") or ""
        doc_prefix = f"【文档: {doc_title}】" if doc_title else ""
        prefix_sec = f"{doc_prefix}【章节大纲: {section_title}】(P.{page_num})\n\n" if section_title and section_title != "基金定期报告" else ""
        intro_prefix = f"【小节导引说明】\n{intro_text}\n\n" if intro_text else ""
        full_prefix = prefix_sec + intro_prefix

        if len(lines) < 3:
            meta = dict(base_meta)
            chunk_body = full_prefix + table_content
            meta.update(self.extract_entities(chunk_body))
            return [
                ParsedChunk(
                    chunk_index=chunk_start_idx,
                    content=chunk_body,
                    page_number=page_num,
                    section_title=section_title,
                    has_table=True,
                    metadata=meta,
                )
            ]

        header_lines = lines[:2]
        header_text = "\n".join(header_lines) + "\n"
        data_rows = lines[2:]

        chunks: List[ParsedChunk] = []
        current_rows: List[str] = []
        current_len = len(full_prefix) + len(header_text)

        for row in data_rows:
            row_len = len(row) + 1
            if current_rows and (current_len + row_len > self.chunk_size):
                chunk_body = full_prefix + header_text + "\n".join(current_rows)
                meta = dict(base_meta)
                meta.update(self.extract_entities(chunk_body))
                chunks.append(
                    ParsedChunk(
                        chunk_index=chunk_start_idx + len(chunks),
                        content=chunk_body,
                        page_number=page_num,
                        section_title=section_title,
                        has_table=True,
                        metadata=meta,
                    )
                )
                current_rows = [row]
                current_len = len(full_prefix) + len(header_text) + row_len
            else:
                current_rows.append(row)
                current_len += row_len

        if current_rows:
            chunk_body = full_prefix + header_text + "\n".join(current_rows)
            meta = dict(base_meta)
            meta.update(self.extract_entities(chunk_body))
            chunks.append(
                ParsedChunk(
                    chunk_index=chunk_start_idx + len(chunks),
                    content=chunk_body,
                    page_number=page_num,
                    section_title=section_title,
                    has_table=True,
                    metadata=meta,
                )
            )

        return chunks

    def split_blocks(
        self,
        blocks: List[Block],
        metadata: Optional[dict] = None,
    ) -> List[ParsedChunk]:
        """顺着大纲标题层级、章节、条款与表格块元素执行语义聚合切块。"""
        base_meta = metadata or {}
        chunks: List[ParsedChunk] = []

        cur_h1 = base_meta.get("title") or "基本信息"
        cur_h2 = ""
        buffer: List[str] = []
        buf_pages: List[int] = []
        buf_blocks: List[Block] = []

        def flush():
            nonlocal buffer, buf_pages, buf_blocks
            if not buffer:
                return

            # 核心防孤立标题规则：若缓冲区内全部都是大纲标题 (仅包含 H1/H2/H3)，无任何实质正文/表格/图表
            # 则绝对不单独生成切片，必须保留在缓冲区中与后续内容结合
            has_substance = any(b.btype not in ("H1", "H2", "H3") for b in buf_blocks)
            if not has_substance:
                return

            content_body = "\n\n".join(buffer).strip()
            if not content_body:
                buffer = []
                buf_pages = []
                buf_blocks = []
                return

            sec_title = f"{cur_h1}" + (f" > {cur_h2}" if cur_h2 else "")
            p_min = min(buf_pages) if buf_pages else 1
            p_max = max(buf_pages) if buf_pages else p_min
            page_label = f"P.{p_min}" if p_min == p_max else f"P.{p_min}-P.{p_max}"

            doc_title = base_meta.get("doc_title") or base_meta.get("title") or ""
            doc_prefix = f"【文档: {doc_title}】" if doc_title else ""
            header_prefix = f"{doc_prefix}【章节大纲: {sec_title}】({page_label})\n\n"
            full_content = header_prefix + content_body

            has_tbl = any(b.btype == "TABLE" for b in buf_blocks)
            has_crt = any(b.btype == "CHART" for b in buf_blocks)

            m = dict(base_meta)
            m.update(self.extract_entities(full_content))
            m["has_table"] = has_tbl
            m["has_chart"] = has_crt
            m["page_range"] = page_label

            chunks.append(
                ParsedChunk(
                    chunk_index=len(chunks),
                    content=full_content,
                    page_number=p_min,
                    section_title=sec_title,
                    has_table=has_tbl,
                    has_chart=has_crt,
                    metadata=m,
                )
            )
            buffer = []
            buf_pages = []
            buf_blocks = []

        for b in blocks:
            # 附注/说明附着检查：紧跟表格/图表的短注记文字附着到前一切片，避免孤立碎片
            is_footnote = (
                b.btype == "PARAGRAPH"
                and b.content.startswith(("注：", "注:", "注(", "注（", "说明：", "说明:", "申购费："))
                and len(b.content) < 350
            )

            if is_footnote and chunks and (chunks[-1].has_table or chunks[-1].has_chart) and not buffer:
                chunks[-1].content += f"\n\n{b.content}"
                continue

            # 1. 遇到一级大纲标题
            if b.btype == "H1":
                # 仅当缓冲区已有实质正文/表格时才刷出前置切块
                if buffer and any(x.btype not in ("H1", "H2", "H3") for x in buf_blocks):
                    flush()
                cur_h1 = b.content
                cur_h2 = ""
                buffer.append(f"## {b.content}")
                buf_pages.append(b.page)
                buf_blocks.append(b)

            # 2. 遇到二级大纲标题
            elif b.btype == "H2":
                # 仅当缓冲区已有实质正文/表格时才刷出上一小节；若只有一级标题，不分块，让 H1 与 H2 结合承接正文
                if buffer and any(x.btype not in ("H1", "H2", "H3") for x in buf_blocks):
                    flush()
                cur_h2 = b.content
                buffer.append(f"### {b.content}")
                buf_pages.append(b.page)
                buf_blocks.append(b)

            # 3. 遇到表格或图表块元素
            elif b.btype in ("TABLE", "CHART"):
                sec_title = f"{cur_h1}" + (f" > {cur_h2}" if cur_h2 else "")
                curr_buf_len = sum(len(x) for x in buffer)

                # 如果表格过长 (> self.chunk_size 字符)，提取缓冲区中已有说明文字作为前置导引并切分
                if b.btype == "TABLE" and len(b.content) > self.chunk_size:
                    intro_paragraphs = [
                        x for idx, x in enumerate(buffer)
                        if buf_blocks[idx].btype not in ("H1", "H2", "H3")
                    ]
                    intro_text = "\n\n".join(intro_paragraphs).strip()
                    if buffer and any(x.btype not in ("H1", "H2", "H3") for x in buf_blocks):
                        flush()
                    sub_chunks = self._split_long_table(
                        table_content=b.content,
                        base_meta=base_meta,
                        section_title=sec_title,
                        page_num=b.page,
                        chunk_start_idx=len(chunks),
                        intro_text=intro_text,
                    )
                    chunks.extend(sub_chunks)
                else:
                    # 中小型表格：优先与当前小节的前置说明、标题融合
                    if buffer and (curr_buf_len + len(b.content) > self.chunk_size):
                        flush()

                    buffer.append(b.content)
                    buf_pages.append(b.page)
                    if "end_page" in b.meta:
                        buf_pages.append(b.meta["end_page"])
                    buf_blocks.append(b)

                    # 核心设计：不立即强制 flush！
                    # 允许紧随表格后面的附注（如“注：上述股票不存在质押...”）或同小节补充说明继续聚合进来
                    # 仅当合并后总长度显著接近上限（如 >= 90% chunk_size）才切块
                    if sum(len(x) for x in buffer) >= int(self.chunk_size * 0.9):
                        flush()

            # 4. 普通段落、三级条款、列表项
            else:
                curr_len = sum(len(x) for x in buffer)
                # 仅当缓冲区已有实质正文且长度超限时刷新
                if buffer and any(x.btype not in ("H1", "H2", "H3") for x in buf_blocks) and (curr_len + len(b.content) > self.chunk_size):
                    flush()
                buffer.append(b.content)
                buf_pages.append(b.page)
                buf_blocks.append(b)

        flush()
        return chunks

    def split_text(self, text: str, metadata: Optional[dict] = None) -> List[ParsedChunk]:
        """纯文本或 Markdown 兼容切分入口（保留单元测试与非 PDF 场景支持）。"""
        meta = metadata or {}
        lines = text.splitlines()
        blocks: List[Block] = []

        in_table = False
        table_lines: List[str] = []

        for line in lines:
            stripped = line.strip()
            if stripped.startswith("|") and stripped.endswith("|"):
                if not in_table:
                    in_table = True
                table_lines.append(line)
            else:
                if in_table:
                    blocks.append(Block("TABLE", "\n".join(table_lines), 1))
                    table_lines = []
                    in_table = False
                if stripped.startswith("# "):
                    blocks.append(Block("H1", stripped[2:].strip(), 1))
                elif stripped.startswith("## "):
                    blocks.append(Block("H2", stripped[3:].strip(), 1))
                elif stripped.startswith("### "):
                    blocks.append(Block("H3", stripped[4:].strip(), 1))
                elif stripped:
                    blocks.append(Block("PARAGRAPH", line, 1))

        if in_table and table_lines:
            blocks.append(Block("TABLE", "\n".join(table_lines), 1))

        return self.split_blocks(blocks, metadata=meta)
