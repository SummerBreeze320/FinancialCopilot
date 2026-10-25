import sys
sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding='utf-8')
from scripts.test_new_parser import extract_clean_blocks, pdf_path

blocks = extract_clean_blocks(pdf_path)
tbl_blocks = [b for b in blocks if b.btype in ('TABLE', 'CHART')]
print(f"Total TABLE + CHART blocks: {len(tbl_blocks)}")
for i, b in enumerate(tbl_blocks, 1):
    end_p = b.meta.get("end_page", b.page)
    stitched = b.meta.get("stitched", False)
    rows = b.meta.get("rows", "-")
    print(f"[{i}] Type: {b.btype} Pages: P.{b.page}-P.{end_p} | Stitched: {stitched} | Rows: {rows}")
    first_two_lines = b.content.splitlines()[:2]
    print("    Header:", " / ".join(first_two_lines))
