@echo off
chcp 65001 >nul
rem 下载磁贴库 androidx.wear.tiles 及依赖到 libs\ (供 build.bat 使用)
setlocal
cd /d "%~dp0.."
if not exist libs mkdir libs
set M=https://dl.google.com/android/maven2
set OK=1
if not exist libs\tiles-1.1.0.jar call :get "androidx/wear/tiles/tiles/1.1.0/tiles-1.1.0.aar" "tiles-1.1.0.aar" aar
if not exist libs\tiles-proto-1.1.0.jar call :get "androidx/wear/tiles/tiles-proto/1.1.0/tiles-proto-1.1.0.jar" "tiles-proto-1.1.0.jar" jar
if not exist libs\annotation-1.2.0.jar call :get "androidx/annotation/annotation/1.2.0/annotation-1.2.0.jar" "annotation-1.2.0.jar" jar
if not exist libs\annotation-experimental-1.2.0.jar call :get "androidx/annotation/annotation-experimental/1.2.0/annotation-experimental-1.2.0.aar" "annotation-experimental-1.2.0.aar" aar
if not exist libs\listenablefuture-1.0.jar call :get "com/google/guava/listenablefuture/1.0/listenablefuture-1.0.jar" "listenablefuture-1.0.jar" jar central
if "%OK%"=="1" (echo [OK] 依赖就绪) else (echo [X] 有下载失败, 重跑本脚本重试 & exit /b 1)
endlocal
exit /b 0

:get
echo 下载 %~2 ...
curl -sL --max-time 120 -o "libs\%~2" "%M%/%~1" || set OK=0
if not exist "libs\%~2" set OK=0
if "%~3"=="aar" if exist "libs\%~2" (
  powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('libs\%~2','libs\_tmp')" || set OK=0
  if exist "libs\_tmp\classes.jar" move /y "libs\_tmp\classes.jar" "libs\%~n2.jar" >nul
  rmdir /s /q "libs\_tmp" 2>nul
)
goto :eof
