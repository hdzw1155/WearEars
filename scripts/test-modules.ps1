$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $env:USERPROFILE 'android-tools\jdk-ms\bin'
$platform = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platforms\android-34\android.jar'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$runName = [Guid]::NewGuid().ToString('N')
$driverNames = @('Edifier', 'RoseLink', 'NiceHck', 'Sony', 'Technics')
$driverFiles = @('EdifierProtocol', 'RoseProtocol', 'NiceHckProtocol', 'SonyProtocol', 'TechnicsProtocol', 'RfcommClient')
$panels = @('BatteryPanel', 'NoisePanel', 'VolumePanel', 'EffectsPanel')
$tiles = @('TwsTileService', 'TileLayouts', 'ClassicTileLayouts', 'TileResources', 'ClassicTileResources',
    'TileRenderSpec', 'TileActions', 'TileCommand', 'TileFutures', 'TileConnectionReceiver', 'ModeCyclerActivity')

Push-Location $projectPath
try {
    if (!(Test-Path 'build/gen/com/hdz/wearcontrol/R.java')) { throw 'Run scripts/build.bat first' }
    $sourceFiles = @(Get-ChildItem src -Recurse -Filter '*.java') + @(Get-ChildItem build/gen -Recurse -Filter '*.java')
    $jars = @(Get-ChildItem libs -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'
    $variants = @('no-drivers', 'no-battery', 'no-noise', 'no-volume', 'no-effects', 'no-panels', 'no-tiles', 'minimal')
    foreach ($variant in $variants) {
        $excluded = @()
        if ($variant -in @('no-drivers', 'minimal')) { $excluded += $driverNames | ForEach-Object { $_ + 'Client' }; $excluded += $driverFiles }
        if ($variant -in @('no-panels', 'minimal')) { $excluded += $panels }
        if ($variant -eq 'no-battery') { $excluded += 'BatteryPanel' }
        if ($variant -eq 'no-noise') { $excluded += 'NoisePanel' }
        if ($variant -eq 'no-volume') { $excluded += 'VolumePanel' }
        if ($variant -eq 'no-effects') { $excluded += 'EffectsPanel' }
        if ($variant -in @('no-tiles', 'minimal')) { $excluded += $tiles }
        $output = Join-Path $projectPath ('build/module-checks/' + $runName + '/' + $variant)
        New-Item -ItemType Directory -Path $output -Force | Out-Null
        $lines = @('-source', '8', '-target', '8', '-encoding', 'UTF-8', '-bootclasspath', ('"' + $platform.Replace('\', '/') + '"'),
            '-sourcepath', ('"' + $output.Replace('\', '/') + '"'), '-d', ('"' + $output.Replace('\', '/') + '"'))
        # 不引用完整构建的 classes，防止被排除的模块从旧 class 文件漏进验证。
        if ($variant -notin @('no-tiles', 'minimal')) {
            $lines += @('-classpath', ('"' + $jars.Replace('\', '/') + '"'))
        } else {
            $lines += @('-classpath', ('"' + $output.Replace('\', '/') + '"'))
        }
        foreach ($file in $sourceFiles) {
            $relative = $file.FullName.Substring($projectPath.Length + 1).Replace('\', '/')
            if ($file.BaseName -in $excluded) { continue }
            if ($variant -in @('no-drivers', 'minimal') -and $relative -match '^src/com/hdz/wearcontrol/(vivo|huawei|xiaomi|oppo)/') { continue }
            $lines += '"' + $file.FullName.Replace('\', '/') + '"'
        }
        $argumentsFile = Join-Path $output 'sources.txt'
        [IO.File]::WriteAllLines($argumentsFile, $lines, $utf8)
        & (Join-Path $jdk 'javac.exe') '-J-Dfile.encoding=UTF-8' '-J-Duser.language=en' ('@' + $argumentsFile)
        if ($LASTEXITCODE -ne 0) { throw "Module compilation failed: $variant" }
        Write-Output "PASS: $variant"
    }
} finally { Pop-Location }
