# PiPilot Bridge 一键启动脚本
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\start-bridge.ps1              # 启动（默认）
#   powershell -ExecutionPolicy Bypass -File scripts\start-bridge.ps1 -CheckOnly   # 只做环境自检，不启动
#   powershell -ExecutionPolicy Bypass -File scripts\start-bridge.ps1 -Host windows-zk.tail.guozk.top
param(
    [switch]$CheckOnly,
    [string]$PairHost
)

$ErrorActionPreference = 'Stop'
$bridgeDir = Join-Path $PSScriptRoot '..\bridge'

function Write-Step($message) { Write-Host "[PiPilot] $message" -ForegroundColor Cyan }
function Write-Ok($message) { Write-Host "[PiPilot] OK $message" -ForegroundColor Green }
function Write-Fail($message) { Write-Host "[PiPilot] 失败：$message" -ForegroundColor Red; exit 1 }

# 1) Node.js >= 24
Write-Step '检查 Node.js（需要 24 LTS 或更高）…'
$nodeOk = $false
try {
    $nodeVersion = [version]((node --version) -replace '^v', '')
    if ($nodeVersion -ge [version]'24.0.0') { $nodeOk = $true }
} catch { }
if (-not $nodeOk) { Write-Fail '未找到 Node.js 24+，请先安装：https://nodejs.org/' }
Write-Ok ("Node " + $nodeVersion)

# 2) pnpm 10（项目通过 packageManager 锁定；优先系统 pnpm，其次 corepack 在 bridge 目录内解析）
Write-Step '检查 pnpm（项目锁定 10.x）…'
$pnpmCmd = $null
try {
    if ((pnpm --version 2>$null) -like '10.*') { $pnpmCmd = 'pnpm' }
} catch { }
if (-not $pnpmCmd) {
    try {
        Push-Location $bridgeDir
        $corepackVersion = (corepack pnpm --version 2>$null)
        Pop-Location
        if ($corepackVersion -like '10.*') { $pnpmCmd = 'corepack pnpm' }
    } catch {
        try { Pop-Location } catch { }
    }
}
if (-not $pnpmCmd) { Write-Fail '未找到 pnpm 10。请执行：corepack enable，或安装 pnpm@10：npm install -g pnpm@10' }
Write-Ok "pnpm ($pnpmCmd)"

# 3) Pi 编程智能体 >= 0.80.6
Write-Step '检查 Pi（需要 0.80.6 或更高）…'
$piVersionText = $null
try { $piVersionText = (pi --version 2>$null) } catch { }
if (-not $piVersionText) { Write-Fail '未找到 pi 命令。请先安装：npm install -g @earendil-works/pi-coding-agent@^0.80.6' }
Write-Ok "Pi $piVersionText"

# 4) bridge/.env（缺失时创建并自动生成令牌）
Write-Step '检查 bridge/.env …'
$envPath = Join-Path $bridgeDir '.env'
if (-not (Test-Path $envPath)) {
    $token = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }) -as [byte[]])
    if (-not $token) { Write-Fail '生成令牌失败' }
    @"
BRIDGE_HOST=127.0.0.1
BRIDGE_PORT=8787
BRIDGE_AUTH_TOKEN=$token
BRIDGE_PROCESS_IDLE_TTL_MS=300000
BRIDGE_RECONNECT_GRACE_MS=30000
BRIDGE_LOG_LEVEL=info
BRIDGE_ENABLE_HEALTH_ENDPOINT=true
"@ | Set-Content -Path $envPath -Encoding ascii
    Write-Ok '已创建 bridge/.env 并自动生成 BRIDGE_AUTH_TOKEN（内容不会显示）'
} else {
    $hasToken = Select-String -Path $envPath -Pattern '^BRIDGE_AUTH_TOKEN=.+$' -Quiet
    if (-not $hasToken) { Write-Fail 'bridge/.env 缺少 BRIDGE_AUTH_TOKEN，请补齐后重试' }
    Write-Ok 'bridge/.env 已存在'
}

# 5) 依赖安装
Write-Step '安装 Bridge 依赖（pnpm install --frozen-lockfile）…'
Push-Location $bridgeDir
try {
    Invoke-Expression "$pnpmCmd install --frozen-lockfile" | Out-Null
    Write-Ok '依赖就绪'
} finally {
    Pop-Location
}

if ($CheckOnly) {
    Write-Host ''
    Write-Ok '环境自检全部通过。去掉 -CheckOnly 即可启动 Bridge 并打印配对二维码。'
    exit 0
}

# 6) 后台启动 Bridge，等待就绪后打印配对二维码
Write-Step '启动 Bridge（后台运行，Ctrl+C 一并退出）…'
Push-Location $bridgeDir
try {
    $bridgeJob = Start-Job -ScriptBlock {
        param($dir, $cmd)
        Set-Location $dir
        Invoke-Expression "$cmd start"
    } -ArgumentList $bridgeDir, $pnpmCmd

    $ready = $false
    foreach ($i in 1..20) {
        Start-Sleep -Milliseconds 500
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8787/health' -TimeoutSec 2 -ErrorAction Stop
            $ready = $true
            break
        } catch { }
    }
    if (-not $ready) { Write-Host '[PiPilot] 提示：健康检查未就绪（可能未开启 /health），仍继续尝试打印二维码' -ForegroundColor Yellow }

    Write-Host ''
    Write-Step '在手机 PiPilot 中打开 主机 → 扫码配对，扫描下方二维码：'
    Invoke-Expression "$pnpmCmd pair $(if ($PairHost) { '--host ' + $PairHost })"
    Write-Host ''
    Write-Ok ('Bridge 运行中（任务 ID ' + $bridgeJob.Id + '）。按 Ctrl+C 停止。')

    while ($true) { Start-Sleep -Seconds 5 }
} finally {
    Pop-Location
}
