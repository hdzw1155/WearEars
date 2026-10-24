@echo off
chcp 65001 >nul
rem ============================================================
rem  vivo TWS Air3 Pro 手表控制器 - 免 Gradle 手动构建脚本
rem  依赖: %USERPROFILE%\android-tools\jdk-ms\bin (JDK 17)
rem        %LOCALAPPDATA%\Android\Sdk (platforms;android-34,
rem        build-tools;34.0.0, 由 scripts\setup-env.bat 安装)
rem  产物: tws-control-wear-debug.apk
rem ============================================================
setlocal
cd /d "%~dp0.."

set JDK_DIR=
for /d %%d in ("%USERPROFILE%\android-tools\jdk-*") do set JDK_DIR=%%d
if not defined JDK_DIR (
  echo [X] 未找到 JDK, 先运行 scripts\setup-env.bat
  exit /b 1
)
set PATH=%JDK_DIR%\bin;%PATH%

set SDK=%LOCALAPPDATA%\Android\Sdk
set BT=%SDK%\build-tools\34.0.0
set PLAT=%SDK%\platforms\android-34\android.jar
if not exist "%PLAT%" (
  echo [X] 未找到 android.jar, 先运行 scripts\setup-env.bat
  exit /b 1
)

if not exist keystore\debug.keystore (
  mkdir keystore 2>nul
  keytool -genkeypair -keystore keystore\debug.keystore ^
    -alias androiddebugkey -storepass android -keypass android ^
    -keyalg RSA -keysize 2048 -validity 10000 ^
    -dname "CN=Android Debug,O=Android,C=US" || exit /b 1
)

echo [1/6] aapt2 compile...
if exist build rmdir /s /q build
mkdir build
"%BT%\aapt2.exe" compile --dir res -o build\res.zip || exit /b 1

echo [2/6] aapt2 link...
"%BT%\aapt2.exe" link -o build\app.unsigned.apk -I "%PLAT%" ^
  --manifest AndroidManifest.xml --java build\gen ^
  --min-sdk-version 30 --target-sdk-version 34 ^
  --version-code 2 --version-name 0.1.1 ^
  --auto-add-overlay build\res.zip || exit /b 1

echo [3/6] javac...
setlocal enabledelayedexpansion
set SRCS=
for /r src %%f in (*.java) do set "SRCS=!SRCS! "%%f""
for /r build\gen %%f in (*.java) do set "SRCS=!SRCS! "%%f""
javac -source 8 -target 8 -encoding UTF-8 ^
  -bootclasspath "%PLAT%" -d build\classes !SRCS! || exit /b 1
endlocal

echo [4/6] d8 dex...
pushd build\classes
jar cf ..\classes.jar com || exit /b 1
popd
call "%BT%\d8.bat" --release --lib "%PLAT%" --min-api 30 ^
  --output build build\classes.jar || exit /b 1

echo [5/6] 打包 classes.dex...
powershell -NoProfile -Command ^
  "Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem; $z=[System.IO.Compression.ZipFile]::Open('build\app.unsigned.apk','Update'); $e=$z.CreateEntry('classes.dex'); $s=$e.Open(); $f=[System.IO.File]::OpenRead('build\classes.dex'); $f.CopyTo($s); $f.Close(); $s.Close(); $z.Dispose()" || exit /b 1

echo [6/6] 对齐 + 签名...
"%BT%\zipalign.exe" -f 4 build\app.unsigned.apk build\app.aligned.apk || exit /b 1
call "%BT%\apksigner.bat" sign --ks keystore\debug.keystore ^
  --ks-pass pass:android --key-pass pass:android ^
  --ks-key-alias androiddebugkey ^
  --out tws-control-wear-debug.apk build\app.aligned.apk || exit /b 1

echo.
echo [OK] 已生成 tws-control-wear-debug.apk
endlocal
