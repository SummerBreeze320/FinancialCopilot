[CmdletBinding()]
param(
    [string]$AdminToken = "local-ner-admin-token",
    [string]$CatalogPath = "",
    [string]$BaseUrl = "http://127.0.0.1:8800",
    [string]$DatabaseUrl = "postgresql+asyncpg://postgres:123456@localhost:15432/financial_copilot",
    [switch]$Force,
    [int]$PollSeconds = 5,
    [int]$StartupTimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"

$serviceRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $serviceRoot
$embeddingCompose = Join-Path $repoRoot "docker\embedding\docker-compose.yml"
$schemaFile = Join-Path $repoRoot "docker\postgres\initdb\05_entity_schema.sql"
$stdoutLog = Join-Path $serviceRoot "ner-service.stdout.log"
$stderrLog = Join-Path $serviceRoot "ner-service.stderr.log"

if ([string]::IsNullOrWhiteSpace($CatalogPath)) {
    $CatalogPath = Join-Path $repoRoot ".worktrees\lite-mysql\docs\temp\entity.json"
}
$CatalogPath = [System.IO.Path]::GetFullPath($CatalogPath)

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Wait-HttpReady {
    param(
        [string]$Uri,
        [int]$TimeoutSeconds
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            Invoke-RestMethod -Uri $Uri -TimeoutSec 5 | Out-Null
            return
        }
        catch {
            Start-Sleep -Seconds 1
        }
    } while ((Get-Date) -lt $deadline)
    throw "等待服务超时：$Uri"
}

function Test-PortListening([int]$Port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

if (-not (Test-Path -LiteralPath $CatalogPath -PathType Leaf)) {
    throw "实体目录文件不存在：$CatalogPath"
}
if (-not (Test-Path -LiteralPath $embeddingCompose -PathType Leaf)) {
    throw "Ollama Compose 文件不存在：$embeddingCompose"
}
if (-not (Test-Path -LiteralPath $schemaFile -PathType Leaf)) {
    throw "数据库初始化脚本不存在：$schemaFile"
}
if ($PollSeconds -lt 1) {
    throw "PollSeconds 必须大于 0"
}

Write-Step "启动 Ollama embedding 服务"
& docker compose -f $embeddingCompose up -d
if ($LASTEXITCODE -ne 0) {
    throw "Ollama Compose 启动失败"
}
Wait-HttpReady -Uri "http://127.0.0.1:11434/api/tags" -TimeoutSeconds $StartupTimeoutSeconds

$models = Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -TimeoutSec 10
if ($models.models.name -notcontains "qwen3-embedding:0.6b") {
    throw "缺少模型 qwen3-embedding:0.6b，请先执行：docker exec financial_copilot_embedding ollama pull qwen3-embedding:0.6b"
}

Write-Step "预热 qwen3-embedding:0.6b"
$warmupBody = @{
    model = "qwen3-embedding:0.6b"
    input = @("FinancialCopilot NER warmup")
} | ConvertTo-Json -Depth 3
$warmup = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:11434/v1/embeddings" `
    -ContentType "application/json" -Body $warmupBody -TimeoutSec 120
if ($warmup.data[0].embedding.Count -ne 1024) {
    throw "Embedding 维度异常，期望 1024，实际 $($warmup.data[0].embedding.Count)"
}

Write-Step "初始化 PostgreSQL 实体表（幂等）"
Get-Content -LiteralPath $schemaFile -Raw |
    & docker exec -i docker_postgres_16 psql -v ON_ERROR_STOP=1 -U postgres -d financial_copilot
if ($LASTEXITCODE -ne 0) {
    throw "PostgreSQL 实体表初始化失败"
}

$baseUri = [Uri]$BaseUrl
$port = if ($baseUri.IsDefaultPort) { 80 } else { $baseUri.Port }
if (-not (Test-PortListening -Port $port)) {
    Write-Step "启动 NER 服务"
    $env:ENTITY_CATALOG_PATH = $CatalogPath.Replace("\", "/")
    $env:ENTITY_ADMIN_TOKEN = $AdminToken
    $env:DATABASE_URL = $DatabaseUrl
    $env:HOST = "0.0.0.0"
    $env:PORT = [string]$port

    $python = (Get-Command python -ErrorAction Stop).Source
    Start-Process -FilePath $python `
        -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", [string]$port) `
        -WorkingDirectory $serviceRoot `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -WindowStyle Hidden | Out-Null
}
else {
    Write-Step "复用已运行的 NER 服务：$BaseUrl"
}

Wait-HttpReady -Uri "$BaseUrl/health" -TimeoutSeconds $StartupTimeoutSeconds
$health = Invoke-RestMethod -Uri "$BaseUrl/health" -TimeoutSec 10
if ($health.dependencies.database -ne "ok") {
    throw "NER 数据库依赖不可用：$($health | ConvertTo-Json -Depth 8 -Compress)"
}
if ($health.dependencies.embedding -ne "ok") {
    throw "NER Embedding 依赖不可用。检查 $stderrLog"
}

Write-Step "提交实体索引重建任务"
$headers = @{ Authorization = "Bearer $AdminToken" }
$rebuildBody = @{ force = [bool]$Force } | ConvertTo-Json
$job = Invoke-RestMethod -Method Post -Uri "$BaseUrl/v1/entities/index/rebuild" `
    -Headers $headers -ContentType "application/json" -Body $rebuildBody -TimeoutSec 30
$jobId = $job.job_id
Write-Host "任务 ID：$jobId"

Write-Step "跟踪索引进度"
$lastProcessed = -1
$startedAt = Get-Date
do {
    $status = Invoke-RestMethod -Uri "$BaseUrl/v1/entities/index/jobs/$jobId" -TimeoutSec 15
    $total = [int64]$status.total
    $processed = [int64]$status.processed
    $percent = if ($total -gt 0) { [math]::Min(100, [math]::Round($processed * 100.0 / $total, 2)) } else { 0 }
    $elapsed = (Get-Date) - $startedAt
    $rate = if ($elapsed.TotalSeconds -gt 0) { $processed / $elapsed.TotalSeconds } else { 0 }
    $remaining = if ($rate -gt 0 -and $total -gt $processed) {
        [TimeSpan]::FromSeconds(($total - $processed) / $rate).ToString("hh\:mm\:ss")
    }
    else {
        "--:--:--"
    }

    Write-Progress -Activity "构建 NER 实体索引" `
        -Status "$processed / $total ($percent%)，预计剩余 $remaining" `
        -PercentComplete $percent
    if ($processed -ne $lastProcessed) {
        Write-Host ("[{0:HH:mm:ss}] {1,-10} {2,8} / {3,-8} {4,6}%  ETA {5}" -f `
            (Get-Date), $status.status, $processed, $total, $percent, $remaining)
        $lastProcessed = $processed
    }

    if ($status.status -in @("completed", "failed")) {
        break
    }
    Start-Sleep -Seconds $PollSeconds
} while ($true)

Write-Progress -Activity "构建 NER 实体索引" -Completed
if ($status.status -eq "failed") {
    throw "索引任务失败：$($status.error)"
}

Write-Step "验证服务状态"
$finalHealth = Invoke-RestMethod -Uri "$BaseUrl/health" -TimeoutSec 10
$finalHealth | ConvertTo-Json -Depth 8
if ($finalHealth.dependencies.active_index -ne "ok") {
    throw "索引任务已结束，但 active_index 尚未就绪"
}

Write-Host "`n索引建立完成。Swagger：$BaseUrl/docs" -ForegroundColor Green
