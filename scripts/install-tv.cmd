@echo off
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%install-tv.ps1"

if "%~1"=="" (
    powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%"
    set "EXITCODE=!ERRORLEVEL!"
    echo.
    pause
    exit /b !EXITCODE!
)

powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" %*
set "EXITCODE=!ERRORLEVEL!"
if not "!EXITCODE!"=="0" (
    echo.
    pause
)
exit /b !EXITCODE!
