param(
    [string]$Token = "",
    [string]$TokenFile = "",
    [string]$BaseUrl = "http://127.0.0.1:8710",
    [switch]$ExecuteWrites
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($Token) -eq [string]::IsNullOrWhiteSpace($TokenFile)) {
    throw "Token 与 TokenFile 必须且只能提供一个。"
}
$tokenValue = $Token.Trim()
if ([string]::IsNullOrWhiteSpace($tokenValue) -and -not [string]::IsNullOrWhiteSpace($TokenFile)) {
    $resolvedTokenFile = (Resolve-Path -LiteralPath $TokenFile -ErrorAction Stop).Path
    $tokenValue = [IO.File]::ReadAllText($resolvedTokenFile).Trim()
}
if ($tokenValue -notmatch '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$') {
    throw "访问令牌格式无效。"
}
$base = $BaseUrl.Trim().TrimEnd('/')
if ($base -notmatch '^http://[^/]+$') {
    throw "BaseUrl 必须是单一主机 HTTP 地址，不能包含路径。"
}

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseCookies = $true
$handler.CookieContainer = [System.Net.CookieContainer]::new()
$handler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(10)

function Send-Request {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Body,
        [string]$ContentType = "application/json"
    )
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), $base + $Path)
    $request.Headers.UserAgent.ParseAdd("N1RemoteControl-ControlValidation")
    if ($null -ne $Body) {
        $request.Content = [System.Net.Http.StringContent]::new($Body, [Text.Encoding]::UTF8, $ContentType)
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    [PSCustomObject]@{
        Status = [int]$response.StatusCode
        Body = $text
        Response = $response
    }
}

function Get-Snapshot {
    $result = Send-Request GET "/api/v1/snapshot" $null
    if ($result.Status -ne 200) {
        throw "读取快照失败：HTTP $($result.Status)"
    }
    $json = $result.Body | ConvertFrom-Json
    [PSCustomObject]@{
        Volume = $json.volume
        Scan = $json.radio.settings.scan
        Side = $json.radio.settings.dualMode
        ChannelName = $json.channel.name
        GatewayState = $json.gateway.state
        ConnectionState = $json.connection.state
        Stale = $json.stale
        Receiving = $json.radio.status.receiving
        Transmitting = $json.radio.status.transmitting
    }
}

function Wait-Control {
    param([string]$OperationId)
    $deadline = [DateTime]::UtcNow.AddSeconds(16)
    $last = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        $result = Send-Request GET "/api/v1/control" $null
        if ($result.Status -ne 200) {
            throw "读取控制状态失败：HTTP $($result.Status)"
        }
        $last = $result.Body | ConvertFrom-Json
        if ($last.operationId -eq $OperationId -and -not [bool]$last.busy) {
            return $last
        }
        Start-Sleep -Milliseconds 250
    }
    $state = if ($null -eq $last) { "unknown" } else { [string]$last.state }
    throw "控制操作等待超时：$OperationId，最后状态 $state"
}

function Submit-Control {
    param(
        [string]$Action,
        [hashtable]$Payload,
        [string]$Label
    )
    if (-not $ExecuteWrites) {
        throw "写入测试需要显式指定 -ExecuteWrites。"
    }
    $payload.confirm = $true
    $body = $Payload | ConvertTo-Json -Compress
    $result = Send-Request POST "/api/v1/control/$Action" $body
    if ($result.Status -ne 202) {
        throw "$Label 未被受理：HTTP $($result.Status) $($result.Body)"
    }
    $accepted = $result.Body | ConvertFrom-Json
    if (-not [bool]$accepted.accepted -or [string]::IsNullOrWhiteSpace([string]$accepted.operationId)) {
        throw "$Label 未返回有效操作编号。"
    }
    $operation = Wait-Control ([string]$accepted.operationId)
    [PSCustomObject]@{
        Label = $Label
        OperationId = $operation.operationId
        State = $operation.state
        Reason = $operation.reason
        Baseline = $operation.baselineDisplay
        Expected = $operation.expectedDisplay
        Observed = $operation.observedDisplay
        Sent = $operation.sent
        RollbackSent = $operation.rollbackSent
    }
}

function Assert-ControlSuccess {
    param($Operation, [string]$ExpectedState = "READBACK_OK")
    if ([string]$Operation.State -ne $ExpectedState -or [bool]$Operation.RollbackSent) {
        throw "$($Operation.Label) 未正常完成：state=$($Operation.State) reason=$($Operation.Reason) rollbackSent=$($Operation.RollbackSent)"
    }
}

try {
    $login = Send-Request POST "/api/v1/session" "token=$([Uri]::EscapeDataString($tokenValue))" "application/x-www-form-urlencoded"
    if ($login.Status -ne 303) {
        throw "登录失败：HTTP $($login.Status)"
    }

    $initial = Get-Snapshot
    if ($initial.GatewayState -ne "ONLINE" -or $initial.ConnectionState -ne "CONNECTED" -or [bool]$initial.Stale) {
        throw "电台未处于可用在线状态，停止写入测试。"
    }
    if ([bool]$initial.Receiving -or [bool]$initial.Transmitting) {
        throw "电台正在接收或发射，停止低风险写入测试。"
    }
    if ($null -eq $initial.Volume -or $null -eq $initial.Scan -or $initial.Side -notin @("A", "B")) {
        throw "基线字段不完整，停止写入测试。"
    }

    $control = Send-Request GET "/api/v1/control" $null
    if ($control.Status -ne 200) { throw "控制接口不可用：HTTP $($control.Status)" }
    $controlView = $control.Body | ConvertFrom-Json
    if (-not [bool]$controlView.available -or [bool]$controlView.busy) {
        throw "控制接口忙或不可用，停止写入测试。"
    }

    $baselineVolume = [int]$initial.Volume
    $baselineScan = [bool]$initial.Scan
    $baselineSide = [string]$initial.Side
    Write-Output ("BASELINE volume={0} scan={1} side={2} channel={3}" -f $baselineVolume, $baselineScan, $baselineSide, $initial.ChannelName)

    $volumeTarget = if ($baselineVolume -eq 4) { 3 } else { 4 }
    $volumeChange = Submit-Control "volume" @{ volume = $volumeTarget } "volume-change"
    Assert-ControlSuccess $volumeChange
    $afterVolume = Get-Snapshot
    if ([int]$afterVolume.Volume -ne $volumeTarget) { throw "音量回读与目标不一致。" }
    Write-Output ("PASS volume {0}->{1} state={2} sent={3} rollbackSent={4}" -f $baselineVolume, $volumeTarget, $volumeChange.State, $volumeChange.Sent, $volumeChange.RollbackSent)

    $volumeRestore = Submit-Control "volume" @{ volume = $baselineVolume } "volume-restore"
    Assert-ControlSuccess $volumeRestore
    $afterVolumeRestore = Get-Snapshot
    if ([int]$afterVolumeRestore.Volume -ne $baselineVolume) { throw "音量未恢复基线。" }
    Write-Output ("PASS volume-restore target={0} state={1}" -f $baselineVolume, $volumeRestore.State)

    $scanTarget = -not $baselineScan
    $scanChange = Submit-Control "scan" @{ scan = $scanTarget } "scan-change"
    Assert-ControlSuccess $scanChange
    $afterScan = Get-Snapshot
    if ([bool]$afterScan.Scan -ne $scanTarget) { throw "扫描设置回读与目标不一致。" }
    Write-Output ("PASS scan {0}->{1} state={2} sent={3} rollbackSent={4}" -f $baselineScan, $scanTarget, $scanChange.State, $scanChange.Sent, $scanChange.RollbackSent)

    $scanRestore = Submit-Control "scan" @{ scan = $baselineScan } "scan-restore"
    Assert-ControlSuccess $scanRestore
    $afterScanRestore = Get-Snapshot
    if ([bool]$afterScanRestore.Scan -ne $baselineScan) { throw "扫描设置未恢复基线。" }
    Write-Output ("PASS scan-restore target={0} state={1}" -f $baselineScan, $scanRestore.State)

    $sideTarget = if ($baselineSide -eq "A") { "B" } else { "A" }
    $sideChange = Submit-Control "side" @{ side = $sideTarget } "side-change"
    Assert-ControlSuccess $sideChange
    $afterSide = Get-Snapshot
    if ([string]$afterSide.Side -ne $sideTarget) { throw "A/B 设置回读与目标不一致。" }
    Write-Output ("PASS side {0}->{1} state={2} sent={3} rollbackSent={4}" -f $baselineSide, $sideTarget, $sideChange.State, $sideChange.Sent, $sideChange.RollbackSent)

    $sideRestore = Submit-Control "side" @{ side = $baselineSide } "side-restore"
    Assert-ControlSuccess $sideRestore
    $afterSideRestore = Get-Snapshot
    if ([string]$afterSideRestore.Side -ne $baselineSide) { throw "A/B 设置未恢复基线。" }
    Write-Output ("PASS side-restore target={0} state={1}" -f $baselineSide, $sideRestore.State)

    $final = Get-Snapshot
    if ([int]$final.Volume -ne $baselineVolume -or [bool]$final.Scan -ne $baselineScan -or [string]$final.Side -ne $baselineSide) {
        throw "最终快照未全部恢复基线。"
    }
    Write-Output ("FINAL volume={0} scan={1} side={2} channel={3} stale={4}" -f $final.Volume, $final.Scan, $final.Side, $final.ChannelName, $final.Stale)
    Write-Output "PASS: 音量、扫描、A/B 三项受限写入及恢复全部通过"
} finally {
    $tokenValue = $null
    $client.Dispose()
    $handler.Dispose()
}
