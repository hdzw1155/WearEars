# Run build.bat first. This optional step never replaces the ordinary APK.
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $env:USERPROFILE 'android-tools\jdk-ms\bin'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$buildTools = Join-Path $sdk 'build-tools\34.0.0'
$platform = Join-Path $sdk 'platforms\android-34\android.jar'
$r8 = Join-Path $buildTools 'lib\d8.jar'
$output = Join-Path $projectPath ('build/compact/' + [Guid]::NewGuid().ToString('N'))
$previousJavaHome = $env:JAVA_HOME

Push-Location $projectPath
try {
    if (!(Test-Path 'build/classes.jar') -or !(Test-Path 'build/app.unsigned.apk')) {
        throw 'Run scripts/build.bat first'
    }
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $inputs = @('build/classes.jar') + @(Get-ChildItem libs -Filter '*.jar' | ForEach-Object { $_.FullName })
    & (Join-Path $jdk 'java.exe') '-Dfile.encoding=UTF-8' -cp $r8 com.android.tools.r8.R8 --release --min-api 30 --lib $platform `
        --pg-conf scripts/shrink-rules.pro --pg-map-output (Join-Path $output 'mapping.txt') --output $output @inputs
    if ($LASTEXITCODE -ne 0) { throw 'R8 failed' }

    $unsigned = Join-Path $output 'app.unsigned.apk'
    $aligned = Join-Path $output 'app.aligned.apk'
    Copy-Item -LiteralPath 'build/app.unsigned.apk' -Destination $unsigned
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
    try {
        @($archive.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' }) | ForEach-Object { $_.Delete() }
        foreach ($dex in Get-ChildItem -LiteralPath $output -Filter '*.dex') {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $dex.FullName, $dex.Name,
                [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $archive.Dispose() }

    & (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
    # The existing project key is the Android debug key; no release key is required.
    $env:JAVA_HOME = Split-Path $jdk -Parent
    & (Join-Path $buildTools 'apksigner.bat') sign --ks keystore/debug.keystore --ks-pass pass:android `
        --key-pass pass:android --ks-key-alias androiddebugkey --out wearears-compact.apk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed' }
    & (Join-Path $buildTools 'apksigner.bat') verify --verbose wearears-compact.apk
    if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }
    $regular = (Get-Item -LiteralPath 'wearears-debug.apk').Length
    $compact = (Get-Item -LiteralPath 'wearears-compact.apk').Length
    Write-Output ("APK bytes: {0} -> {1} ({2:N1}% smaller)" -f $regular, $compact, (100 * (1 - $compact / $regular)))
} finally { $env:JAVA_HOME = $previousJavaHome; Pop-Location }
