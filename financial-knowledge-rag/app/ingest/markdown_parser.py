"""Markdown 文档解析器。"""

from typing import List, Optional
from app.ingest.splitter import TableAwareSplitter, ParsedChunk


class MarkdownParser:
    """Markdown 格式解析器。"""

    def __init__(self, splitter: Optional[TableAwareSplitter] = None):
        self.splitter = splitter or TableAwareSplitter()

    def parse_text(self, content: str, filename: str = "document.md", metadata: Optional[dict] = None) -> List[ParsedChunk]:
        meta = metadata or {}
        meta["filename"] = filename
        return self.splitter.split_text(content, metadata=meta)

    def parse_file(self, file_path: str, metadata: Optional[dict] = None) -> List[ParsedChunk]:
        with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
        return self.parse_text(content, filename=file_path, metadata=metadata)
