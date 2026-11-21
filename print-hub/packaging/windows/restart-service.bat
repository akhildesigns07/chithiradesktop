@echo off
setlocal EnableExtensions
title Print Hub - restart
rem  Restart after changing config\application.yml. Right-click > Run as administrator.
cd /d "%~dp0"
net session >nul 2>&1
if errorlevel 1 (
  echo Please right-click restart-service.bat and choose "Run as administrator".
  pause
  exit /b 1
)
PrintHubService.exe restart
echo Restarted. Give it about 20 seconds, then refresh the browser.
pause
