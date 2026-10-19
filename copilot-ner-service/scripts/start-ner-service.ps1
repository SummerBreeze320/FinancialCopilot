[CmdletBinding()]
param(
    [string]$AdminToken = "local-ner-admin-token",
    [string]$CatalogPath = "",
    [string]$DatabaseUrl = "postgresql+asyncpg://postgres:123456@localhost:15432/financial_copilot",
    [string]$HostAddress = "0.0.0.0",
    [int]$Port = 8800,
    [int]$StartupTimeoutSeconds = 60,
    [switch]$SkipOllama
)

$ErrorActionPreference = "Stop"

$serviceRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $serviceRoot
$embeddingCompose = Join-Path $repoRoot "docker\embedding\docker-compose.yml"
$stdoutLog = Join-Path $serviceRoot "ner-service.stdout.log"
$stderrLog = Join-Path $serviceRoot "ner-service.stderr.log"

if ([string]::IsNullOrWhiteSpace($CatalogPath)) {
    $CatalogPath = Join-Path $repoRoot ".worktrees\lite-mysql\docs\temp\entity.json"
}
$CatalogPath = [System.IO.Path]::GetFullPath($CatalogPath)
$baseUrl = "http://127.0.0.1:$Port"

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Get-ListeningProcess([int]$LocalPort) {
    $connection = Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $connection) {
        return $null
    }
    return Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
}

function Wait-Health {
    param(
        [string]$Uri,
        [int]$TimeoutSeconds,
        [System.Diagnostics.Process]$Process
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($null -ne $Process -and $Process.HasExited) {
            throw "NER 进程提前退出，退出码 $($Process.ExitCode)。请检查：$stderrLog"
        }
        try {
            return Invoke-RestMethod -Uri $Uri -TimeoutSec 5
        }
        catch {
            Start-Sleep -Seconds 1
        }
    } while ((Get-Date) -lt $deadline)
    throw "NER 服务启动超时。请检查：$stderrLog"
}

if ($Port -lt 1 -or $Port -gt 65535) {
    throw "Port 必须在 1 到 65535 之间"
}
if ([string]::IsNullOrWhiteSpace($AdminToken) -or
    $AdminToken -in @("change-me", "replace-with-a-random-secret")) {
    throw "请通过 -AdminToken 设置非占位管理令牌"
}
if (-not (Test-Path -LiteralPath $CatalogPath -PathType Leaf)) {
    throw "实体目录文件不存在：$CatalogPath"
}

$existingProcess = Get-ListeningProcess -LocalPort $Port
if ($null -ne $existingProcess) {
    Write-Step "端口 $Port 已被进程 $($existingProcess.Id) 占用"
    try {
        $health = Invoke-RestMethod -Uri "$baseUrl/health" -TimeoutSec 5
    }
    catch {
        throw "端口 $Port 已被占用，但不是可访问的 NER 服务：$($existingProcess.ProcessName) (PID $($existingProcess.Id))"
    }
    Write-Host "NER 服务已经运行，无需重复启动。" -ForegroundColor Yellow
    $health | ConvertTo-Json -Depth 8
    exit 0
}

if (-not $SkipOllama) {
    if (-not (Test-Path -LiteralPath $embeddingCompose -PathType Leaf)) {
        throw "Ollama Compose 文件不存在：$embeddingCompose"
    }
    Write-Step "启动 Ollama embedding 服务"
    & docker compose -f $embeddingCompose up -d
    if ($LASTEXITCODE -ne 0) {
        throw "Ollama Compose 启动失败"
    }

    $ollamaDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        try {
            $tags = Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -TimeoutSec 5
            break
        }
        catch {
            Start-Sleep -Seconds 1
        }
    } while ((Get-Date) -lt $ollamaDeadline)
    if ($null -eq $tags) {
        throw "Ollama 启动超时"
    }
    if ($tags.models.name -notcontains "qwen3-embedding:0.6b") {
        throw "缺少 qwen3-embedding:0.6b，请执行：docker exec financial_copilot_embedding ollama pull qwen3-embedding:0.6b"
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
}

Write-Step "启动 NER 服务"
$python = (Get-Command python -ErrorAction Stop).Source
$env:ENTITY_CATALOG_PATH = $CatalogPath.Replace("\", "/")
$env:ENTITY_ADMIN_TOKEN = $AdminToken
$env:DATABASE_URL = $DatabaseUrl
$env:HOST = $HostAddress
$env:PORT = [string]$Port

$process = Start-Process -FilePath $python `
    -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", $HostAddress, "--port", [string]$Port) `
    -WorkingDirectory $serviceRoot `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden `
    -PassThru

$health = Wait-Health -Uri "$baseUrl/health" -TimeoutSeconds $StartupTimeoutSeconds -Process $process

Write-Host "`nNER 服务已启动" -ForegroundColor Green
[PSCustomObject]@{
    PID = $process.Id
    BaseUrl = $baseUrl
    Swagger = "$baseUrl/docs"
    HealthStatus = $health.status
    Database = $health.dependencies.database
    ActiveIndex = $health.dependencies.active_index
    Embedding = $health.dependencies.embedding
    Reranker = $health.dependencies.reranker
    StdoutLog = $stdoutLog
    StderrLog = $stderrLog
} | Format-List

if ($health.dependencies.database -ne "ok") {
    Write-Warning "数据库不可用；请先运行 rebuild-index.ps1 初始化数据库和索引。"
}
if ($health.dependencies.active_index -ne "ok") {
    Write-Warning "尚无活动实体索引；请运行 rebuild-index.ps1 建立索引。"
}
if ($health.dependencies.reranker -ne "ok") {
    Write-Warning "Reranker 不可用；服务将以降级模式运行。"
}
