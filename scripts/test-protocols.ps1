$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $env:USERPROFILE 'android-tools\jdk-ms\bin'
$output = Join-Path $projectPath 'build\protocol-checks'
New-Item -ItemType Directory -Path $output -Force | Out-Null
Push-Location $projectPath
try {
    $sources = @(
        'src/com/hdz/wearcontrol/EdifierProtocol.java',
        'src/com/hdz/wearcontrol/ScrollFeedback.java',
        'src/com/hdz/wearcontrol/ScrollIndicatorMotion.java',
        'src/com/hdz/wearcontrol/ProtocolWriter.java',
        'src/com/hdz/wearcontrol/TileSnapshot.java',
        'src/com/hdz/wearcontrol/vivo/VivoProtocol.java',
        'src/com/hdz/wearcontrol/SonyProtocol.java',
        'src/com/hdz/wearcontrol/TechnicsProtocol.java',
        'src/com/hdz/wearcontrol/RoseProtocol.java',
        'src/com/hdz/wearcontrol/NiceHckProtocol.java',
        'src/com/hdz/wearcontrol/oppo/OppoProtocol.java',
        'src/com/hdz/wearcontrol/xiaomi/XiaomiAuth.java',
        'src/com/hdz/wearcontrol/xiaomi/XiaomiProtocol.java',
        'scripts/ProtocolChecks.java'
    )
    & (Join-Path $jdk 'javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath build/classes -d $output @sources
    if ($LASTEXITCODE -ne 0) { throw 'Protocol test compilation failed' }
    & (Join-Path $jdk 'java.exe') -cp $output com.hdz.wearcontrol.ProtocolChecks
    if ($LASTEXITCODE -ne 0) { throw 'Protocol checks failed' }
} finally {
    Pop-Location
}
