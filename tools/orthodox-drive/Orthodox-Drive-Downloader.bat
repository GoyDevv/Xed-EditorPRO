@echo off
setlocal
title Orthodox PDF Collection Downloader

set "PS1=%~dp0Orthodox-Drive-Downloader.ps1"

if not exist "%PS1%" (
  echo ERROR: Orthodox-Drive-Downloader.ps1 is missing.
  echo Put it next to this .bat file.
  pause
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
set "RC=%ERRORLEVEL%"

echo.
if "%RC%"=="0" (
  echo ==========================================
  echo SUCCESS - DOWNLOAD COMPLETED AND VERIFIED
  echo ==========================================
) else (
  echo ==========================================
  echo FINISHED WITH STATUS %RC%
  echo Check .orthodox-drive-downloader for logs.
  echo ==========================================
)
echo.
pause
exit /b %RC%
