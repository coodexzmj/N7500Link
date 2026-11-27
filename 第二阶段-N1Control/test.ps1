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

$sourceDir = Join-Path $project "src\java\com\bi4byf\n7500link\control"
$sources = @(
    (Join-Path $sourceDir "HexUtil.java"),
    (Join-Path $sourceDir "GaiaCodec.java"),
    (Join-Path $sourceDir "RadioParser.java"),
    (Join-Path $sourceDir "RadioState.java"),
    (Join-Path $project "tools\ProtocolSelfTest.java")
)

& $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "协议自测编译失败。" }

& $java -cp $classes com.bi4byf.n7500link.control.ProtocolSelfTest
if ($LASTEXITCODE -ne 0) { throw "协议自测未通过。" }
Write-Host "协议自测通过：$testRoot"