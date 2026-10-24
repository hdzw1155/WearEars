@echo off
chcp 65001 >nul
rem ============================================================
rem  一次性环境安装脚本(Windows):
rem  1. 下载 Microsoft JDK 17 到 %USERPROFILE%\android-tools\jdk-ms
rem  2. 安装 Android SDK 命令行工具到 %LOCALAPPDATA%\Android\Sdk
rem  3. 安装 platforms;android-34 + build-tools;34.0.0
rem ============================================================
setlocal
set TOOLS=%USERPROFILE%\android-tools
set SDK=%LOCALAPPDATA%\Android\Sdk

if not exist "%TOOLS%" mkdir "%TOOLS%"
cd /d "%TOOLS%"

if not exist jdk-ms (
  echo [1/3] 下载 JDK 17...
  curl -sL -o jdk-ms.zip "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip" || exit /b 1
  echo       解压...
  mkdir jdk-ms
  tar -xf jdk-ms.zip -C jdk-ms --strip-components=1 || exit /b 1
  del jdk-ms.zip
)

set JDK_DIR=
for /d %%d in ("%TOOLS%\jdk-*") do set JDK_DIR=%%d
set PATH=%JDK_DIR%\bin;%PATH%

if not exist "%SDK%\cmdline-tools\latest" (
  echo [2/3] 下载 Android 命令行工具...
  if not exist cmdline-tools.zip curl -sL -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" || exit /b 1
  mkdir "%SDK%\cmdline-tools" 2>nul
  tar -xf cmdline-tools.zip -C "%SDK%\cmdline-tools" || exit /b 1
  move "%SDK%\cmdline-tools\cmdline-tools" "%SDK%\cmdline-tools\latest" >nul || exit /b 1
)

echo [3/3] 安装 SDK 组件(约 150MB)...
(for /l %%i in (1,1,20) do @echo y) | call "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" --licenses >nul
echo y| call "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-34" "build-tools;34.0.0" || exit /b 1

echo [OK] 环境就绪, 运行 scripts\build.bat 构建 APK
endlocal
