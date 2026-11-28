param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$TokenFile = "",
    [string]$Token = "",
    [string]$AdbPath = "",
    [string]$Serial = "",
    [int]$DurationMinutes = 120,
    [int]$IntervalSeconds = 30,
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Continue"
if ($BaseUrl -notmatch '^http://(?:127\.0\.0\.1|localhost|[A-Za-z0-9.-]+)(?::\d+)?$') {
    throw "BaseUrl 必须是单一 HTTP 地址，不能包含路径、查询串或凭据。"
}
if ($DurationMinutes -lt 1 -or $IntervalSeconds -lt 5) {
    throw "监测时长至少 1 分钟，采样间隔至少 5 秒。"
}
if ([string]::IsNullOrWhiteSpace($Token) -eq [string]::IsNullOrWhiteSpace($TokenFile)) {
    throw "Token 与 TokenFile 必须且只能提供一个。"
}

$tokenValue = $Token.Trim()
if ([string]::IsNullOrWhiteSpace($Token) -and -not [string]::IsNullOrWhiteSpace($TokenFile)) {
    $resolvedTokenFile = (Resolve-Path -LiteralPath $TokenFile -ErrorAction Stop).Path
    $tokenValue = [IO.File]::ReadAllText($resolvedTokenFile).Trim()
}
if ($tokenValue -notmatch '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$') {
    throw "访问令牌格式无效。"
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputPath = Join-Path $PSScriptRoot "..\build\stability-$stamp.csv"
}
$outDir = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$headers = @{ Authorization = "Bearer $tokenValue"; Accept = "application/json" }
$started = Get-Date
$end = $started.AddMinutes($DurationMinutes)
$firstRevision = $null
$lastRevision = $null
$revisionChanges = 0
$samples = 0
$failures = 0
$screenOffSamples = 0
$lockLostSamples = 0
$htActiveSamples = 0
$maxRss = 0
$minRss = [int]::MaxValue
$recoveryCount = 0
$wasHealthy = $false
$columns = @(
    'timestamp','ok','healthVersion','httpMs','revision','revisionChanged','stale',
    'updatedAt','gatewayState','connectionState','device','channel','rxMHz',
    'receiving','squelchOpen','pid','rssKb','foreground','partialLock','wifiLock',
    'screenLock','wakefulness','displayPower','uptimeSeconds','htPid','htEnabled',
    'htStopped','error'
)
Add-Content -LiteralPath $OutputPath -Value ($columns -join ',') -Encoding UTF8

function Invoke-AdbShell([string]$Command) {
    if ([string]::IsNullOrWhiteSpace($AdbPath)) { return "" }
    $adbArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($Serial)) { $adbArgs += @('-s', $Serial) }
    $adbArgs += @('shell', $Command)
    return ((& $AdbPath @adbArgs 2>$null | Out-String) -replace "`r", "")
}

while ((Get-Date) -lt $end) {
    $sampleTime = Get-Date
    $row = [ordered]@{
        timestamp=$sampleTime.ToString('yyyy-MM-dd HH:mm:ss'); ok=$false; healthVersion=''
        httpMs=''; revision=''; revisionChanged=$false; stale=''; updatedAt='';
        gatewayState=''; connectionState=''; device=''; channel=''; rxMHz='';
        receiving=''; squelchOpen=''; pid=''; rssKb=''; foreground=''; partialLock='';
        wifiLock=''; screenLock=''; wakefulness=''; displayPower=''; uptimeSeconds='';
        htPid=''; htEnabled=''; htStopped=''; error=''
    }
    $healthy = $false
    try {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $health = Invoke-RestMethod -Uri "$BaseUrl/healthz" -TimeoutSec 8
        $watch.Stop()
        $row.healthVersion = [string]$health.version
        $row.httpMs = $watch.ElapsedMilliseconds
        $snap = Invoke-RestMethod -Uri "$BaseUrl/api/v1/snapshot" -Headers $headers -TimeoutSec 8
        $row.ok = [bool]$health.ok -and $snap.stale -eq $false -and
            $snap.gateway.state -eq 'ONLINE' -and $snap.connection.state -eq 'CONNECTED'
        $healthy = [bool]$row.ok
        if ($null -eq $firstRevision -and $null -ne $snap.revision) {
            $firstRevision = $snap.revision
        }
        if ($null -ne $lastRevision -and $snap.revision -ne $lastRevision) {
            $revisionChanges++
            $row.revisionChanged = $true
        }
        $lastRevision = $snap.revision
        $row.revision = $snap.revision
        $row.stale = $snap.stale
        $row.updatedAt = $snap.updatedAt
        $row.gatewayState = $snap.gateway.state
        $row.connectionState = $snap.connection.state
        $row.device = $snap.connection.deviceLabel
        $row.channel = $snap.channel.name
        $row.rxMHz = $snap.channel.rxMHz
        $row.receiving = $snap.radio.status.receiving
        $row.squelchOpen = $snap.radio.status.squelchOpen
    } catch {
        $row.error = ($_.Exception.Message -replace '[,\r\n]+',' ')
    }

    if (-not [string]::IsNullOrWhiteSpace($AdbPath)) {
        try {
            $pidText = (Invoke-AdbShell 'pidof com.bi4byf.n7500link.gateway').Trim()
            $row.pid = $pidText
            if ($pidText) {
                $statusText = Invoke-AdbShell "cat /proc/$pidText/status"
                $rss = [regex]::Match($statusText, 'VmRSS:\s+(\d+)\s+kB')
                if ($rss.Success) {
                    $row.rssKb = [int]$rss.Groups[1].Value
                    if ($row.rssKb -gt $maxRss) { $maxRss = $row.rssKb }
                    if ($row.rssKb -lt $minRss) { $minRss = $row.rssKb }
                }
            }
            $svc = Invoke-AdbShell 'dumpsys activity services com.bi4byf.n7500link.gateway'
            $row.foreground = $svc -match 'isForeground=true'
            $power = Invoke-AdbShell 'dumpsys power'
            $row.partialLock = $power -match "PARTIAL_WAKE_LOCK.*'N1Gateway:radio'"
            $row.screenLock = $power -match "SCREEN_BRIGHT_WAKE_LOCK.*'N1Gateway:screen'"
            $wake = [regex]::Match($power, 'mWakefulness=([^\r\n]+)')
            if ($wake.Success) { $row.wakefulness = $wake.Groups[1].Value.Trim() }
            $display = [regex]::Match($power, 'Display Power: state=([^\r\n]+)')
            if ($display.Success) { $row.displayPower = $display.Groups[1].Value.Trim() }
            $wifi = Invoke-AdbShell 'dumpsys wifi'
            $row.wifiLock = $wifi -match 'WifiLock\{N1Gateway:wifi'
            $uptime = Invoke-AdbShell 'cat /proc/uptime'
            if ($uptime -match '^(\d+(?:\.\d+)?)') {
                $row.uptimeSeconds = [math]::Floor([double]$Matches[1])
            }
            $row.htPid = (Invoke-AdbShell 'pidof com.benshikj.ht').Trim()
            $htPkg = Invoke-AdbShell 'dumpsys package com.benshikj.ht'
            $enabled = [regex]::Match($htPkg, 'enabled=(\d+)')
            if ($enabled.Success) { $row.htEnabled = $enabled.Groups[1].Value }
            $stopped = [regex]::Match($htPkg, 'stopped=(\w+)')
            if ($stopped.Success) { $row.htStopped = $stopped.Groups[1].Value }

            if ([string]$row.wakefulness -ne 'Awake' -or [string]$row.displayPower -ne 'ON') {
                $screenOffSamples++
            }
            if (-not (([bool]$row.partialLock) -and ([bool]$row.wifiLock))) {
                $lockLostSamples++
            }
            if (-not [string]::IsNullOrWhiteSpace([string]$row.htPid)) {
                $htActiveSamples++
            }
        } catch {
            if ($row.error) { $row.error += ' | ' }
            $row.error += ($_.Exception.Message -replace '[,\r\n]+',' ')
        }
    }

    if ($healthy -and -not $wasHealthy -and $samples -gt 0) { $recoveryCount++ }
    $wasHealthy = $healthy
    if (-not $healthy) { $failures++ }
    $samples++
    $values = @($columns | ForEach-Object {
        $value = [string]$row[$_]
        '"' + ($value -replace '"','""') + '"'
    })
    Add-Content -LiteralPath $OutputPath -Value ($values -join ',') -Encoding UTF8

    $remaining = [int]($end - (Get-Date)).TotalSeconds
    if ($remaining -le 0) { break }
    Start-Sleep -Seconds ([Math]::Min($IntervalSeconds, $remaining))
}

$summary = [ordered]@{
    file = (Resolve-Path -LiteralPath $OutputPath).Path
    started = $started.ToString('yyyy-MM-dd HH:mm:ss')
    ended = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    durationMinutes = $DurationMinutes
    samples = $samples
    healthySamples = $samples - $failures
    failures = $failures
    recoveries = $recoveryCount
    revisionChanges = $revisionChanges
    firstRevision = $firstRevision
    lastRevision = $lastRevision
    screenOffSamples = $screenOffSamples
    lockLostSamples = $lockLostSamples
    htActiveSamples = $htActiveSamples
    minRssKb = if ($minRss -eq [int]::MaxValue) { $null } else { $minRss }
    maxRssKb = if ($maxRss -gt 0) { $maxRss } else { $null }
}
$summaryPath = [IO.Path]::ChangeExtension($OutputPath, '.summary.json')
$summary | ConvertTo-Json | Set-Content -LiteralPath $summaryPath -Encoding UTF8
$summary | ConvertTo-Json -Compress