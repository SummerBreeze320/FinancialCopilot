# FinancialCopilot FastMCP Service

Python 原生 FastMCP 工具发现与执行服务（遵循 MCP 2024-11-05 协议）。

## 职责定位
- 暴露金融领域专业工具（实体识别、记忆检索、宏观新闻、权威公募基金量化指标、A股个股量化指标、宽基指数）；
- 双通道支持：
  1. **HTTP JSON-RPC 2.0 端点**：`POST /messages` 和 `POST /api/mcp/messages`，供 Java `ReactiveMcpClient` 极速调用；
  2. **标准 SSE 协议**：`GET /mcp/sse`，供 Claude Desktop、Antigravity IDE、Cursor 等 MCP 客户端原生挂载；
- 杜绝大语言模型心算金融指标，保证量化计算权威与事实一致性。

## 默认端口
- 默认监听：`http://127.0.0.1:8085`

## 快速启动
```powershell
# 1. 安装依赖
pip install -e .

# 2. 启动服务
python -m uvicorn app.server:app --host 127.0.0.1 --port 8085
```

## 测试
```powershell
pytest
```
