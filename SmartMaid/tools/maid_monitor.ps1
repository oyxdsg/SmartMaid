<#
.SYNOPSIS
    SmartMaid 女仆移动 + 资源监测脚本
.DESCRIPTION
    周期性采样：
      1) 女仆移动决策变量 —— 从游戏日志(latest.log)增量抓取 [SmartMaid-Debug] 行，
         解析动作/目标/速度/冲刺/跳跃/滞空/剩余路径等，并统计 findPath OK/FAIL、卡死次数、A* 耗时。
      2) 资源 —— 游戏进程(Minecraft) CPU%、内存(工作集/私有)、句柄、线程；系统 CPU%、可用内存。
    采样结果实时显示到控制台，并写 CSV 存档到脚本同目录 logs/ 下。
    用法示例：
      .\maid_monitor.ps1 -Duration 120 -Interval 2
      .\maid_monitor.ps1 -Duration 30 -Interval 1 -MinecraftDir "D:\MC\.minecraft"
      Ctrl+C 结束（会先打印统计摘要）。
.NOTES
    女仆调试输出启用方式：在游戏目录 config/smartmaid/ 下创建空文件 debug.flag（删除即关闭）。
    本脚本仅作监测，不修改任何文件。PowerShell 5.1 兼容。
#>
param(
    [int]$Duration = 60,                            # 监测总时长（秒）
    [int]$Interval = 2,                             # 采样间隔（秒）
    [string]$MinecraftDir = "",                      # 游戏目录；留空则自动探测（环境变量 SMARTMAID_MC_DIR → tools/local_paths.json → %APPDATA%\.minecraft）
    [string]$ProcessName = "javaw",                 # Minecraft 进程名（HMCL 启动的 MC 为 javaw）
    [string]$LogDir = ""                            # CSV 输出目录，默认脚本同目录 logs/
)

$ErrorActionPreference = "Stop"
$DebugTag = "[SmartMaid-Debug]"

# ---------- 游戏目录自动探测（不把个人路径写死在仓库里） ----------
if (-not $MinecraftDir) {
    $cands = @()
    if ($env:SMARTMAID_MC_DIR) { $cands += $env:SMARTMAID_MC_DIR }
    $cfgPath = Join-Path $PSScriptRoot "local_paths.json"
    if (Test-Path -LiteralPath $cfgPath) {
        try {
            $cfg = Get-Content -LiteralPath $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($cfg.minecraft_dir) { $cands += $cfg.minecraft_dir }
        } catch { }
    }
    if ($env:APPDATA) { $cands += (Join-Path $env:APPDATA ".minecraft") }
    foreach ($c in $cands) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c "logs\latest.log"))) {
            $MinecraftDir = $c
            break
        }
    }
    if (-not $MinecraftDir) {
        Write-Host "[x] 找不到游戏目录。请用 -MinecraftDir 指定，或设置环境变量 SMARTMAID_MC_DIR，或写 tools/local_paths.json"
        exit 2
    }
}

# ---------- 输出目录 ----------
if (-not $LogDir) {
    $LogDir = Join-Path $PSScriptRoot "logs"
}
if (-not (Test-Path -LiteralPath $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}
$csvPath = Join-Path $LogDir ("maid_monitor_" + (Get-Date -Format "yyyyMMdd_HHmmss") + ".csv")

# ---------- 游戏日志 ----------
$logFile = Join-Path $MinecraftDir "logs\latest.log"
if (-not (Test-Path -LiteralPath $logFile)) {
    Write-Host "[警告] 未找到游戏日志: $logFile" -ForegroundColor Yellow
    Write-Host "        请确认已启动游戏（26.2 Fabric + smartmaid mod）。将只监测资源。" -ForegroundColor Yellow
}

# ---------- 日志增量读取器 ----------
$stream = $null
if (Test-Path -LiteralPath $logFile) {
    try {
        $stream = [System.IO.File]::Open($logFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        $stream.Seek(0, [System.IO.SeekOrigin]::End) | Out-Null
    } catch {
        $stream = $null
    }
}

function Get-NewDebugLines {
    if ($null -eq $stream) { return @() }
    try {
        $stream.Seek(0, [System.IO.SeekOrigin]::Current) | Out-Null
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8, $true, 4096, $true)
        $lines = New-Object System.Collections.Generic.List[string]
        while ($null -ne ($line = $reader.ReadLine())) {
            if ($line.Contains($DebugTag)) { $lines.Add($line) }
        }
        return $lines
    } catch {
        return @()
    }
}

# ---------- 女仆 debug 行解析 ----------
function Parse-ExecLine([string]$line) {
    $m = [regex]::Match($line,
        'exec pos=\((?<px>-?[\d.]+),(?<py>-?[\d.]+),(?<pz>-?[\d.]+)\) ' +
        'target=\((?<tx>-?\d+),(?<ty>-?\d+),(?<tz>-?\d+)\) ' +
        'action=(?<act>\w+) dist=(?<dist>\d+) sprint=(?<sprint>\w+) jump=(?<jump>\w+) ' +
        'speedMul=(?<speed>[\d.]+) onGround=(?<ground>\w+) pathLeft=(?<left>\d+) ' +
        'vel=\((?<vx>-?[\d.]+),(?<vy>-?[\d.]+),(?<vz>-?[\d.]+)\)')
    if (-not $m.Success) { return $null }
    return [pscustomobject]@{
        Action   = $m.Groups['act'].Value
        Dist     = $m.Groups['dist'].Value
        Sprint   = $m.Groups['sprint'].Value
        Jump     = $m.Groups['jump'].Value
        OnGround = $m.Groups['ground'].Value
        PathLeft = [int]$m.Groups['left'].Value
        Speed    = $m.Groups['speed'].Value
        Pos      = "($($m.Groups['px'].Value),$($m.Groups['py'].Value),$($m.Groups['pz'].Value))"
        Target   = "($($m.Groups['tx'].Value),$($m.Groups['ty'].Value),$($m.Groups['tz'].Value))"
        Vel      = "($($m.Groups['vx'].Value),$($m.Groups['vy'].Value),$($m.Groups['vz'].Value))"
    }
}

# ---------- 进程选择 ----------
$mcProcesses = @(Get-CimInstance Win32_Process -Filter "Name='$ProcessName.exe'" -ErrorAction SilentlyContinue)
$gameDirNorm = ($MinecraftDir -replace '\\','/').ToLower()
$chosen = $null
foreach ($proc in $mcProcesses) {
    if ($proc.CommandLine -and $proc.CommandLine.ToLower().Contains($gameDirNorm)) {
        $chosen = $proc
        break
    }
}
if ($null -eq $chosen -and $mcProcesses.Count -gt 0) {
    $chosen = $mcProcesses[0]   # 找不到匹配就取第一个同进程名
}
if ($null -ne $chosen) {
    Write-Host "[信息] 监测进程: $($chosen.Name) (PID $($chosen.ProcessId)) cmd: $($chosen.CommandLine)" -ForegroundColor Cyan
} else {
    Write-Host "[警告] 未找到进程 $ProcessName.exe，将只监测系统资源与日志。" -ForegroundColor Yellow
}

$sysCpu = Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 1 -ErrorAction SilentlyContinue
if (-not $sysCpu) { $sysCpuAvail = $false } else { $sysCpuAvail = $true }

# ---------- 统计 ----------
$stat = @{
    findPathOK   = 0
    findPathFAIL = 0
    repathOK     = 0
    repathFAIL   = 0
    stuck        = 0
    tookSamples  = New-Object System.Collections.Generic.List[double]
}
$lastExec = $null
$csvLines = New-Object System.Collections.Generic.List[string]
$csvHeader = "Time,Action,Dist,Sprint,Jump,OnGround,PathLeft,Speed,Pos,Target,Vel,FP_OK,FP_FAIL,Repath,Stuck,MC_CPU%,MC_WS_MB,MC_Priv_MB,MC_Handles,MC_Threads,Sys_CPU%,SysAvail_MB"

function Add-CsvLine([hashtable]$vals) {
    $row = @(
        (Get-Date -Format "HH:mm:ss"),
        $vals.Action, $vals.Dist, $vals.Sprint, $vals.Jump, $vals.OnGround, $vals.PathLeft, $vals.Speed,
        $vals.Pos, $vals.Target, $vals.Vel,
        $stat.findPathOK, $stat.findPathFAIL, ($stat.repathOK + $stat.repathFAIL), $stat.stuck,
        $vals.McCpu, $vals.McWs, $vals.McPriv, $vals.McHandles, $vals.McThreads,
        $vals.SysCpu, $vals.SysAvail
    ) -join ","
    $csvLines.Add($row)
}

function Update-Stats([string[]]$lines) {
    foreach ($line in $lines) {
        if ($line -match 'findPath OK .*nodes=(\d+).*took=([\d.]+)ms') {
            $stat.findPathOK++
            $stat.tookSamples.Add([double]$Matches[2])
        } elseif ($line -match 'findPath FAIL') {
            $stat.findPathFAIL++
        } elseif ($line -match 'repath OK') {
            $stat.repathOK++
        } elseif ($line -match 'repath FAIL') {
            $stat.repathFAIL++
        } elseif ($line -match 'STUCK detected') {
            $stat.stuck++
        }
        $parsed = Parse-ExecLine $line
        if ($null -ne $parsed) { $lastExec = $parsed }
    }
}

function Show-Header {
    Write-Host ""
    Write-Host ("{0,-8} {1,-9} {2,-5} {3,-4} {4,-5} {5,-5} {6,-4} {7,-6} {8,-20} {9,-20} {10,-10} {11,-8} {12,-8} {13,-8} {14,-9}" -f `
        "时间","动作","距","冲刺","跳","地面","剩","速","位置","目标","水平速度","MC_CPU%","MC_WS_MB","Sys_CPU%","SysAvail_MB") -ForegroundColor DarkCyan
}

function Show-Row($v) {
    Write-Host ("{0,-8} {1,-9} {2,-5} {3,-4} {4,-5} {5,-5} {6,-4} {7,-6} {8,-20} {9,-20} {10,-10} {11,-8} {12,-8} {13,-8} {14,-9}" -f `
        $v.Time, $v.Action, $v.Dist, $v.Sprint, $v.Jump, $v.OnGround, $v.PathLeft, $v.Speed, $v.Pos, $v.Target, $v.Vel,
        $v.McCpu, $v.McWs, $v.SysCpu, $v.SysAvail)
}

# ---------- 采样循环 ----------
Show-Header
$deadline = (Get-Date).AddSeconds($Duration)
$firstSample = $true
$prevCpuSec = $null
$prevSample = (Get-Date)

while ((Get-Date) -lt $deadline) {
    # 女仆变量
    $newLines = Get-NewDebugLines
    Update-Stats $newLines

    # 进程资源
    $mcCpu = 0.0; $mcWs = 0; $mcPriv = 0; $mcHandles = 0; $mcThreads = 0
    if ($null -ne $chosen) {
        try {
            $proc = Get-Process -Id $chosen.ProcessId -ErrorAction Stop
            $mcWs = [math]::Round($proc.WorkingSet64 / 1MB, 1)
            $mcPriv = [math]::Round($proc.PrivateMemorySize64 / 1MB, 1)
            $mcHandles = $proc.HandleCount
            $mcThreads = $proc.Threads.Count
            $nowSec = $proc.TotalProcessorTime.TotalSeconds
            if ($null -ne $prevCpuSec -and -not $firstSample) {
                $dt = ((Get-Date) - $prevSample).TotalSeconds
                if ($dt -gt 0) {
                    $mcCpu = [math]::Round((($nowSec - $prevCpuSec) / $dt) / [Environment]::ProcessorCount * 100, 1)
                }
            }
            $prevCpuSec = $nowSec
        } catch {
            $mcWs = -1; $mcPriv = -1
        }
    }

    # 系统资源
    $sysCpuVal = -1.0; $sysAvail = -1
    try {
        if ($sysCpuAvail) {
            $sysCpuVal = [math]::Round((Get-Counter '\Processor(_Total)\% Processor Time' -SampleInterval 1 -MaxSamples 1).CounterSamples[0].CookedValue, 1)
        }
        $os = Get-CimInstance Win32_OperatingSystem
        $sysAvail = [math]::Round($os.FreePhysicalMemory / 1KB, 1)
    } catch { }

    $v = [pscustomobject]@{
        Time = (Get-Date -Format "HH:mm:ss")
        Action = if ($lastExec) { $lastExec.Action } else { "-" }
        Dist = if ($lastExec) { $lastExec.Dist } else { "-" }
        Sprint = if ($lastExec) { $lastExec.Sprint } else { "-" }
        Jump = if ($lastExec) { $lastExec.Jump } else { "-" }
        OnGround = if ($lastExec) { $lastExec.OnGround } else { "-" }
        PathLeft = if ($lastExec) { $lastExec.PathLeft } else { "-" }
        Speed = if ($lastExec) { $lastExec.Speed } else { "-" }
        Pos = if ($lastExec) { $lastExec.Pos } else { "-" }
        Target = if ($lastExec) { $lastExec.Target } else { "-" }
        Vel = if ($lastExec) { $lastExec.Vel } else { "-" }
        McCpu = $mcCpu; McWs = $mcWs; McPriv = $mcPriv; McHandles = $mcHandles; McThreads = $mcThreads
        SysCpu = $sysCpuVal; SysAvail = $sysAvail
    }

    Show-Row $v

    $h = @{
        Action = $v.Action; Dist = $v.Dist; Sprint = $v.Sprint; Jump = $v.Jump; OnGround = $v.OnGround
        PathLeft = $v.PathLeft; Speed = $v.Speed; Pos = $v.Pos; Target = $v.Target; Vel = $v.Vel
        McCpu = $mcCpu; McWs = $mcWs; McPriv = $mcPriv; McHandles = $mcHandles; McThreads = $mcThreads
        SysCpu = $sysCpuVal; SysAvail = $sysAvail
    }
    Add-CsvLine $h

    $firstSample = $false
    $prevSample = Get-Date
    Start-Sleep -Seconds $Interval
}

# ---------- 收尾 ----------
if ($stream) { $stream.Dispose() }

$csvLines.Insert(0, $csvHeader)
[System.IO.File]::WriteAllLines($csvPath, $csvLines, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "============= 统计摘要 =============" -ForegroundColor Cyan
Write-Host "findPath 成功 : $($stat.findPathOK)" -ForegroundColor Green
Write-Host "findPath 失败 : $($stat.findPathFAIL)" -ForegroundColor $(if ($stat.findPathFAIL -gt 0) { "Red" } else { "Green" })
Write-Host "repath(重算)  : $($stat.repathOK + $stat.repathFAIL) (OK $($stat.repathOK) / FAIL $($stat.repathFAIL))"
Write-Host "卡死(STUCK)   : $($stat.stuck)" -ForegroundColor $(if ($stat.stuck -gt 0) { "Yellow" } else { "Green" })
if ($stat.tookSamples.Count -gt 0) {
    $avg = ($stat.tookSamples | Measure-Object -Average).Average
    $max = ($stat.tookSamples | Measure-Object -Maximum).Maximum
    Write-Host "A* 耗时        : avg $([math]::Round($avg,2))ms / max $([math]::Round($max,2))ms (采样 $($stat.tookSamples.Count) 次)"
}
Write-Host "CSV 已保存     : $csvPath" -ForegroundColor Cyan
