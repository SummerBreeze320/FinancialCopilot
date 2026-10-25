import sys
sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding='utf-8')
from scripts.test_new_parser import extract_clean_blocks, pdf_path
import re

blocks = extract_clean_blocks(pdf_path)

class HierarchicalSplitter:
    def __init__(self, max_size=800):
        self.max_size = max_size
        self._stock_pattern = re.compile(r'(?<!\d)(?:00|30|60|68)\d{4}(?!\d)')
        self._fund_pattern = re.compile(r'(?<!\d)(?:00|01|15|16|50|51)\d{4}(?!\d)')

    def split_blocks(self, blocks):
        chunks = []
        cur_h1 = '基金季报'
        cur_h2 = ''
        buffer = []
        buf_pages = []
        buf_blocks = []

        def flush():
            nonlocal buffer, buf_pages, buf_blocks
            if not buffer:
                return
            content = '\n\n'.join(buffer).strip()
            if not content:
                buffer = []
                buf_pages = []
                buf_blocks = []
                return
            header_prefix = f'【章节大纲: {cur_h1}' + (f' > {cur_h2}' if cur_h2 else '') + f'】(P.{min(buf_pages)}-P.{max(buf_pages)})\n\n'
            full_content = header_prefix + content
            has_tbl = any(b.btype == 'TABLE' for b in buf_blocks)
            has_crt = any(b.btype == 'CHART' for b in buf_blocks)
            stocks = list(set(self._stock_pattern.findall(full_content)))
            funds = list(set(self._fund_pattern.findall(full_content)))
            chunks.append({
                'chunk_index': len(chunks),
                'section_title': f'{cur_h1}' + (f' > {cur_h2}' if cur_h2 else ''),
                'page_number': min(buf_pages),
                'content': full_content,
                'has_table': has_tbl,
                'has_chart': has_crt,
                'stock_codes': stocks,
                'fund_codes': funds,
                'length': len(full_content)
            })
            buffer = []
            buf_pages = []
            buf_blocks = []

        for b in blocks:
            if b.btype == 'H1':
                flush()
                cur_h1 = b.content
                cur_h2 = ''
                buffer.append(f'## {b.content}')
                buf_pages.append(b.page)
                buf_blocks.append(b)
            elif b.btype == 'H2':
                flush()
                cur_h2 = b.content
                buffer.append(f'### {b.content}')
                buf_pages.append(b.page)
                buf_blocks.append(b)
            elif b.btype in ('TABLE', 'CHART'):
                if buffer and len('\n\n'.join(buffer)) > 300:
                    flush()
                buffer.append(b.content)
                buf_pages.append(b.page)
                if 'end_page' in b.meta:
                    buf_pages.append(b.meta['end_page'])
                buf_blocks.append(b)
                flush()
            else:
                # 段落与列表
                if buffer and sum(len(x) for x in buffer) + len(b.content) > self.max_size:
                    flush()
                buffer.append(b.content)
                buf_pages.append(b.page)
                buf_blocks.append(b)
        flush()
        return chunks

splitter = HierarchicalSplitter()
chunks = splitter.split_blocks(blocks)
print(f'成功按大纲结构切分为: {len(chunks)} 个高质量切片')
for i, c in enumerate(chunks, 1):
    tbl_flag = '[含表格]' if c['has_table'] else ('[含图表]' if c['has_chart'] else '[纯文本]')
    print(f"({i:02d}) P.{c['page_number']:02d} {tbl_flag} {c['section_title']} (长: {c['length']:4d}字) 股票:{c['stock_codes']} 基金:{c['fund_codes']}")
