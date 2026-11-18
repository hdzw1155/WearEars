$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $env:USERPROFILE 'android-tools\jdk-ms'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$platform = Join-Path $sdk 'platforms\android-34\android.jar'
$r8 = Join-Path $sdk 'build-tools\34.0.0\lib\d8.jar'
$output = Join-Path $projectPath ('build/compact-checks/' + [Guid]::NewGuid().ToString('N'))
Push-Location $projectPath
try {
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $compactJar = Join-Path $output 'compact.jar'
    $inputs = @('build/classes.jar') + @(Get-ChildItem libs -Filter '*.jar' | ForEach-Object { $_.FullName })
    & (Join-Path $jdk 'bin/java.exe') '-Dfile.encoding=UTF-8' -cp $r8 com.android.tools.r8.R8 --classfile `
        --lib $platform --lib $jdk --pg-conf scripts/shrink-rules.pro --output $compactJar @inputs
    if ($LASTEXITCODE -ne 0) { throw 'R8 classfile check failed' }
    $compileClasspath = 'build/classes;' + $platform + ';' + ((Get-ChildItem libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';')
    & (Join-Path $jdk 'bin/javac.exe') '-J-Duser.language=en' -encoding UTF-8 -source 8 -target 8 `
        -cp $compileClasspath -d $output scripts/CompactChecks.java
    if ($LASTEXITCODE -ne 0) { throw 'Compact check compilation failed' }
    # Only the shrunk artifact is on this classpath; no original app or library classes can mask removals.
    & (Join-Path $jdk 'bin/java.exe') -cp "$output;$compactJar;$platform" com.hdz.wearcontrol.CompactChecks
    if ($LASTEXITCODE -ne 0) { throw 'Compact runtime checks failed' }
} finally { Pop-Location }
