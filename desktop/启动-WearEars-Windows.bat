@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "REPO_ROOT=%%~fI"
set "JAR_FILE=%REPO_ROOT%\build\desktop\WearEars-Desktop.jar"

set "JAVAW_EXE="
if exist "%USERPROFILE%\android-tools\jdk-ms\bin\javaw.exe" (
    set "JAVAW_EXE=%USERPROFILE%\android-tools\jdk-ms\bin\javaw.exe"
) else (
    for %%J in (javaw.exe) do set "JAVAW_EXE=%%~$PATH:J"
)

if not defined JAVAW_EXE (
    echo [WearEars] Java runtime ^(javaw.exe^) not found.
    pause
    exit /b 1
)

if not exist "%JAR_FILE%" (
    echo [WearEars] Building WearEars Desktop...
    pushd "%REPO_ROOT%"
    py -X utf8 desktop\build_desktop.py build
    if errorlevel 1 (
        popd
        echo [WearEars] Build failed.
        pause
        exit /b 1
    )
    popd
)

start "" "%JAVAW_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR_FILE%"
exit /b 0
