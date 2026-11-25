@echo off
py -X utf8 "%~dp0build.py" %*
exit /b %errorlevel%
