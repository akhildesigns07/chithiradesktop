@echo off
setlocal EnableExtensions
title Print Hub - remove the Windows service
rem  Stops and removes the service and its firewall rule. Your data stays in PostgreSQL.
rem  Right-click > Run as administrator.
cd /d "%~dp0"
net session >nul 2>&1
if errorlevel 1 (
  echo Please right-click uninstall-service.bat and choose "Run as administrator".
  pause
  exit /b 1
)
PrintHubService.exe stop
PrintHubService.exe uninstall
netsh advfirewall firewall delete rule name="Chithira Print Hub" >nul 2>&1
echo The Print Hub service has been removed. Your data is still in the PostgreSQL database "printhub".
pause
