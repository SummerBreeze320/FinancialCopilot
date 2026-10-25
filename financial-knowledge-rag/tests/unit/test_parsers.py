import pytest
from app.ingest.splitter import TableAwareSplitter, ParsedChunk
from app.ingest.markdown_parser import MarkdownParser
from app.ingest.parser_registry import ParserRegistry


def test_table_aware_splitter_header_duplication():
    splitter = TableAwareSplitter(chunk_size=160, chunk_overlap=30)
    markdown_table = (
        "| 指标 | 2022 | 2023 | 2024E |\n"
        "| :--- | :--- | :--- | :--- |\n"
        "| 营业收入(亿元) | 100 | 150 | 220 |\n"
        "| 净利润(亿元) | 10 | 18 | 28 |\n"
        "| 毛利率(%) | 25.0 | 26.5 | 28.0 |\n"
        "| 净利率(%) | 10.0 | 12.0 | 12.7 |\n"
        "| ROE(%) | 15.0 | 18.0 | 21.0 |\n"
    )
    chunks = splitter.split_text(markdown_table, metadata={"doc_id": "test_01"})
    table_chunks = [c for c in chunks if c.has_table]
    assert len(table_chunks) >= 2
    for c in table_chunks:
        assert "| 指标 | 2022 | 2023 | 2024E |" in c.content
        assert c.has_table is True
        assert c.metadata.get("doc_id") == "test_01"


def test_markdown_parser():
    parser = MarkdownParser()
    md_content = "# 一、公司概述\n宁德时代是全球领先的动力电池龙头。\n\n## 二、核心数据\n2024年前三季度出货持续增长。"
    chunks = parser.parse_text(md_content, filename="test.md")
    assert len(chunks) >= 1
    assert any("宁德时代" in c.content for c in chunks)


def test_parser_registry_mapping():
    registry = ParserRegistry()
    assert registry.get_parser_for_extension(".md") is not None
    assert registry.get_parser_for_extension(".xlsx") is not None
    assert registry.get_parser_for_extension(".docx") is not None
    assert registry.get_parser_for_extension(".pdf") is not None


def test_clean_financial_text_pipeline():
    from app.ingest.pdf_parser import clean_financial_text

    # 1. 汉字间异常断裂空格
    raw_chinese = "公 募 基 金 份 额 净 值 表现"
    assert clean_financial_text(raw_chinese) == "公募基金份额净值表现"

    # 2. 英文连字符断行
    raw_hyphen = "China semi-\nconductor market grew rapidly."
    assert clean_financial_text(raw_hyphen) == "China semiconductor market grew rapidly."

    # 3. 水印清洗与全角空格
    raw_watermark = "重要提示：本基金已获批准。\u3000内部资料 严禁外传\xa0\xa0"
    cleaned = clean_financial_text(raw_watermark)
    assert "内部资料" not in cleaned
    assert "严禁外传" not in cleaned
    assert "重要提示：本基金已获批准。" in cleaned

    # 4. 中文与数字间断行
    raw_num = "增长率为\n12.5%且持续提升"
    assert clean_financial_text(raw_num) == "增长率为 12.5%且持续提升"
