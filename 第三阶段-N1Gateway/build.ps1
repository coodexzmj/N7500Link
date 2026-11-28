param(
    [string]$OutputName = "N1Gateway-N7500-v0.3.8-debug.apk",
    [string]$ToolchainRoot = "C:\adb-n1\toolchain"
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$buildRel = "build\$stamp"
$buildAbs = Join-Path $project $buildRel
$distDir = Join-Path $project "dist"
$distApk = Join-Path $distDir $OutputName
$distRel = "dist\$OutputName"

$javaHome = Join-Path $ToolchainRoot "jdk-17.0.21+9"
$androidJar = Join-Path $ToolchainRoot "android-7.1.1\android.jar"
$aapt2 = Join-Path $ToolchainRoot "android-14\aapt2.exe"
$aapt = Join-Path $ToolchainRoot "android-14\aapt.exe"
$d8 = Join-Path $ToolchainRoot "android-14\d8.bat"
$zipalign = Join-Path $ToolchainRoot "android-14\zipalign.exe"
$apksigner = Join-Path $ToolchainRoot "android-14\apksigner.bat"
$javac = Join-Path $javaHome "bin\javac.exe"
$jar = Join-Path $javaHome "bin\jar.exe"
$keytool = Join-Path $javaHome "bin\keytool.exe"
$keyStore = Join-Path $project "keystore\n1gateway-debug.jks"
$storePass = "n1gateway-debug"
$keyAlias = "n1gateway"

$required = @($javaHome, $androidJar, $aapt2, $aapt, $d8, $zipalign,
    $apksigner, $javac, $jar, $keytool)
foreach ($item in $required) {
    if (!(Test-Path -LiteralPath $item)) { throw "缺少构建工具：$item" }
}

$env:JAVA_HOME = $javaHome
New-Item -ItemType Directory -Force -Path $buildAbs,
    (Join-Path $buildAbs "gen"), (Join-Path $buildAbs "classes"),
    (Join-Path $buildAbs "dex"), $distDir,
    (Join-Path $project "keystore") | Out-Null

Push-Location -LiteralPath $project
try {
    & $aapt2 compile --dir "src/res" -o "$buildRel\resources.zip"
    if ($LASTEXITCODE -ne 0) { throw "资源编译失败。" }

    & $aapt2 link -o "$buildRel\unsigned.apk" -I $androidJar `
        --manifest "src/AndroidManifest.xml" --java "$buildRel\gen" `
        --min-sdk-version 25 --target-sdk-version 25 `
        "$buildRel\resources.zip"
    if ($LASTEXITCODE -ne 0) { throw "APK 资源与网页资源链接失败。" }

    # aapt2 on Windows may write asset ZIP entries with backslashes when -A is used.
    # Android AssetManager requires forward slashes, so add the assets explicitly.
    $sourceRoot = (Resolve-Path -LiteralPath "src").Path
    $assetsRoot = Join-Path $sourceRoot "assets"
    $assetEntries = @(Get-ChildItem -LiteralPath $assetsRoot -Recurse -File |
        ForEach-Object { "assets/" + $_.FullName.Substring($assetsRoot.Length + 1).Replace('\', '/') })
    if ($assetEntries.Count -eq 0) { throw "网页资源目录为空。" }
    Push-Location -LiteralPath "src"
    try {
        & $aapt add "$buildAbs\unsigned.apk" @assetEntries
        if ($LASTEXITCODE -ne 0) { throw "网页资源打包失败。" }
    } finally {
        Pop-Location
    }

    $sources = @(Get-ChildItem -LiteralPath (Join-Path $project "src\java") `
        -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)
    $sources += @(Get-ChildItem -LiteralPath (Join-Path $buildAbs "gen") `
        -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)
    & $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn `
        -classpath $androidJar -d "$buildAbs\classes" $sources
    if ($LASTEXITCODE -ne 0) { throw "Java 编译失败。" }

    Push-Location -LiteralPath (Join-Path $buildAbs "classes")
    try {
        & $jar cf "$buildAbs\classes.jar" .
        if ($LASTEXITCODE -ne 0) { throw "classes.jar 创建失败。" }
    } finally {
        Pop-Location
    }

    & $d8 --min-api 25 --output "$buildAbs\dex" "$buildAbs\classes.jar"
    if ($LASTEXITCODE -ne 0) { throw "DEX 生成失败。" }
    Copy-Item -LiteralPath "$buildAbs\dex\classes.dex" `
        -Destination "$buildAbs\classes.dex" -Force

    Push-Location -LiteralPath $buildAbs
    try {
        & $aapt add "unsigned.apk" "classes.dex"
        if ($LASTEXITCODE -ne 0) { throw "DEX 打包失败。" }
    } finally {
        Pop-Location
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead("$buildAbs\unsigned.apk")
    try {
        $entryNames = @($zip.Entries | ForEach-Object { $_.FullName })
        foreach ($requiredEntry in @(
                "classes.dex", "assets/web/index.html", "assets/web/login.html",
                "assets/web/app.js", "assets/web/style.css")) {
            if ($entryNames -notcontains $requiredEntry) {
                throw "APK 缺少必需条目：$requiredEntry"
            }
        }
        $badAssetEntry = $entryNames | Where-Object {
            $_.StartsWith("assets/") -and $_.Contains('\')
        } | Select-Object -First 1
        if ($badAssetEntry) {
            throw "APK 网页资源路径包含反斜杠：$badAssetEntry"
        }
    } finally {
        $zip.Dispose()
    }

    & $zipalign -f -p 4 "$buildRel\unsigned.apk" "$buildRel\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "APK 对齐失败。" }

    if (!(Test-Path -LiteralPath $keyStore)) {
        & $keytool -genkeypair -v -storetype JKS -keystore $keyStore `
            -storepass $storePass -keypass $storePass -alias $keyAlias `
            -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=BI4BYF, OU=N7500, O=N1Gateway, L=Local, ST=Local, C=CN"
        if ($LASTEXITCODE -ne 0) { throw "签名证书创建失败。" }
    }

    if (Test-Path -LiteralPath $distApk) {
        Remove-Item -LiteralPath $distApk -Force
    }
    & $apksigner sign --ks $keyStore --ks-key-alias $keyAlias `
        --ks-pass "pass:$storePass" --key-pass "pass:$storePass" `
        --out $distApk "$buildRel\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "APK 签名失败。" }

    & $apksigner verify --verbose $distApk
    if ($LASTEXITCODE -ne 0) { throw "APK 签名验证失败。" }
    & $zipalign -c 4 $distApk
    if ($LASTEXITCODE -ne 0) { throw "APK 对齐验证失败。" }

    $badging = & $aapt dump badging $distRel
    if ($LASTEXITCODE -ne 0) { throw "APK 清单验证失败。" }
    $badging | Select-Object -First 8
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "构建完成：$distApk"
Get-Item -LiteralPath $distApk | Select-Object FullName, Length, LastWriteTime
