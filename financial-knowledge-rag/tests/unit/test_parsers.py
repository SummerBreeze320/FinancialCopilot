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
