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

if (!(Test-Path -LiteralPath $javac)) { throw "缺少 Java 编译器：$javac" }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sourceDir = Join-Path $project "src\java\com\bi4byf\n7500link\gateway"
$sources = @(
    (Join-Path $sourceDir "HexUtil.java"),
    (Join-Path $sourceDir "JsonUtil.java"),
    (Join-Path $sourceDir "GatewayContract.java"),
    (Join-Path $sourceDir "RadioModels.java"),
    (Join-Path $sourceDir "GaiaCodec.java"),
    (Join-Path $sourceDir "RadioParser.java"),
    (Join-Path $sourceDir "CommandScheduler.java"),
    (Join-Path $sourceDir "AuthManager.java"),
    (Join-Path $sourceDir "StatusRepository.java"),
    (Join-Path $sourceDir "SseHub.java"),
    (Join-Path $sourceDir "LocalWebServer.java"),
    (Join-Path $project "tools\GatewaySelfTest.java")
)

& $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "网关离线自测编译失败。" }

& $java -cp $classes com.bi4byf.n7500link.gateway.GatewaySelfTest
if ($LASTEXITCODE -ne 0) { throw "网关离线自测未通过。" }

$forbiddenCode = @(
    'CMD_SET_VOLUME', 'CMD_WRITE_SETTINGS', 'CMD_WRITE_RF_CH', 'CMD_SET_IN_SCAN',
    'MediaRecorder', 'AudioRecord', 'AudioTrack', 'BluetoothA2dp',
    'firmware', 'factory_reset'
)
$codeFiles = @(Get-ChildItem -LiteralPath (Join-Path $project 'src\java') -Recurse -Filter '*.java')
$codeFiles += @(Get-ChildItem -LiteralPath (Join-Path $project 'src\assets\web') -Recurse -File)
foreach ($file in $codeFiles) {
    $text = [IO.File]::ReadAllText($file.FullName)
    foreach ($pattern in $forbiddenCode) {
        if ($text.IndexOf($pattern, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "只读边界扫描失败：$($file.FullName) 含 $pattern"
        }
    }
}

$webFiles = @(Get-ChildItem -LiteralPath (Join-Path $project 'src\assets\web') -Recurse -File)
foreach ($file in $webFiles) {
    $text = [IO.File]::ReadAllText($file.FullName)
    if ($text -match '(?i)https?://|//cdn\.|@import\s+url') {
        throw "网页发现外部资源或明文外链：$($file.FullName)"
    }
}

$manifest = [IO.File]::ReadAllText((Join-Path $project 'src\AndroidManifest.xml'))
$serviceSource = [IO.File]::ReadAllText((Join-Path $sourceDir 'GatewayService.java'))
if ($manifest -notmatch 'android.permission.WAKE_LOCK') {
    throw "熄屏保活扫描失败：Manifest 缺少 WAKE_LOCK 权限。"
}
foreach ($required in @('PARTIAL_WAKE_LOCK', 'SCREEN_BRIGHT_WAKE_LOCK', 'WIFI_MODE_FULL_HIGH_PERF', 'acquireRuntimeLocks', 'releaseRuntimeLocks', 'runtimeLocksStatus', 'syncScreenWakeLock')) {
    if ($serviceSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "熄屏保活扫描失败：GatewayService 缺少 $required。"
    }
}
$contractSource = [IO.File]::ReadAllText((Join-Path $sourceDir 'GatewayContract.java'))
$configSource = [IO.File]::ReadAllText((Join-Path $sourceDir 'GatewayConfig.java'))
foreach ($required in @('PREF_SCREEN_DEFAULT_MIGRATED', 'PREF_SCREEN_INITIALIZATION_FIX_MIGRATED', 'DEFAULT_SCREEN_ALWAYS_ON = true', 'screenAlwaysOn(preferences)')) {
    if ($contractSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0 -and
            $configSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0 -and
            $serviceSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "屏幕常亮默认值扫描失败：源码缺少 $required"
    }
}
if ($serviceSource.IndexOf('GatewayConfig.screenAlwaysOn(preferences)', [StringComparison]::Ordinal) -lt 0) {
    throw "屏幕常亮默认值扫描失败：GatewayService 未使用统一设置读取"
}
$activitySource = [IO.File]::ReadAllText((Join-Path $sourceDir 'MainActivity.java'))
foreach ($required in @('PREF_SCREEN_ALWAYS_ON', '保持屏幕常亮', '强制前台常驻服务', '熄屏后可能断开 Wi-Fi')) {
    if ($activitySource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "运行选项扫描失败：MainActivity 缺少 $required。"
    }
}
foreach ($required in @('bindRuntimeOptionListeners', 'runtimeOptionsReady',
        'Listener must be bound only after setChecked()', 'if (!runtimeOptionsReady) return;')) {
    if ($activitySource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "运行选项初始化门禁失败：MainActivity 缺少 $required。"
    }
}
$loadAt = $activitySource.IndexOf('loadSavedSelection();', [StringComparison]::Ordinal)
$bindAt = $activitySource.IndexOf('bindRuntimeOptionListeners();', [StringComparison]::Ordinal)
if ($loadAt -lt 0 -or $bindAt -le $loadAt) {
    throw "运行选项初始化门禁失败：监听器必须在恢复开关值之后绑定。"
}
if ($contractSource.IndexOf('screen_always_on_init_fix_v3', [StringComparison]::Ordinal) -lt 0) {
    throw "屏幕常亮升级修复门禁失败：缺少独立迁移键。"
}

$expectedVersion = "0.3.8"
$expectedVersionCode = 14
$contractVersion = [Regex]::Match($contractSource, 'VERSION\s*=\s*"([^"]+)"').Groups[1].Value
$manifestVersion = [Regex]::Match($manifest, 'android:versionName="([^"]+)"').Groups[1].Value
$manifestVersionCodeText = [Regex]::Match($manifest, 'android:versionCode="([0-9]+)"').Groups[1].Value
if ($contractVersion -ne $expectedVersion -or $manifestVersion -ne $expectedVersion -or
        $manifestVersionCodeText -ne "$expectedVersionCode") {
    throw "版本门禁失败：契约=$contractVersion，清单=$manifestVersion/$manifestVersionCodeText，期望=$expectedVersion/$expectedVersionCode"
}
$buildSource = [IO.File]::ReadAllText((Join-Path $project 'build.ps1'))
if ($buildSource.IndexOf("v$expectedVersion-debug.apk", [StringComparison]::Ordinal) -lt 0) {
    throw "版本门禁失败：build.ps1 默认输出名未包含 v$expectedVersion。"
}

foreach ($required in @('ACCESS_TOKEN_LENGTH = 10', 'PREF_TOKEN_COMPACT_MIGRATED',
        'token_compact_length_v1')) {
    if ($contractSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "紧凑令牌门禁失败：GatewayContract 缺少 $required"
    }
}
foreach ($required in @('hadToken', 'auth.rotateToken()', 'PREF_TOKEN_COMPACT_MIGRATED')) {
    if ($serviceSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "旧令牌迁移门禁失败：GatewayService 缺少 $required"
    }
}
foreach ($required in @('mainScroll', 'dispatchGenericMotionEvent', '上翻', '下翻', '到底')) {
    if ($activitySource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "盒子滚动门禁失败：MainActivity 缺少 $required"
    }
}
foreach ($required in @('PositionStableScrollView', 'preservePosition',
        'LAYOUT_PRESERVE_MS', 'scrollToBottom', 'scrollByUser',
        'requestChildFocus', 'releaseAnchorForFocusNavigation',
        'dispatchKeyEvent', 'onTouchEvent', 'setTextIfChanged')) {
    if ($activitySource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "滚动位置保持门禁失败：MainActivity 缺少 $required"
    }
}
if ($activitySource.IndexOf('preservePosition(mainScroll.getScrollY())',
        [StringComparison]::Ordinal) -ge 0) {
    throw "滚动位置保持门禁失败：实时刷新不得重新捕获当前位置作为锚点。"
}

$toolChecks = 0
$liveToolSource = [IO.File]::ReadAllText((Join-Path $project 'tools\Test-LiveGateway.ps1'))
foreach ($required in @('TokenFile', 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789',
        'invalid_token=401', 'EscapeDataString')) {
    if ($liveToolSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "实机验证工具门禁失败：Test-LiveGateway 缺少 $required"
    }
    $toolChecks++
}
$monitorToolSource = [IO.File]::ReadAllText((Join-Path $project 'tools\Monitor-LiveGateway.ps1'))
foreach ($required in @('TokenFile', 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789')) {
    if ($monitorToolSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "实机验证工具门禁失败：Monitor-LiveGateway 缺少 $required"
    }
    $toolChecks++
}

$uiChecks = 0
function Assert-UiText([string]$textValue, [string]$expected, [string]$label) {
    if ($textValue.IndexOf($expected, [StringComparison]::Ordinal) -lt 0) {
        throw "界面门禁失败：$label 缺少 $expected"
    }
    $script:uiChecks++
}
$cssSource = [IO.File]::ReadAllText((Join-Path $project 'src\assets\web\style.css'))
$indexSource = [IO.File]::ReadAllText((Join-Path $project 'src\assets\web\index.html'))
$loginSource = [IO.File]::ReadAllText((Join-Path $project 'src\assets\web\login.html'))
$appSource = [IO.File]::ReadAllText((Join-Path $project 'src\assets\web\app.js'))
Assert-UiText $cssSource '--wx-green: #07c160' '微信配色'
Assert-UiText $indexSource 'class="wx-topbar"' '网页标题栏'
Assert-UiText $indexSource 'readonly-badge' '只读徽标'
Assert-UiText $indexSource 'data-panel="overview"' '总览页签'
Assert-UiText $indexSource 'data-panel="device"' '设备页签'
Assert-UiText $indexSource 'data-panel="channel"' '信道页签'
Assert-UiText $indexSource 'data-panel="gateway"' '网关页签'
Assert-UiText $indexSource 'data-panel="diagnostics"' '诊断页签'
Assert-UiText $indexSource 'data-panel="about"' '关于页签'
Assert-UiText $loginSource 'action="/api/v1/session"' '登录表单'
Assert-UiText $loginSource 'minlength="10"' '紧凑令牌登录长度'
Assert-UiText $appSource 'selectPanel(initial, false)' '页签切换'
Assert-UiText $activitySource 'setHeaderStatus("电台已连接", Color.rgb(7, 193, 96))' '原生绿色在线状态'
Assert-UiText $activitySource 'setHeaderStatus("快照已过期", Color.rgb(255, 179, 0))' '原生过期状态'
Assert-UiText $activitySource 'setHeaderStatus("电台未连接", Color.rgb(229, 57, 53))' '原生离线状态'
Write-Host "网关离线自测通过：$testRoot"
Write-Host "只读边界扫描通过：$($codeFiles.Count) 个源码/网页文件"
Write-Host "熄屏保活扫描通过：CPU、Wi-Fi 与可选屏幕锁在服务启停时成对申请/释放"
Write-Host "版本与微信风格界面门禁通过：$uiChecks 项（版本 $expectedVersion / code $expectedVersionCode）"
Write-Host "实机验证工具门禁通过：$toolChecks 项（10 位令牌文件、错误令牌 401 与采样脚本一致）"
