from __future__ import annotations

from app.models.candidate import CandidateContract

DEFAULT_METRICS = [
    CandidateContract(
        domain="metric",
        key="f_risk_maxdownside",
        name="最大回撤",
        aliases=("回撤", "最大回撤"),
        supported_operators=("<", "<=", ">", ">="),
        value_type="PERCENT",
        supported_usage=("filter", "sort"),
    ),
    CandidateContract(
        domain="metric",
        key="f_info_fundscale",
        name="基金规模",
        aliases=("规模", "基金规模"),
        supported_operators=(">", ">=", "<", "<="),
        value_type="BILLION_CNY",
        supported_usage=("filter", "sort"),
    ),
    CandidateContract(
        domain="metric",
        key="f_return_3y",
        name="近3年回报",
        aliases=("近3年收益", "近三年收益", "近3年回报"),
        supported_operators=(">", ">=", "<", "<="),
        value_type="PERCENT",
        supported_usage=("filter", "sort"),
    ),
    CandidateContract(
        domain="metric",
        key="f_info_existingyear",
        name="成立年限",
        aliases=("成立年限", "成立时间", "成立", "成立大于", "成立年限大于"),
        supported_operators=(">", ">=", "<", "<="),
        value_type="YEAR",
        supported_usage=("filter", "sort"),
    ),
]

DEFAULT_SCOPES = [
    CandidateContract(
        domain="scope",
        key="1000019220000000",
        name="全市场",
        aliases=("内地公募基金", "公募基金", "全市场"),
    ),
    CandidateContract(
        domain="scope",
        key="1000002533000000",
        name="股票型开放式基金",
        aliases=("主动权益", "主动权益基金"),
    ),
    CandidateContract(
        domain="scope",
        key="1000002535000000",
        name="偏股混合型基金",
        aliases=("主动权益", "主动权益基金"),
    ),
    CandidateContract(
        domain="scope",
        key="1000022713000000",
        name="半导体行业主题基金",
        aliases=("半导体", "芯片基金"),
    ),
]

DEFAULT_ENTITIES = [
    CandidateContract(
        domain="entity",
        key="161725.OF",
        name="招商中证白酒指数",
        aliases=("招商中证白酒指数", "招商中证白酒"),
        score=1.0,
        preferred_metric="f_info_fundcode",
        value_type="ENTITY_REF",
    ),
    CandidateContract(
        domain="entity",
        key="manager:zhangkun",
        name="张坤",
        aliases=("张坤",),
        score=1.0,
        preferred_metric="f_info_fundmanager",
        binding_role="FUND_MANAGER",
        value_type="ENTITY_REF",
    ),
]
