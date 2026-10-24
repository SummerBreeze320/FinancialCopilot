"""金融知识库核心 API 请求与响应数据传输对象 (DTO)。"""

from datetime import date
from enum import Enum
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class CognitiveLayer(str, Enum):
    """四大认知本体层级。"""
    AXIOM = "AXIOM"                  # 规范公理层 (指标计算口径、会计准则、监管法规)
    GROUND_TRUTH = "GROUND_TRUTH"    # 法定事实层 (上市公司财报、基金运作报告、法定披露)
    OPINION = "OPINION"              # 观点投研层 (卖方研报、盈利预测、调研纪要)
    SIGNAL = "SIGNAL"                # 动态信号层 (突发快讯、实时舆情、盘中异动)


class FilterParams(BaseModel):
    """检索多维过滤参数。"""
    stock_codes: Optional[List[str]] = Field(default=None, description="过滤关联股票代码，如 ['300750']")
    fund_codes: Optional[List[str]] = Field(default=None, description="过滤关联基金代码，如 ['005827']")
    cognitive_layers: Optional[List[CognitiveLayer]] = Field(default=None, description="过滤认知分层")
    category_paths: Optional[List[str]] = Field(default=None, description="过滤分类路径通配，如 ['GROUND_TRUTH.PERIODIC.*']")
    doc_types: Optional[List[str]] = Field(default=None, description="历史文档类型兼容过滤")
    institutions: Optional[List[str]] = Field(default=None, description="发布机构过滤，如 ['中金公司']")
    publish_date_start: Optional[date] = Field(default=None, description="发布起始日期")
    publish_date_end: Optional[date] = Field(default=None, description="发布截止日期")


class RetrieveRequest(BaseModel):
    """Top-K 知识切片检索请求。"""
    query: str = Field(..., min_length=1, description="检索查询词或投研自然语言意图")
    top_k: int = Field(default=5, ge=1, le=50, description="返回的最终切片数量")
    score_threshold: float = Field(default=0.35, ge=0.0, le=1.0, description="最低综合置信度得分截断阈值")
    filters: Optional[FilterParams] = Field(default=None, description="多维业务与标的过滤器")
    rerank_strategy: str = Field(default="neural", description="重排策略: neural (GPU神经模型), rrf (算法), hybrid")
    explain: bool = Field(default=False, description="是否输出执行耗时与候选池位移诊断透视报告")


class ChunkDTO(BaseModel):
    """交付给下游智能体或调用方的结构化知识切片。"""
    chunk_id: str = Field(..., description="切片唯一ID")
    document_id: str = Field(..., description="所属文档ID")
    document_title: str = Field(..., description="文档标题")
    cognitive_layer: CognitiveLayer = Field(..., description="所属认知分层")
    category_path: str = Field(..., description="细分类别全路径，如 GROUND_TRUTH.PERIODIC.STOCK_ANNUAL")
    institution: Optional[str] = Field(default=None, description="发布机构或证券公司")
    author: Optional[str] = Field(default=None, description="作者或分析师")
    publish_date: Optional[date] = Field(default=None, description="发布日期")
    page_number: Optional[int] = Field(default=None, description="PDF对应原始物理页码")
    section_title: Optional[str] = Field(default=None, description="所属大纲章节标题")
    content: str = Field(..., description="切片原始文本内容（含 Markdown 表格与表头）")
    score: float = Field(..., description="最终重排打分或相关度综合得分")
    recall_sources: List[str] = Field(default_factory=list, description="召回来源渠道: ['VECTOR', 'FULLTEXT']")
    has_table: bool = Field(default=False, description="当前切片是否包含结构化表格数据")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="业务扩展元数据 (股票/基金/表格信息)")


class CandidateRankShiftDTO(BaseModel):
    """候选片段在召回与重排阶段的位移分析。"""
    chunk_id: str = Field(...)
    vector_rank: Optional[int] = Field(default=None)
    vector_score: Optional[float] = Field(default=None)
    fulltext_rank: Optional[int] = Field(default=None)
    fulltext_score: Optional[float] = Field(default=None)
    rerank_score: Optional[float] = Field(default=None)
    boosts_applied: List[str] = Field(default_factory=list)
    final_rank: int = Field(...)
    rank_change: str = Field(..., description="位移说明，如 '+5 (Promoted)'")


class DiagnosticsDTO(BaseModel):
    """全链路诊断与精度归因透视报告。"""
    latency_breakdown_ms: Dict[str, float] = Field(
        default_factory=dict,
        description="各阶段耗时毫秒: embedding, vector_search, fulltext_search, rerank, total"
    )
    recall_funnel: Dict[str, int] = Field(
        default_factory=dict,
        description="召回漏斗数量: vector_total, fulltext_total, union_candidates"
    )
    candidate_rank_shift: List[CandidateRankShiftDTO] = Field(
        default_factory=list,
        description="重点候选片段的前后位移跟踪"
    )
    warnings: List[str] = Field(
        default_factory=list,
        description="异常与自适应告警，如候选池饱和预警"
    )


class RetrieveResponse(BaseModel):
    """Top-K 知识切片检索响应。"""
    query: str = Field(..., description="原始检索词")
    total_candidates: int = Field(..., description="粗召回阶段合并候选池数量")
    chunks: List[ChunkDTO] = Field(default_factory=list, description="最终精排 Top-K 切片列表")
    diagnostics: Optional[DiagnosticsDTO] = Field(default=None, description="诊断报告 (当 explain=True 时输出)")


class DocumentUploadResponse(BaseModel):
    """文档上传与入库响应。"""
    document_id: str = Field(...)
    title: str = Field(...)
    status: str = Field(...)
    message: str = Field(default="文档解析与向量化任务已提交")


class DocumentStatusResponse(BaseModel):
    """文档入库状态查询响应。"""
    document_id: str = Field(...)
    title: str = Field(...)
    status: str = Field(...)
    chunk_count: int = Field(default=0)
    error_message: Optional[str] = Field(default=None)
