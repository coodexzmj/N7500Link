param(
    [Parameter(Mandatory = $true)]
    [string]$Token,
    [string]$Adb = "C:\adb-n1\platform-tools\adb.exe",
    [int]$LocalPort = 18710,
    [int]$DevicePort = 8710,
    [string]$BaseUrl = ""
)

$ErrorActionPreference = "Stop"
if ($Token.Length -lt 20 -or $Token.Length -gt 256) {
    throw "访问令牌长度无效。"
}

$useAdbForward = [string]::IsNullOrWhiteSpace($BaseUrl)
if ($useAdbForward) {
    $base = "http://127.0.0.1:$LocalPort"
} else {
    $base = $BaseUrl.Trim().TrimEnd("/")
    if ($base -notmatch '^http://[^/]+$') {
        throw "BaseUrl 必须是单一本机或局域网 HTTP 地址，不能包含路径。"
    }
}
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseCookies = $true
$handler.CookieContainer = [System.Net.CookieContainer]::new()
$handler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(10)

function Send-GatewayRequest {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Body
    )
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), $base + $Path)
    $request.Headers.UserAgent.ParseAdd("N1Gateway-Stage3-Validation")
    if ($null -ne $Body) {
        $request.Content = [System.Net.Http.StringContent]::new(
            $Body, [Text.Encoding]::UTF8, "application/x-www-form-urlencoded")
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    [PSCustomObject]@{ Status = [int]$response.StatusCode; Body = $text; Response = $response }
}

try {
    if ($useAdbForward) {
        & $Adb forward --remove "tcp:$LocalPort" 2>$null | Out-Null
        & $Adb forward "tcp:$LocalPort" "tcp:$DevicePort" | Out-Null
    }

    $health = Send-GatewayRequest GET "/healthz" $null
    $loginPage = Send-GatewayRequest GET "/login" $null
    $appJs = Send-GatewayRequest GET "/assets/app.js" $null
    $style = Send-GatewayRequest GET "/assets/style.css" $null
    $anonymous = Send-GatewayRequest GET "/" $null
    $login = Send-GatewayRequest POST "/api/v1/session" "token=$Token"
    $authorized = Send-GatewayRequest GET "/" $null
    $snapshotResult = Send-GatewayRequest GET "/api/v1/snapshot" $null
    $snapshot = $snapshotResult.Body | ConvertFrom-Json

    $sseRequest = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Get, $base + "/api/v1/events")
    $sseRequest.Headers.UserAgent.ParseAdd("N1Gateway-Stage3-Validation")
    $sseResponse = $client.SendAsync(
        $sseRequest, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
    ).GetAwaiter().GetResult()
    $sseStatus = [int]$sseResponse.StatusCode
    $events = @{ ready = $false; snapshot = $false; heartbeat = $false }
    if ($sseStatus -eq 200) {
        $stream = $sseResponse.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $reader = [System.IO.StreamReader]::new($stream)
        $deadline = [DateTime]::UtcNow.AddSeconds(18)
        $readTask = $reader.ReadLineAsync()
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($readTask.IsCompleted) {
                $line = $readTask.Result
                if ($null -eq $line) { break }
                if ($line -match "^event:\s*(\w+)$" -and $events.ContainsKey($Matches[1])) {
                    $events[$Matches[1]] = $true
                }
                if ($events.ready -and $events.snapshot -and $events.heartbeat) { break }
                $readTask = $reader.ReadLineAsync()
            } else {
                Start-Sleep -Milliseconds 100
            }
        }
        $reader.Dispose()
        $stream.Dispose()
    }
    $sseResponse.Dispose()

    $logout = Send-GatewayRequest DELETE "/api/v1/session" $null
    $checks = [ordered]@{
        "healthz=200" = ($health.Status -eq 200)
        "login_page=200" = ($loginPage.Status -eq 200)
        "app.js=200" = ($appJs.Status -eq 200)
        "style.css=200" = ($style.Status -eq 200)
        "anonymous_root=303" = ($anonymous.Status -eq 303)
        "login=303" = ($login.Status -eq 303)
        "authenticated_root=200" = ($authorized.Status -eq 200)
        "snapshot=200" = ($snapshotResult.Status -eq 200)
        "snapshot_live" = ($snapshot.stale -eq $false)
        "gateway_online" = ($snapshot.gateway.state -eq "ONLINE")
        "connection_connected" = ($snapshot.connection.state -eq "CONNECTED")
        "sse=200" = ($sseStatus -eq 200)
        "sse.ready" = ([bool]$events.ready)
        "sse.snapshot" = ([bool]$events.snapshot)
        "sse.heartbeat" = ([bool]$events.heartbeat)
        "logout=204" = ($logout.Status -eq 204)
    }
    $failed = @()
    foreach ($name in $checks.Keys) {
        $result = if ($checks[$name]) { "PASS" } else { "FAIL" }
        "${name}: $result"
        if (-not $checks[$name]) { $failed += $name }
    }
    if ($failed.Count -gt 0) {
        throw "接口验证失败：" + ($failed -join ", ")
    }
    "HTTP/API/SSE 实机验证全部通过"
} finally {
    $client.Dispose()
    $handler.Dispose()
    if ($useAdbForward) {
        & $Adb forward --remove "tcp:$LocalPort" 2>$null | Out-Null
    }
    foreach ($result in @($health, $loginPage, $appJs, $style, $anonymous, $login,
            $authorized, $snapshotResult, $logout)) {
        if ($null -ne $result -and $null -ne $result.Response) { $result.Response.Dispose() }
    }
}
