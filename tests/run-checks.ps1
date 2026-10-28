param([string]$SdkPath='D:\Android\SDK',[string]$JdkPath='D:\Android\Android studio\jbr',[switch]$Traffic,[switch]$TlsFixtures)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$destination=Join-Path $project 'build\host-tests'
New-Item -ItemType Directory -Force $destination | Out-Null
$dependency=Join-Path $destination 'json.jar'
if(-not(Test-Path -LiteralPath $dependency)){
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar' -OutFile $dependency
}
if((Get-FileHash -LiteralPath $dependency).Hash -ne '3EA61B2A06E31EDF1C91134FE9106B0EBB16628BE169F3DB75BC7A2B06B45796'){throw 'JSON 测试依赖校验失败'}
$platform=Join-Path $SdkPath 'platforms\android-36\android.jar'
$classes=Join-Path $destination 'classes'
$source=Join-Path $project 'app\src\main\java\io\github\netcap\app'
$files=@('PacketStore.java','PacketFilter.java','PacketNotes.java','IntegrityReport.java','SessionLibrary.java','ModalGeometry.java','TlsInspector.java','ThemePalette.java','BubbleState.java','BubbleLayout.java','StyleLayout.java','WorkspaceFiles.java','ArchiveIndex.java')|ForEach-Object{Join-Path $source $_}
& "$JdkPath\bin\javac.exe" -encoding UTF-8 -cp "$dependency;$platform" -d $classes @files (Join-Path $PSScriptRoot 'WorkbenchChecks.java') (Join-Path $PSScriptRoot 'LibraryChecks.java') (Join-Path $PSScriptRoot 'TlsThemeChecks.java') (Join-Path $PSScriptRoot 'GalleryBubbleChecks.java') (Join-Path $PSScriptRoot 'WorkspaceFileChecks.java') (Join-Path $PSScriptRoot 'ArchiveChecks.java')
if($LASTEXITCODE-ne 0){throw '主机测试编译失败'}
$run=Join-Path $destination ('run-'+[DateTimeOffset]::Now.ToUnixTimeMilliseconds())
& "$JdkPath\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$classes;$dependency;$platform" io.github.netcap.app.WorkbenchChecks $run
if($LASTEXITCODE-ne 0){throw '数据回归失败'}
& "$JdkPath\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$classes;$dependency;$platform" io.github.netcap.app.LibraryChecks ($run+'-library')
if($LASTEXITCODE-ne 0){throw '会话管理/窗口边界回归失败'}
if($Traffic){& python (Join-Path $PSScriptRoot 'probe_checks.py');if($LASTEXITCODE-ne 0){throw '本机通信回归失败'}}

$fixtureArgs=@()
if($TlsFixtures){
    $fixtures=Join-Path $destination 'tls-fixtures'
    & python (Join-Path $PSScriptRoot 'tls_fixtures.py') $fixtures
    if($LASTEXITCODE-ne 0){throw 'TLS 测试数据生成失败，需要 Python cryptography'}
    $fixtureArgs=@($fixtures)
}
& "$JdkPath\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$classes;$dependency;$platform" io.github.netcap.app.TlsThemeChecks ($run+'-tls') @fixtureArgs
if($LASTEXITCODE-ne 0){throw 'TLS/主题回归失败'}

& "$JdkPath\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$classes;$dependency;$platform" io.github.netcap.app.GalleryBubbleChecks
if($LASTEXITCODE-ne 0){throw '主题画廊/悬浮球回归失败'}

& "$JdkPath\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$classes;$dependency;$platform" io.github.netcap.app.WorkspaceFileChecks ($run+'-files')
if($LASTEXITCODE-ne 0){throw '真实文件工作区/胶囊拖动回归失败'}

& "$JdkPath\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$classes;$dependency;$platform" io.github.netcap.app.ArchiveChecks ($run+'-archives')
if($LASTEXITCODE-ne 0){throw '压缩包只读浏览回归失败'}
