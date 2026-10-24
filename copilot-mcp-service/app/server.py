"""Financial Copilot FastMCP 主服务入口 (端口 8085)

支持两种通信通道：
1. 标准 MCP 2024-11-05 SSE/Streamable 规范 (供 Claude Desktop / Cursor / Antigravity 直接挂载)
2. 兼容 HTTP JSON-RPC 2.0 端点 (/messages 与 /api/mcp/messages) (供 Java ReactiveMcpClient 即插即用)
"""
import json
from typing import Any, Dict, Optional
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from loguru import logger
from mcp.server.fastmcp import FastMCP

from app.config import settings
from app.tools.entity_tools import recognize_entities_tool
from app.tools.memory_tools import search_memory_tool
from app.tools.market_tools import (
    fund_metrics_tool,
    get_market_indices_tool,
    macro_news_realtime_tool,
    stock_metrics_tool,
)

# 1. 声明官方 FastMCP 实例
mcp = FastMCP(
    name=settings.server_name,
    host=settings.host,
    port=settings.port,
    instructions="金融研究核心工具集：实体识别、长期记忆、量化指标与宏观行情"
)


# 2. 注册 FastMCP 领域工具
@mcp.tool(name="recognize_entities")
async def recognize_entities(text: str) -> dict:
    """对输入文本进行金融实体识别（包含基金代码、基金名称、基金经理、股票等实体解析）"""
    return await recognize_entities_tool(text)


@mcp.tool(name="search_memory")
async def search_memory(
    query: str,
    user_id: Optional[str] = None,
    max_results: int = 5
) -> dict:
    """按作用域检索用户的长期记忆事实（如投资风格偏好、关注行业、历史约束事实）"""
    return await search_memory_tool(query, user_id=user_id, max_results=max_results)


@mcp.tool(name="macro_news_realtime")
async def macro_news_realtime(keyword: str, limit: int = 5) -> dict:
    """获取最新宏观财经新闻快讯"""
    return await macro_news_realtime_tool(keyword, limit=limit)


@mcp.tool(name="fund_metrics")
async def fund_metrics(fund_code: str) -> dict:
    """查询公募基金核心量化绩效指标（年化收益、最大回撤、夏普比率、卡玛比率）"""
    return await fund_metrics_tool(fund_code)


@mcp.tool(name="stock_metrics")
async def stock_metrics(stock_code: str) -> dict:
    """查询A股个股基本行情与核心财务指标"""
    return await stock_metrics_tool(stock_code)


@mcp.tool(name="get_market_indices")
async def get_market_indices() -> dict:
    """获取中国核心宽基指数最新行情概况"""
    return await get_market_indices_tool()


# 3. 创建混合 FastAPI 服务
@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"🚀 [FastMCP] FinancialCopilot MCP 服务启动: {settings.host}:{settings.port}")
    tools = await mcp.list_tools()
    logger.info(f"📦 [FastMCP] 已就绪发布 {len(tools)} 个金融工具: {[t.name for t in tools]}")
    yield
    logger.info("🛑 [FastMCP] FinancialCopilot MCP 服务关闭")


app = FastAPI(
    title="FinancialCopilot MCP Service",
    version=settings.server_version,
    description="基于 Python FastMCP 的金融投研专业工具发现与执行网关",
    lifespan=lifespan
)


@app.get("/health")
async def health_check():
    """微服务健康检查"""
    tools = await mcp.list_tools()
    return {
        "status": "UP",
        "service": settings.server_name,
        "tools_count": len(tools),
        "protocol": "MCP 2024-11-05"
    }


@app.get("/")
async def root():
    return {
        "service": "copilot-mcp-service",
        "type": "FastMCP Server",
        "sse_endpoint": "/sse",
        "messages_endpoint": "/messages"
    }


@app.middleware("http")
async def trace_propagation_middleware(request: Request, call_next):
    traceparent = request.headers.get("traceparent")
    if traceparent:
        logger.debug(f"[FastMCP-TRACE] 接收上游跟踪凭证: traceparent={traceparent}, path={request.url.path}")
    response = await call_next(request)
    if traceparent:
        response.headers["traceparent"] = traceparent
    return response


# 4. JSON-RPC 2.0 兼容处理器 (供 Java ReactiveMcpClient 直接 POST)
@app.post("/messages")
@app.post("/api/mcp/messages")
async def handle_json_rpc_messages(request: Request):
    try:
        body = await request.json()
    except Exception as ex:
        return JSONResponse(
            status_code=400,
            content={
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32700, "message": f"Parse error: {str(ex)}"}
            }
        )

    jsonrpc_ver = body.get("jsonrpc", "2.0")
    req_id = body.get("id")
    method = body.get("method")
    user_id_header = request.headers.get("X-User-Id")
    traceparent = request.headers.get("traceparent")

    logger.debug(f"[FastMCP] 收到 JSON-RPC 请求: method={method}, id={req_id}, userId={user_id_header}, traceparent={traceparent}")

    # 4.1 工具清单发现 tools/list
    if method == "tools/list":
        try:
            tools = await mcp.list_tools()
            tools_data = []
            for t in tools:
                tools_data.append({
                    "name": t.name,
                    "description": t.description or "",
                    "inputSchema": t.inputSchema or {}
                })
            return {
                "jsonrpc": jsonrpc_ver,
                "id": req_id,
                "result": {"tools": tools_data}
            }
        except Exception as ex:
            logger.error(f"[FastMCP] 列出工具失败: {ex}")
            return JSONResponse(
                status_code=500,
                content={
                    "jsonrpc": jsonrpc_ver,
                    "id": req_id,
                    "error": {"code": -32603, "message": f"Internal error: {str(ex)}"}
                }
            )

    # 4.2 工具执行调用 tools/call
    elif method == "tools/call":
        params = body.get("params", {})
        tool_name = params.get("name")
        arguments = params.get("arguments", {})

        # 若请求头透传了 userId，且参数中未显式指定，则自动补齐
        if user_id_header and "user_id" not in arguments:
            arguments["user_id"] = user_id_header

        try:
            call_result = await mcp.call_tool(tool_name, arguments)
            content_list = []
            # FastMCP 返回的是 TextContent 列表
            for item in call_result:
                text_content = item.text if hasattr(item, "text") else str(item)
                content_list.append({"type": "text", "text": text_content})

            return {
                "jsonrpc": jsonrpc_ver,
                "id": req_id,
                "result": {
                    "content": content_list,
                    "isError": False
                }
            }
        except Exception as ex:
            logger.warning(f"[FastMCP] 执行工具 {tool_name} 报错: {ex}")
            return {
                "jsonrpc": jsonrpc_ver,
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": f"Tool Execution Error: {str(ex)}"}],
                    "isError": True
                }
            }

    # 4.3 探活 Ping
    elif method == "ping":
        return {"jsonrpc": jsonrpc_ver, "id": req_id, "result": {}}

    # 未知方法
    return JSONResponse(
        status_code=400,
        content={
            "jsonrpc": jsonrpc_ver,
            "id": req_id,
            "error": {"code": -32601, "message": f"Method '{method}' not found"}
        }
    )


# 5. 挂载标准 FastMCP SSE 应用 (用于 Claude/Cursor/外部通用客户端)
# FastMCP 的 SSE app 处理 /sse 与 /messages/ (带 session)
sse_sub_app = mcp.sse_app()
app.mount("/mcp", sse_sub_app)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.server:app", host=settings.host, port=settings.port, reload=False)
