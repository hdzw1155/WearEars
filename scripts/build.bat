@echo off
rem ============================================================
rem  TWS control for Wear (multi-brand) - no-Gradle manual build
rem  deps: %USERPROFILE%\android-tools\jdk-ms\bin (JDK 17)
rem        %LOCALAPPDATA%\Android\Sdk (platforms;android-34,
rem        build-tools;34.0.0, installed by scripts\setup-env.bat)
rem  tile libs: libs\ (fetched by scripts\fetch-libs.bat)
rem  output: tws-control-wear-debug.apk
rem  NOTE: keep this file ASCII-only. A UTF-8 bat with Chinese text
rem        plus `chcp 65001` makes cmd misparse lines intermittently.
rem ============================================================
setlocal
cd /d "%~dp0.."

set JDK_DIR=
for /d %%d in ("%USERPROFILE%\android-tools\jdk-*") do set JDK_DIR=%%d
if not defined JDK_DIR (
  echo [X] JDK not found, run scripts\setup-env.bat first
  exit /b 1
)
set PATH=%JDK_DIR%\bin;%PATH%

set SDK=%LOCALAPPDATA%\Android\Sdk
set BT=%SDK%\build-tools\34.0.0
set PLAT=%SDK%\platforms\android-34\android.jar
if not exist "%PLAT%" (
  echo [X] android.jar not found, run scripts\setup-env.bat first
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
if exist build (echo [X] build dir is locked, close other programs and retry & exit /b 1)
mkdir build || exit /b 1
"%BT%\aapt2.exe" compile --dir res -o build\res.zip || exit /b 1

echo [2/6] aapt2 link...
"%BT%\aapt2.exe" link -o build\app.unsigned.apk -I "%PLAT%" ^
  --manifest AndroidManifest.xml --java build\gen ^
  --min-sdk-version 30 --target-sdk-version 34 ^
  --version-code 8 --version-name 0.1.2.2 ^
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
rem library jars must be d8 INPUTS (--lib only resolves refs, never packages)
setlocal enabledelayedexpansion
call "%BT%\d8.bat" --release --lib "%PLAT%" --min-api 30 ^
  --output build "build\classes.jar" || exit /b 1
endlocal

echo [5/6] pack dex files...
powershell -NoProfile -Command ^
  "Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem; $z=[System.IO.Compression.ZipFile]::Open('build\app.unsigned.apk','Update'); Get-ChildItem 'build' -Filter '*.dex' | ForEach-Object { $e=$z.CreateEntry($_.Name); $s=$e.Open(); $f=[System.IO.File]::OpenRead($_.FullName); $f.CopyTo($s); $f.Close(); $s.Close() }; $z.Dispose()" || exit /b 1

echo [6/6] zipalign + sign...
"%BT%\zipalign.exe" -f 4 build\app.unsigned.apk build\app.aligned.apk || exit /b 1
call "%BT%\apksigner.bat" sign --ks keystore\debug.keystore ^
  --ks-pass pass:android --key-pass pass:android ^
  --ks-key-alias androiddebugkey ^
  --out tws-control-wear-debug.apk build\app.aligned.apk || exit /b 1

echo.
echo [OK] tws-control-wear-debug.apk
endlocal
