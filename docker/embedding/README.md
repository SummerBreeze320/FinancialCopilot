# 本地 Embedding 服务

使用 Ollama + `qwen3-embedding:0.6b`，默认输出 **1024 维**向量。
需要运行 Docker Desktop（Linux 容器）并支持 NVIDIA GPU 透传。

在项目根目录执行：

```powershell
docker compose -f docker/embedding/docker-compose.yml up -d
docker compose -f docker/embedding/docker-compose.yml exec embedding ollama pull qwen3-embedding:0.6b
```

模型保存在 Docker 命名卷中，重建容器无需重新下载。服务只绑定本机回环地址。
镜像已固定到本次验证通过的 SHA-256 摘要，避免重新部署时自动变更版本。

## 调用

- OpenAI 兼容接口：`http://localhost:11434/v1/embeddings`
- 原生接口：`http://localhost:11434/api/embed`
- 模型：`qwen3-embedding:0.6b`
- 无需 API Key；客户端强制要求时可填 `ollama`。

PowerShell 中文与批量请求示例（显式 UTF-8 编码）：

```powershell
$body = @{
    model = 'qwen3-embedding:0.6b'
    input = @('基金投资组合的风险与收益', '债券基金的利率风险')
} | ConvertTo-Json
$response = Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:11434/v1/embeddings' `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
$response.data | ForEach-Object { "index=$($_.index), dimensions=$($_.embedding.Count)" }
```

## 运维与接入

```powershell
docker compose -f docker/embedding/docker-compose.yml ps
docker compose -f docker/embedding/docker-compose.yml logs --tail 50
docker compose -f docker/embedding/docker-compose.yml exec embedding ollama ps
powershell -ExecutionPolicy Bypass -File docker/embedding/test-embedding.ps1
docker compose -f docker/embedding/docker-compose.yml stop
```

当前仅部署 Embedding 服务，尚未接入 Java 检索链路。
本机 RTX 3060 6GB 已验证 GPU 推理，`ollama ps` 显示 `100% GPU`。
首次请求需要加载和预热模型；空闲 5 分钟后会卸载模型释放显存。
现有 `fund_report_vector.embedding` 是 `vector(1536)`，不能直接存入该模型的
1024 维结果。接入时应迁移向量字段/索引，使用同一模型重新生成文档向量；
查询文本也必须使用同一模型。不要通过补零混用不同模型的向量。
长文档先切分；本服务上下文配置为 4096 token，原生接口可设置
`truncate: false` 以拒绝超长输入，避免默认截断。

官方文档：[Docker](https://docs.ollama.com/docker)、
[模型](https://ollama.com/library/qwen3-embedding)、
[Embedding API](https://docs.ollama.com/api/embed)。
