from pydantic import BaseModel, Field


class MemoryPack(BaseModel):
    """离线预编译物化文本包，供在线 Reader 直接常数时间使用"""
    memory_id: str = Field(description="归属记忆ID")
    version: int = Field(description="归属记忆版本")
    compact_text: str = Field(description="在线默认短记忆（30~80 tokens，必须包含安全与前提约束）")
    standard_text: str = Field(description="完整操作指南/详细事实（包含前置条件与验证步骤）")
    token_count: int = Field(ge=0, description="预计算好的 Compact 文本 Token 数")
    retrieval_text: str = Field(description="用于生成向量 Embedding 的优化检索文本")
