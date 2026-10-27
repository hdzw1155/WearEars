@echo off
rem ============================================================
rem  fetch androidx.wear.tiles libs for the no-Gradle build
rem  run once:  scripts\fetch-libs.bat   (needs curl + JDK jar)
rem  the jars are NOT committed (see .gitignore)
rem  sources: https://dl.google.com/android/maven2
rem ============================================================
setlocal
cd /d "%~dp0.."

set JDK_DIR=
for /d %%d in ("%USERPROFILE%\android-tools\jdk-*") do set JDK_DIR=%%d
if defined JDK_DIR set PATH=%JDK_DIR%\bin;%PATH%

set M=https://dl.google.com/android/maven2
if not exist libs mkdir libs

if not exist "libs\tiles-1.1.0.jar" (
  echo [1/5] tiles-1.1.0.aar -^> classes.jar
  call :get "androidx/wear/tiles/tiles/1.1.0/tiles-1.1.0.aar" "libs\tiles-1.1.0.aar" || exit /b 1
  pushd libs || exit /b 1
  jar xf tiles-1.1.0.aar classes.jar || exit /b 1
  popd
  del "libs\tiles-1.1.0.aar" >nul 2>&1
  if not exist "libs\classes.jar" exit /b 1
  move /y "libs\classes.jar" "libs\tiles-1.1.0.jar" >nul || exit /b 1
)

if not exist "libs\tiles-proto-1.1.0.jar" (
  echo [2/5] tiles-proto-1.1.0.jar
  call :get "androidx/wear/tiles/tiles-proto/1.1.0/tiles-proto-1.1.0.jar" "libs\tiles-proto-1.1.0.jar" || exit /b 1
)

if not exist "libs\annotation-1.2.0.jar" (
  echo [3/5] annotation-1.2.0.jar
  call :get "androidx/annotation/annotation/1.2.0/annotation-1.2.0.jar" "libs\annotation-1.2.0.jar" || exit /b 1
)

if not exist "libs\annotation-experimental-1.2.0.jar" (
  echo [4/5] annotation-experimental-1.2.0.aar -^> classes.jar
  call :get "androidx/annotation/annotation-experimental/1.2.0/annotation-experimental-1.2.0.aar" "libs\annotation-experimental-1.2.0.aar" || exit /b 1
  pushd libs || exit /b 1
  jar xf annotation-experimental-1.2.0.aar classes.jar || exit /b 1
  popd
  del "libs\annotation-experimental-1.2.0.aar" >nul 2>&1
  if not exist "libs\classes.jar" exit /b 1
  move /y "libs\classes.jar" "libs\annotation-experimental-1.2.0.jar" >nul || exit /b 1
)

if not exist "libs\listenablefuture-1.0.jar" (
  echo [5/5] listenablefuture-1.0.jar
  call :get "com/google/guava/listenablefuture/1.0/listenablefuture-1.0.jar" "libs\listenablefuture-1.0.jar" || exit /b 1
)

for %%j in (tiles-1.1.0.jar tiles-proto-1.1.0.jar annotation-1.2.0.jar annotation-experimental-1.2.0.jar listenablefuture-1.0.jar) do (
  if not exist "libs\%%j" (
    echo [X] missing libs\%%j
    exit /b 1
  )
)
echo [OK] tile libs ready in libs\
exit /b 0

rem ------------------------------------------------------------
rem :get <maven-relative-path> <local-file>
rem curl -f fails on HTTP errors; retry-free is fine for maven
rem ------------------------------------------------------------
:get
if exist "%~2" goto :eof
curl -fsSL --max-time 180 "%M%/%~1" -o "%~2" || (
  echo [X] download failed: %~1
  exit /b 1
)
goto :eof
