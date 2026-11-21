@echo off
setlocal EnableExtensions
title Print Hub - install as a Windows service
rem ---------------------------------------------------------------------------
rem  Installs the Print Hub as a Windows service that starts with the PC,
rem  starts it now, and allows the port through the Windows firewall
rem  (private networks only) so other PCs in the shop can open it.
rem  Right-click > Run as administrator.
rem ---------------------------------------------------------------------------
set "PORT=8080"
cd /d "%~dp0"

net session >nul 2>&1
if errorlevel 1 (
  echo Please right-click install-service.bat and choose "Run as administrator".
  pause
  exit /b 1
)
if not exist "config\application.properties" (
  echo The database is not set up yet. Run setup-database.bat first.
  pause
  exit /b 1
)

PrintHubService.exe install
if errorlevel 1 (
  echo Could not install the service. If it is already installed, run uninstall-service.bat first.
  pause
  exit /b 1
)
netsh advfirewall firewall delete rule name="Chithira Print Hub" >nul 2>&1
netsh advfirewall firewall add rule name="Chithira Print Hub" dir=in action=allow protocol=TCP localport=%PORT% profile=private >nul
PrintHubService.exe start

echo.
echo The Print Hub is starting (this takes about 20 seconds the first time).
echo.
echo   On this PC:      http://localhost:%PORT%
echo   On other PCs:    http://THIS-PC-IP:%PORT%   (find the IP with: ipconfig)
echo.
echo The first login (user admin) is in FIRST-LOGIN.txt in this folder once it has started.
echo Make sure Windows treats the shop network as a Private network.
timeout /t 25 /nobreak >nul
if exist "FIRST-LOGIN.txt" start "" notepad "FIRST-LOGIN.txt"
start "" "http://localhost:%PORT%"
pause
