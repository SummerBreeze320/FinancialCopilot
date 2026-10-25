"""原型测试：PDF 去噪、表格去重与跨页缝合、图表注入、顺着标题与块元素切分。"""

import sys
sys.stdout.reconfigure(encoding='utf-8')
import re
from typing import Any, Dict, List, Optional
import pdfplumber
import pymupdf

pdf_path = r"D:\Users\longlong\Downloads\诺安成长混合型证券投资基金2026 年第 2 季度报告2026 年 06 月 30 日.pdf"

# 1. 标题识别正则
SEC_L1_REGEX = re.compile(r"^(§\s*\d+|重要提示|目录|备查文件目录)")
SEC_L2_REGEX = re.compile(r"^(\d+\.\d+)\s+(.+)")
SEC_L3_REGEX = re.compile(r"^(\(\d+\)|[一二三四五六七八九十]、|\d+、)")


class Block:
    def __init__(self, btype: str, content: str, page: int, meta: Optional[dict] = None):
        self.btype = btype  # H1, H2, H3, TABLE, CHART, PARAGRAPH, LIST
        self.content = content
        self.page = page
        self.meta = meta or {}

    def __repr__(self):
        return f"<Block {self.btype} P.{self.page} len={len(self.content)}: {repr(self.content[:40])}>"


def table_to_markdown(table_data: List[List[Any]]) -> str:
    """将二维表格数据转换为标准 Markdown 表格。"""
    if not table_data or len(table_data) < 2:
        return ""
    # 清理换行符与空格
    clean_rows = []
    max_cols = max(len(r) for r in table_data)
    for row in table_data:
        r = [str(c or "").replace("\n", " ").strip() for c in row]
        r = r + [""] * (max_cols - len(r))
        clean_rows.append(r)

    headers = clean_rows[0]
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join([":---"] * max_cols) + " |"
    ]
    for row in clean_rows[1:]:
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines)


def extract_clean_blocks(pdf_file: str) -> List[Block]:
    blocks: List[Block] = []

    # 1. 提取所有页面的表格及其 bbox
    tables_per_page = {}
    with pdfplumber.open(pdf_file) as pdf:
        for idx, page in enumerate(pdf.pages):
            pno = idx + 1
            found = page.find_tables()
            t_list = []
            for t in found:
                t_data = t.extract()
                if t_data and len(t_data) >= 1:
                    t_list.append({
                        "bbox": t.bbox,  # (x0, top, x1, bottom)
                        "data": t_data,
                        "page": pno
                    })
            tables_per_page[pno] = t_list

    # 2. 跨页表格缝合 (Stitch Cross-Page Tables)
    stitched_tables = []
    skip_table_keys = set()

    for pno in range(1, len(tables_per_page) + 1):
        tbls = tables_per_page.get(pno, [])
        for t_idx, t in enumerate(tbls):
            t_key = (pno, t_idx)
            if t_key in skip_table_keys:
                continue

            # 探测下一页是否有接续表格 (Page N bottom -> Page N+1 top)
            next_pno = pno + 1
            next_tbls = tables_per_page.get(next_pno, [])
            if t["bbox"][3] > 700 and next_tbls:
                first_next = next_tbls[0]
                if first_next["bbox"][1] < 120 and (next_pno, 0) not in skip_table_keys:
                    # 检查是否为接续表格 (例如行业分类 O -> P，或投资策略文字续行)
                    # 缝合逻辑
                    merged_data = list(t["data"])
                    next_rows = first_next["data"]

                    # 如果下一页的第一行是接续上一页最后单元格的句子
                    if not next_rows[0][0] and next_rows[0][1]:
                        # 续接到上一页最后一行
                        last_row = list(merged_data[-1])
                        last_row[1] = (last_row[1] + " " + next_rows[0][1]).strip()
                        merged_data[-1] = last_row
                        next_rows = next_rows[1:]

                    merged_data.extend(next_rows)
                    stitched_tables.append({
                        "page": pno,
                        "end_page": next_pno,
                        "data": merged_data,
                        "stitched": True,
                        "bbox_p1": t["bbox"],
                        "bbox_p2": first_next["bbox"]
                    })
                    skip_table_keys.add((next_pno, 0))
                    continue

            stitched_tables.append({
                "page": pno,
                "end_page": pno,
                "data": t["data"],
                "stitched": False,
                "bbox": t["bbox"]
            })

    # 3. 逐页读取 PyMuPDF blocks，过滤页眉、页码，并遮罩表格区域
    doc = pymupdf.open(pdf_file)
    for pno in range(1, len(doc) + 1):
        page = doc[pno - 1]
        raw_blocks = page.get_text("blocks")

        # 获取该页涉及的表格 bboxes
        page_tbl_bboxes = []
        for t in tables_per_page.get(pno, []):
            page_tbl_bboxes.append(t["bbox"])

        for b in raw_blocks:
            x0, y0, x1, y1, text, block_no, btype = b
            text_str = text.strip()
            if not text_str:
                continue

            # 规则 A: 过滤页眉 (y0 < 60)
            if y0 < 60 and ("报告" in text_str or "基金" in text_str):
                continue
            # 规则 B: 过滤页码与页脚 (y0 > 735 且为纯数字或页码格式)
            if y0 > 735:
                if text_str.isdigit() or re.match(r"^第\s*\d+\s*页", text_str):
                    continue

            # 规则 C: 检查是否处于该页的表格区域内 (遮罩避免表格文字重复)
            mid_y = (y0 + y1) / 2
            is_inside_table = False
            for tbox in page_tbl_bboxes:
                # tbox: (x0, top, x1, bottom)
                if tbox[1] - 5 <= mid_y <= tbox[3] + 5:
                    is_inside_table = True
                    break
            if is_inside_table:
                # 该文字已被表格提取器结构化接管，此处忽略
                continue

            # 规则 D: 标题与段落分类
            if SEC_L1_REGEX.search(text_str):
                blocks.append(Block("H1", text_str, pno))
            elif SEC_L2_REGEX.search(text_str):
                blocks.append(Block("H2", text_str, pno))
            elif SEC_L3_REGEX.search(text_str):
                blocks.append(Block("H3", text_str, pno))
            else:
                blocks.append(Block("PARAGRAPH", text_str, pno))

        # 检查该页是否有统计走势图
        if pno == 4 and "3.2.2" in page.get_text():
            blocks.append(Block("CHART", "【📊 历史走势图】自基金合同生效以来基金份额累计净值增长率变动及其与同期业绩比较基准收益率变动的比较（诺安成长混合A / 诺安成长混合C 历史净值走势折线图）\n注：本基金各类份额自实际有资产之日起披露业绩数据，资产配置比例符合基金合同约定。", 4, {"chart_type": "LINE_CHART"}))

        # 将在该页结束的缝合表格插入流中
        for st in stitched_tables:
            if st["end_page"] == pno:
                md_table = table_to_markdown(st["data"])
                if md_table:
                    blocks.append(Block("TABLE", md_table, st["page"], {
                        "stitched": st["stitched"],
                        "end_page": st["end_page"],
                        "rows": len(st["data"])
                    }))

    return blocks


if __name__ == "__main__":
    extracted = extract_clean_blocks(pdf_path)
    print(f"成功提取纯净语义块总数: {len(extracted)}")
    print("\n=== 前 15 个纯净语义块预览 ===")
    for idx, b in enumerate(extracted[:15], 1):
        print(f"[{idx}] {b.btype} (P.{b.page}): {b.content[:70].replace(chr(10), ' ')}...")
