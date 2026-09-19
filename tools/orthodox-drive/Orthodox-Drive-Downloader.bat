@echo off
setlocal
cd /d "%~dp0"

echo.
echo ==============================================================
echo   Orthodox PDF Collection - Automatic Drive Downloader
echo ==============================================================
echo.
echo Launching PowerShell downloader...
echo.

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Orthodox-Drive-Downloader.ps1"

set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (
  echo DONE - download and verification completed successfully.
) else (
  echo Downloader exited with code %RC%.
)
echo.
pause
exit /b %RC%
