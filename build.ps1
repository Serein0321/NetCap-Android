param(
    [string]$SdkPath = $env:ANDROID_SDK_ROOT,
    [string]$JdkPath = '',
    [switch]$SkipX86
)
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
if (-not $SdkPath) { $SdkPath = 'D:\Android\SDK' }
if (-not $JdkPath) { $JdkPath = 'D:\Android\Android studio\jbr' }
if (-not (Test-Path -LiteralPath "$JdkPath\bin\javac.exe")) { throw '请用 -JdkPath 指定 JDK 17 或更高版本目录。' }
if (-not (Test-Path -LiteralPath "$SdkPath\platforms\android-36\android.jar")) { throw '请安装 Android SDK Platform 36。' }
$ndkPath = Join-Path $SdkPath 'ndk\29.0.14206865'
$buildTools = Join-Path $SdkPath 'build-tools\36.0.0'
$llvmPath = Join-Path $ndkPath 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$outDir = Join-Path $projectDir 'build'
$apkName = 'NetCap-0.3.0-workbench-test.apk'
$oldLocation = Get-Location
$previousTmp = $env:TMP
$previousTemp = $env:TEMP
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "构建步骤失败：$Program ($LASTEXITCODE)" }
}
try {
    Set-Location -LiteralPath $projectDir
    # 只清理已核对位于本工程 build 下的编译中间目录，保留签名和历史安装包。
    foreach ($generatedPart in @('generated', 'classes', 'dex')) {
        $generatedPath = [IO.Path]::GetFullPath((Join-Path $outDir $generatedPart))
        $buildBoundary = [IO.Path]::GetFullPath($outDir).TrimEnd('\') + '\'
        if (-not $generatedPath.StartsWith($buildBoundary, [StringComparison]::OrdinalIgnoreCase)) {
            throw '编译中间目录越过 build 边界，已停止。'
        }
        if (Test-Path -LiteralPath $generatedPath) { Remove-Item -LiteralPath $generatedPath -Recurse -Force }
    }
    foreach ($part in @('generated','classes','dex','native\arm64-v8a','native\x86_64','outputs','signing','temp')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $outDir $part) | Out-Null
    }
    $env:TMP = Join-Path $outDir 'temp'
    $env:TEMP = $env:TMP
    $targets = @{ 'arm64-v8a'='aarch64-linux-android33' }
    if (-not $SkipX86) { $targets['x86_64']='x86_64-linux-android33' }
    foreach ($abi in $targets.Keys) {
        Invoke-Checked "$llvmPath\clang++.exe" @("--target=$($targets[$abi])",'-std=c++17','-O2','-Wall','-Wextra','-fPIE','-pie','-static-libstdc++','-pthread','-Wl,-z,max-page-size=16384','native/core.cpp','-o',"build/native/$abi/libnetcap_core.so")
    }
    Invoke-Checked "$buildTools\aapt2.exe" @('compile','--dir','app/src/main/res','-o','build/resources.zip')
    Invoke-Checked "$buildTools\aapt2.exe" @('link','-I',"$SdkPath\platforms\android-36\android.jar",'--manifest','app/src/main/AndroidManifest.xml','--java','build/generated','--auto-add-overlay','-A','app/src/main/assets','-o','build/resources.apk','build/resources.zip')
    $sourceFiles = @(Get-ChildItem -LiteralPath 'app/src/main/java','build/generated' -Recurse -Filter '*.java' | ForEach-Object FullName)
    $sourceArgs = @('-J-Duser.language=en','-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-bootclasspath',"$SdkPath\platforms\android-36\android.jar;$buildTools\core-lambda-stubs.jar",'-d','build/classes') + $sourceFiles
    Invoke-Checked "$JdkPath\bin\javac.exe" $sourceArgs
    Invoke-Checked "$JdkPath\bin\jar.exe" @('cf','build/classes.jar','-C','build/classes','.')
    Invoke-Checked "$JdkPath\bin\java.exe" @('-cp',"$buildTools\lib\d8.jar",'com.android.tools.r8.D8','--min-api','33','--lib',"$SdkPath\platforms\android-36\android.jar",'--output','build/dex','build/classes.jar')
    Copy-Item -LiteralPath 'build/resources.apk' -Destination 'build/unsigned.apk' -Force
    Add-Type -AssemblyName System.IO.Compression
    $apk = [IO.Compression.ZipFile]::Open((Join-Path $outDir 'unsigned.apk'),[IO.Compression.ZipArchiveMode]::Update)
    try {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk,(Join-Path $outDir 'dex/classes.dex'),'classes.dex') | Out-Null
        foreach ($abi in $targets.Keys) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk,(Join-Path $outDir "native/$abi/libnetcap_core.so"),"lib/$abi/libnetcap_core.so") | Out-Null
        }
    } finally { $apk.Dispose() }
    Invoke-Checked "$buildTools\zipalign.exe" @('-f','-P','16','4','build/unsigned.apk','build/aligned.apk')
    if (-not (Test-Path -LiteralPath 'build/signing/development.jks')) {
        Invoke-Checked "$JdkPath\bin\keytool.exe" @('-genkeypair','-keystore','build/signing/development.jks','-storepass','android','-keypass','android','-alias','development','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=NetCapture Development, O=Local Development, C=CN')
    }
    Invoke-Checked "$JdkPath\bin\java.exe" @('-jar',"$buildTools\lib\apksigner.jar",'sign','--ks','build/signing/development.jks','--ks-pass','pass:android','--key-pass','pass:android','--out',"build/outputs/$apkName",'build/aligned.apk')
    Invoke-Checked "$JdkPath\bin\java.exe" @('-jar',"$buildTools\lib\apksigner.jar",'verify','--verbose',"build/outputs/$apkName")
    Get-FileHash -LiteralPath "build/outputs/$apkName" -Algorithm SHA256
} finally { $env:TMP=$previousTmp; $env:TEMP=$previousTemp; Set-Location -LiteralPath $oldLocation }
