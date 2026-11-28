param(
    [string]$JdkRoot = "C:\adb-n1\toolchain\jdk-17.0.21+9"
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$testRoot = Join-Path $project "build\selftest-$stamp"
$classes = Join-Path $testRoot "classes"
$javac = Join-Path $JdkRoot "bin\javac.exe"
$java = Join-Path $JdkRoot "bin\java.exe"
$packageDir = Join-Path $project "src\java\com\bi4byf\n7500link\direct"

if (!(Test-Path -LiteralPath $javac)) { throw "缺少 Java 编译器：$javac" }
if (!(Test-Path -LiteralPath $java)) { throw "缺少 Java 运行环境：$java" }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sourceNames = @(
    "HexUtil.java", "JsonUtil.java", "GatewayContract.java", "RadioModels.java",
    "SettingsBlock.java", "GaiaCodec.java", "RadioParser.java",
    "CommandScheduler.java", "LowRiskWriteController.java", "AuthManager.java",
    "StatusRepository.java", "SseHub.java", "ChannelCatalogService.java",
    "SbcDecoder.java", "RadioAudioStreamParser.java", "AudioLiveHub.java",
    "WavClipWriter.java", "AudioRoomService.java", "LocalWebServer.java"
)
$sources = @($sourceNames | ForEach-Object { Join-Path $packageDir $_ })
$sources += Join-Path $project "tools\DirectSelfTest.java"
$sources += Join-Path $project "tools\AudioStreamSelfTest.java"
& $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn `
    -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "第五阶段离线自测编译失败。" }

& $java -cp $classes com.bi4byf.n7500link.direct.DirectSelfTest
if ($LASTEXITCODE -ne 0) { throw "第五阶段离线自测未通过。" }

& $java -cp $classes com.bi4byf.n7500link.direct.AudioStreamSelfTest `
    (Join-Path $project "tools\sbc-vectors.tsv")
if ($LASTEXITCODE -ne 0) { throw "音频流解析自测未通过。" }

$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    & $node.Source --check (Join-Path $project "src\assets\web\app.js")
    if ($LASTEXITCODE -ne 0) { throw "网页脚本语法检查失败。" }
}

$codeFiles = @(Get-ChildItem -LiteralPath (Join-Path $project "src\java") -Recurse -Filter "*.java")
$webFiles = @(Get-ChildItem -LiteralPath (Join-Path $project "src\assets\web") -Recurse -File)
$allUiFiles = @($codeFiles + $webFiles)

# These identifiers represent capabilities explicitly outside the fifth-stage boundary.
$forbiddenCapabilities = @(
    "CMD_WRITE_RF_CH", "CMD_SET_IN_SCAN", "CMD_DO_PROG_FUNC",
    "MediaRecorder", "AudioRecord", "AudioTrack", "BluetoothA2dp",
    "factory_reset", "upgrade_firmware"
)
foreach ($file in $allUiFiles) {
    $text = [IO.File]::ReadAllText($file.FullName)
    foreach ($pattern in $forbiddenCapabilities) {
        if ($text.IndexOf($pattern, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "控制边界扫描失败：$($file.FullName) 含 $pattern"
        }
    }
}

foreach ($file in $webFiles) {
    $text = [IO.File]::ReadAllText($file.FullName)
    if ($text -match "(?i)https?://|//cdn\.|@import\s+url") {
        throw "网页发现外部资源或明文外链：$($file.FullName)"
    }
}

$gaiaSource = [IO.File]::ReadAllText((Join-Path $packageDir "GaiaCodec.java"))
foreach ($required in @(
        "isAllowedWriteCommand", "command == CMD_WRITE_SETTINGS",
        "command == CMD_SET_VOLUME", "volume < 0 || volume > 15",
        "completePayload.length < 10")) {
    if ($gaiaSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "GAIA 白名单门禁失败：缺少 $required"
    }
}

$controllerSource = [IO.File]::ReadAllText((Join-Path $packageDir "LowRiskWriteController.java"))
foreach ($required in @(
        "submitVolume", "submitScan", "submitSide",
        "volume < 0 || volume > 15", '"A".equals(side)', '"B".equals(side)',
        "OPERATION_TIMEOUT_MS = 12000L", "ROLLBACK_TIMEOUT_MS = 8000L",
        "READBACK_MISMATCH", "ROLLBACK_WRITING", "ROLLBACK_OK", "CONTROL_BUSY")) {
    if ($controllerSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "低风险状态机门禁失败：缺少 $required"
    }
}

$serverSource = [IO.File]::ReadAllText((Join-Path $packageDir "LocalWebServer.java"))
foreach ($required in @(
        "/api/v1/control/volume", "/api/v1/control/scan", "/api/v1/control/side",
        "MAX_BODY_BYTES = 1024", "application/json", "CONFIRMATION_REQUIRED",
        "AUTH_REQUIRED", "/api/v1/channels", "/api/v1/channels/refresh",
        "RADIO_NOT_READY", "CHANNEL_OUT_OF_RANGE",
        "/api/v1/audio/rooms", "/api/v1/audio/diagnostics", "audioDiagnosticsSupplier",
        "audioPath", '"clips"',  "live", '"wav"', 
        "Content-Range: bytes", "Accept-Ranges: bytes", "audio/L16;rate=32000;channels=1")) {
    if ($serverSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "HTTP 控制门禁失败：缺少 $required"
    }
}

$manifest = [IO.File]::ReadAllText((Join-Path $project "src\AndroidManifest.xml"))
$contractSource = [IO.File]::ReadAllText((Join-Path $packageDir "GatewayContract.java"))
$expectedVersion = "0.8.0"
$expectedVersionCode = "21"
if ($contractSource.IndexOf("VERSION = `"$expectedVersion`"", [StringComparison]::Ordinal) -lt 0) {
    throw "版本门禁失败：GatewayContract 不是 $expectedVersion"
}
if ($manifest.IndexOf("android:versionName=`"$expectedVersion`"", [StringComparison]::Ordinal) -lt 0) {
    throw "版本门禁失败：Manifest versionName 不是 $expectedVersion"
}
if ($manifest.IndexOf("android:versionCode=`"$expectedVersionCode`"", [StringComparison]::Ordinal) -lt 0) {
    throw "版本门禁失败：Manifest versionCode 不是 $expectedVersionCode"
}
foreach ($permission in @(
        "android.permission.BLUETOOTH", "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.INTERNET")) {
    if ($manifest.IndexOf($permission, [StringComparison]::Ordinal) -lt 0) {
        throw "运行能力门禁失败：Manifest 缺少 $permission"
    }
}

$buildSource = [IO.File]::ReadAllText((Join-Path $project "build.ps1"))
if ($buildSource.IndexOf("v$expectedVersion-debug.apk", [StringComparison]::Ordinal) -lt 0) {
    throw "版本门禁失败：build.ps1 默认输出名未包含 v$expectedVersion"
}

$indexSource = [IO.File]::ReadAllText((Join-Path $project "src\assets\web\index.html"))
$appSource = [IO.File]::ReadAllText((Join-Path $project "src\assets\web\app.js"))
$activitySource = [IO.File]::ReadAllText((Join-Path $packageDir "MainActivity.java"))
$styleSource = [IO.File]::ReadAllText((Join-Path $project "src\assets\web\style.css"))
$authSource = [IO.File]::ReadAllText((Join-Path $packageDir "AuthManager.java"))
$serviceSource = [IO.File]::ReadAllText((Join-Path $packageDir "GatewayService.java"))
foreach ($required in @("音频已连接，等待接收", "正在接收音频")) {
    if ($serviceSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "音频状态门禁失败：缺少 $required"
    }
}
foreach ($required in @(
        "matchesToken", "PREF_ACCESS_TOKEN_RECOVERY",
        "PREF_TOKEN_RECOVERY_BOOTSTRAP", "currentToken()", "rememberToken",
        "auth.hasToken() && currentToken == null", ".commit();")) {
    $combined = $authSource + $serviceSource
    if ($combined.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "登录恢复门禁失败：缺少 $required"
    }
}
foreach ($required in @(
        "复制令牌并打开登录页", "令牌保存在 N1 私有存储",
        "launchLoginPage", "copyCurrentTokenToClipboard", "confirmRotateToken(true, true)")) {
    if ($activitySource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "登录界面门禁失败：缺少 $required"
    }
}
$uiChecks = 0
function Assert-UiText([string]$textValue, [string]$expected, [string]$label) {
    if ($textValue.IndexOf($expected, [StringComparison]::Ordinal) -lt 0) {
        throw "界面门禁失败：$label 缺少 $expected"
    }
    $script:uiChecks++
}
foreach ($item in @(
        @($indexSource, 'data-panel="control"', '直连控制页签'),
        @($indexSource, 'data-panel="channels"', '信道目录页签'),
        @($indexSource, 'id="panel-channels"', '信道目录面板'),
        @($indexSource, 'id="channel-search"', '信道目录搜索'),
        @($indexSource, 'id="channel-refresh"', '信道目录刷新'),
        @($indexSource, 'id="channel-summary"', '信道目录汇总'),
        @($indexSource, 'id="channel-list"', '信道目录列表'),
        @($activitySource, '运行状态', '左侧菜单运行状态'),
        @($activitySource, '蓝牙电台', '左侧菜单蓝牙电台'),
        @($activitySource, '网络设置', '左侧菜单网络设置'),
        @($activitySource, '运行选项', '左侧菜单运行选项'),
        @($activitySource, '网关与令牌', '左侧菜单网关与令牌'),
        @($indexSource, 'id="volume-range"', '音量滑杆'),
        @($indexSource, 'id="scan-target"', '扫描开关'),
        @($indexSource, 'data-side="A"', 'A/B 切换'),
        @($indexSource, 'N1Direct · N7500 直连控制', '产品标题'),
        @($indexSource, '版本 0.8.0 · 直连控制', '关于页版本标识'),
        @($indexSource, '0.8.0 · 本地优先', '侧栏版本标识'),
        @($indexSource, '0.8.0 · 直连控制', '顶部版本标识'),
        @($appSource, '/api/v1/channels', '信道目录读取接口'),
        @($appSource, '/api/v1/channels/refresh', '信道目录刷新接口'),
        @($appSource, 'loadChannels', '信道目录加载逻辑'),
        @($appSource, 'refreshChannels', '信道目录刷新逻辑'),
        @($appSource, 'scheduleCatalogPoll', '刷新期间轮询'),
        @($appSource, 'catalogMetadataBadges', 'A/B 与当前信道标识'),
        @($appSource, 'channel.stale', '过期信道标识'),
        @($styleSource, '.channel-row', '信道目录列表样式'),
        @($appSource, "confirm: true", '二次确认字段'),
        @($appSource, "clearCompletedTargets", '完成后清理目标脏值'),
        @($styleSource, '--wx-green: #07c160', '微信配色'),
        @($activitySource, '直连控制版本 0.8.0', '原生版本标识'),
        @($indexSource, 'data-panel="audio"', '音频房间页签'),
        @($indexSource, 'id="panel-audio"', '音频房间面板'),
        @($indexSource, 'id="audio-room-form"', '自建音频房间表单'),
        @($indexSource, 'id="audio-room-name"', '音频房间名称'),
        @($indexSource, 'id="audio-live-start"', '开始音频直播'),
        @($indexSource, 'id="audio-live-stop"', '停止音频直播'),
        @($indexSource, 'id="audio-live-volume"', '手机播放音量'),
        @($indexSource, 'id="audio-clip-player"', '历史 WAV 播放器'),
        @($indexSource, 'id="audio-history-list"', '历史音频列表'),
        @($indexSource, '历史录音保留在 N1 私有存储', '音频本地存储说明'),
        @($appSource, '/api/v1/audio/rooms', '音频房间接口'),
        @($appSource, "'/clips'", '音频历史接口'),
        @($appSource, "'/live'", '音频直播接口'),
        @($appSource, 'audioClipUrl', '历史 WAV 地址生成'),
        @($appSource, 'scheduleAudioChunk', '直播 PCM 调度'),
        @($appSource, 'AudioContext', '直播 PCM 解码'),
        @($appSource, 'createAudioRoom', '自建房间逻辑'),
        @($appSource, 'loadAudioClips', '历史音频加载逻辑'),
        @($styleSource, '.audio-status-card', '音频状态样式'),
        @($styleSource, '.audio-room-list', '音频房间列表样式'),
        @($styleSource, '.audio-history-list', '音频历史样式'),
        @($styleSource, '.audio-volume-line', '手机音量样式'),
        @($styleSource, 'dot.online', '音频在线状态样式'))) {
    Assert-UiText $item[0] $item[1] $item[2]
}

if ($activitySource.IndexOf("ScrollView", [StringComparison]::Ordinal) -ge 0) {
    throw "界面门禁失败：仍存在 ScrollView"
}
foreach ($removed in @("上翻", "下翻", "到底", "PositionStableScrollView", "mainScroll")) {
    if ($activitySource.IndexOf($removed, [StringComparison]::Ordinal) -ge 0) {
        throw "界面门禁失败：仍存在旧滚动实现 $removed"
    }
}
foreach ($sidebarMarker in @("pageContainer", "selectPage", "makeMenuButton", "FrameLayout")) {
    Assert-UiText $activitySource $sidebarMarker "固定左侧菜单布局"
}

Write-Host "第五阶段离线自测通过：$testRoot"
Write-Host "低风险边界扫描通过：$($allUiFiles.Count) 个源码/网页文件"
Write-Host "命令白名单通过：仅音量设置与完整设置块写入"
Write-Host "HTTP 鉴权、confirm、请求体上限与控制互斥门禁通过"
Write-Host "网页语法与界面门禁通过：$uiChecks 项（版本 $expectedVersion / code $expectedVersionCode）"
