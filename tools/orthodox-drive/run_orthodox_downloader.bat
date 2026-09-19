@echo off
setlocal
title Orthodox PDF Collection Downloader

set "SCRIPT=%~dp0orthodox_drive_complete_downloader.sh"

if not exist "%SCRIPT%" (
  echo ERROR: orthodox_drive_complete_downloader.sh was not found next to this file.
  pause
  exit /b 1
)

set "BASH="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"

if not defined BASH (
  where bash.exe >nul 2>&1
  if not errorlevel 1 set "BASH=bash.exe"
)

if not defined BASH (
  echo ERROR: Git for Windows / Git Bash was not found.
  echo Install Git for Windows, then run this file again.
  pause
  exit /b 1
)

echo Starting the complete Google Drive downloader...
echo.
"%BASH%" "%SCRIPT%"
set "RC=%ERRORLEVEL%"

echo.
if "%RC%"=="0" (
  echo ==========================================
  echo DOWNLOAD COMPLETED AND VERIFIED
  echo ==========================================
) else (
  echo ==========================================
  echo DOWNLOAD FINISHED WITH STATUS %RC%
  echo Check the .orthodox-download folder for logs.
  echo ==========================================
)
echo.
pause
exit /b %RC%
