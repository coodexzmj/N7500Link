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
$packageDir = Join-Path $project "src\java\com\bi4byf\n7500link\remote"

if (!(Test-Path -LiteralPath $javac)) { throw "缺少 Java 编译器：$javac" }
if (!(Test-Path -LiteralPath $java)) { throw "缺少 Java 运行环境：$java" }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sourceNames = @(
    "HexUtil.java", "JsonUtil.java", "GatewayContract.java", "RadioModels.java",
    "SettingsBlock.java", "GaiaCodec.java", "RadioParser.java",
    "CommandScheduler.java", "LowRiskWriteController.java", "AuthManager.java",
    "StatusRepository.java", "SseHub.java", "LocalWebServer.java"
)
$sources = @($sourceNames | ForEach-Object { Join-Path $packageDir $_ })
$sources += Join-Path $project "tools\RemoteSelfTest.java"
& $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn `
    -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "第四阶段离线自测编译失败。" }

& $java -cp $classes com.bi4byf.n7500link.remote.RemoteSelfTest
if ($LASTEXITCODE -ne 0) { throw "第四阶段离线自测未通过。" }

$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    & $node.Source --check (Join-Path $project "src\assets\web\app.js")
    if ($LASTEXITCODE -ne 0) { throw "网页脚本语法检查失败。" }
}

$codeFiles = @(Get-ChildItem -LiteralPath (Join-Path $project "src\java") -Recurse -Filter "*.java")
$webFiles = @(Get-ChildItem -LiteralPath (Join-Path $project "src\assets\web") -Recurse -File)
$allUiFiles = @($codeFiles + $webFiles)

# These identifiers represent capabilities explicitly outside the fourth-stage boundary.
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
        "AUTH_REQUIRED")) {
    if ($serverSource.IndexOf($required, [StringComparison]::Ordinal) -lt 0) {
        throw "HTTP 控制门禁失败：缺少 $required"
    }
}

$manifest = [IO.File]::ReadAllText((Join-Path $project "src\AndroidManifest.xml"))
$contractSource = [IO.File]::ReadAllText((Join-Path $packageDir "GatewayContract.java"))
$expectedVersion = "0.4.1"
$expectedVersionCode = "16"
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
$uiChecks = 0
function Assert-UiText([string]$textValue, [string]$expected, [string]$label) {
    if ($textValue.IndexOf($expected, [StringComparison]::Ordinal) -lt 0) {
        throw "界面门禁失败：$label 缺少 $expected"
    }
    $script:uiChecks++
}
foreach ($item in @(
        @($indexSource, 'data-panel="control"', '远程控制页签'),
        @($activitySource, '运行状态', '左侧菜单运行状态'),
        @($activitySource, '蓝牙电台', '左侧菜单蓝牙电台'),
        @($activitySource, '网络设置', '左侧菜单网络设置'),
        @($activitySource, '运行选项', '左侧菜单运行选项'),
        @($activitySource, '网关与令牌', '左侧菜单网关与令牌'),
        @($indexSource, 'id="volume-range"', '音量滑杆'),
        @($indexSource, 'id="scan-target"', '扫描开关'),
        @($indexSource, 'data-side="A"', 'A/B 切换'),
        @($indexSource, 'N1RemoteControl · N7500 低风险远程控制', '产品标题'),
        @($indexSource, '版本 0.4.1 · 低风险控制', '版本标识'),
        @($appSource, "confirm: true", '二次确认字段'),
        @($appSource, "clearCompletedTargets", '完成后清理目标脏值'),
        @($styleSource, '--wx-green: #07c160', '微信配色'),
        @($activitySource, '低风险控制版本 0.4.1', '原生版本标识'))) {
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

Write-Host "第四阶段离线自测通过：$testRoot"
Write-Host "低风险边界扫描通过：$($allUiFiles.Count) 个源码/网页文件"
Write-Host "命令白名单通过：仅音量设置与完整设置块写入"
Write-Host "HTTP 鉴权、confirm、请求体上限与控制互斥门禁通过"
Write-Host "网页语法与界面门禁通过：$uiChecks 项（版本 $expectedVersion / code $expectedVersionCode）"
