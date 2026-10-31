param([string]$Serial='emulator-5582',[string]$SdkPath='D:\Android\SDK',[string]$JdkPath='D:\Android\Android studio\jbr',[switch]$SdkCheck)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$out=Join-Path $project 'build\android-mcp-tests'
$bt=Join-Path $SdkPath 'build-tools\36.0.0'
$platform=Join-Path $SdkPath 'platforms\android-36\android.jar'
$adb=Join-Path $SdkPath 'platform-tools\adb.exe'
New-Item -ItemType Directory -Force -Path $out,(Join-Path $out 'classes'),(Join-Path $out 'dex') | Out-Null
function Run([string]$Program,[string[]]$Arguments){& $Program @Arguments;if($LASTEXITCODE-ne 0){throw "Test step failed: $Program"}}
Run "$bt\aapt2.exe" @('link','-I',$platform,'--manifest',(Join-Path $PSScriptRoot 'android\AndroidManifest.xml'),'-o',"$out\resources.apk")
Run "$JdkPath\bin\javac.exe" @('-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-cp',"$platform;$project\build\classes",'-d',"$out\classes",(Join-Path $PSScriptRoot 'android\McpUiChecks.java'))
Run "$JdkPath\bin\jar.exe" @('cf',"$out\tests.jar",'-C',"$out\classes",'.')
Run "$JdkPath\bin\java.exe" @('-cp',"$bt\lib\d8.jar",'com.android.tools.r8.D8','--min-api','33','--lib',$platform,'--classpath',"$project\build\classes.jar",'--output',"$out\dex","$out\tests.jar")
Copy-Item -LiteralPath "$out\resources.apk" -Destination "$out\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression
$zip=[IO.Compression.ZipFile]::Open("$out\unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$out\dex\classes.dex",'classes.dex')|Out-Null}finally{$zip.Dispose()}
Run "$bt\zipalign.exe" @('-f','4',"$out\unsigned.apk","$out\aligned.apk")
Run "$JdkPath\bin\java.exe" @('-jar',"$bt\lib\apksigner.jar",'sign','--ks',"$project\build\signing\development.jks",'--ks-pass','pass:android','--key-pass','pass:android','--out',"$out\checks.apk","$out\aligned.apk")
Run $adb @('-s',$Serial,'install','-r',"$project\build\outputs\NetCap-0.5.0-mcp-test.apk")
Run $adb @('-s',$Serial,'install','-r',"$out\checks.apk")
Run $adb @('-s',$Serial,'shell','pm','grant','io.github.netcap.app','android.permission.POST_NOTIFICATIONS')
if ($SdkCheck) {
    & $adb -s $Serial shell run-as io.github.netcap.app rm -f cache/mcp-sdk-ready cache/mcp-sdk-done
    $logPath = Join-Path $env:TEMP 'netcap-mcp-instrumentation.log'
    $errorPath = Join-Path $env:TEMP 'netcap-mcp-instrumentation-error.log'
    $runner = Start-Process -FilePath $adb -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-e','sdkHold','true','io.github.netcap.tests/io.github.netcap.app.McpUiChecks') -WindowStyle Hidden -PassThru -RedirectStandardOutput $logPath -RedirectStandardError $errorPath
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    $ready = $false
    while (-not $runner.HasExited -and [DateTime]::UtcNow -lt $deadline) {
        & $adb -s $Serial shell run-as io.github.netcap.app test -f cache/mcp-sdk-ready
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    $sdkCode = 1
    try {
        if ($ready) { & python (Join-Path $PSScriptRoot 'mcp_sdk_check.py') $adb $Serial; $sdkCode = $LASTEXITCODE }
    } finally {
        & $adb -s $Serial shell run-as io.github.netcap.app touch cache/mcp-sdk-done
        $runner.WaitForExit(15000) | Out-Null
    }
    $instrumentation = Get-Content -LiteralPath $logPath
    if ($sdkCode -ne 0) { $instrumentation | Write-Output; throw 'Official MCP SDK check failed' }
} else {
    $instrumentation = & $adb -s $Serial shell am instrument -w io.github.netcap.tests/io.github.netcap.app.McpUiChecks
}
$instrumentation | Write-Output
if($LASTEXITCODE-ne 0 -or -not ($instrumentation -match 'result=PASS')){throw 'Android MCP checks failed'}
$screens = Join-Path $env:TEMP 'netcap-mcp-screens'
New-Item -ItemType Directory -Force -Path $screens | Out-Null
Run $adb @('-s',$Serial,'pull','/sdcard/Android/data/io.github.netcap.app/files/mcp-checks', $screens)
Copy-Item -LiteralPath (Join-Path $screens 'mcp-checks') -Destination $out -Recurse -Force
