$ErrorActionPreference = 'Stop'
$body = @{
    model = 'qwen3-embedding:0.6b'
    input = @(
        '基金投资组合的风险与收益',
        '基金投资组合的风险与收益',
        '今天的天气晴朗'
    )
} | ConvertTo-Json
$response = Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:11434/v1/embeddings' `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 180
if ($response.data.Count -ne 3) { throw 'Expected three embeddings.' }
foreach ($item in $response.data) {
    if ($item.embedding.Count -ne 1024) { throw 'Expected 1024 dimensions.' }
    $norm = 0.0
    foreach ($value in $item.embedding) {
        if ([double]::IsNaN($value) -or [double]::IsInfinity($value)) {
            throw 'Embedding contains a non-finite value.'
        }
        $norm += $value * $value
    }
    if ($norm -le 0) { throw 'Embedding must be nonzero.' }
}
$same = 0.0
$different = 0.0
for ($i = 0; $i -lt 1024; $i++) {
    $same += [Math]::Abs($response.data[0].embedding[$i] - $response.data[1].embedding[$i])
    $different += [Math]::Abs($response.data[0].embedding[$i] - $response.data[2].embedding[$i])
}
if ($same -gt 0.001) { throw 'Identical inputs returned inconsistent vectors.' }
if ($different -lt 0.01) { throw 'Different inputs returned indistinguishable vectors.' }
Write-Output 'PASS: Chinese batch input, 3 x 1024 finite nonzero values, consistent and distinct vectors.'
