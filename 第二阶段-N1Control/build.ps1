param(
    [string]$OutputName = "N1Control-N7500-v0.2.1-debug.apk",
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
$keyStore = Join-Path $project "keystore\n1probe-debug.jks"
$storePass = "n1probe-debug"
$keyAlias = "n1probe"

$required = @($javaHome, $androidJar, $aapt2, $aapt, $d8, $zipalign,
    $apksigner, $javac, $jar, $keytool)
foreach ($item in $required) {
    if (!(Test-Path -LiteralPath $item)) {
        throw "缺少构建工具：$item"
    }
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
        --min-sdk-version 25 --target-sdk-version 25 "$buildRel\resources.zip"
    if ($LASTEXITCODE -ne 0) { throw "APK 资源链接失败。" }

    $sources = @(Get-ChildItem -LiteralPath (Join-Path $project "src\java") `
        -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)
    $sources += @(Get-ChildItem -LiteralPath (Join-Path $buildAbs "gen") `
        -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)
    & $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -classpath $androidJar `
        -d "$buildAbs\classes" $sources
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

    & $zipalign -f -p 4 "$buildRel\unsigned.apk" "$buildRel\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw "APK 对齐失败。" }

    if (!(Test-Path -LiteralPath $keyStore)) {
        & $keytool -genkeypair -v -storetype JKS -keystore $keyStore `
            -storepass $storePass -keypass $storePass -alias $keyAlias `
            -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=BI4BYF, OU=N7500, O=N1Probe, L=Local, ST=Local, C=CN"
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