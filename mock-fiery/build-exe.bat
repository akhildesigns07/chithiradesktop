@echo off
setlocal
rem ======================================================================
rem  Builds the test server:  dist\Mock Fiery\Mock Fiery.exe  (Java included)
rem  Needs JDK 17+ (jpackage) and Maven on the PC that builds it.
rem ======================================================================

cd /d "%~dp0"

where mvn >nul 2>nul
if errorlevel 1 (
    echo Maven was not found. Install Apache Maven and add its bin folder to PATH.
    goto :fail
)
where jpackage >nul 2>nul
if errorlevel 1 (
    echo jpackage was not found. Install JDK 17 or newer and add its bin folder to PATH.
    goto :fail
)

echo [1/2] Building the mock server jar...
call mvn -q -B package
if errorlevel 1 goto :fail

if exist target\jpackage-input rmdir /s /q target\jpackage-input
mkdir target\jpackage-input
copy /y target\mock-fiery-1.0.0-all.jar target\jpackage-input\ >nul
if exist "..\dist\Mock Fiery" rmdir /s /q "..\dist\Mock Fiery"

echo [2/2] Creating Mock Fiery.exe...
jpackage --type app-image ^
  --name "Mock Fiery" ^
  --app-version 1.0.0 ^
  --vendor "Chithira Printers" ^
  --description "Test server that behaves like a Xerox Fiery" ^
  --icon ..\assets\mock.ico ^
  --win-console ^
  --input target\jpackage-input ^
  --main-jar mock-fiery-1.0.0-all.jar ^
  --main-class com.chithira.mockfiery.MockFiery ^
  --dest ..\dist
if errorlevel 1 goto :fail

echo.
echo ======================================================================
echo  Done:  dist\Mock Fiery\Mock Fiery.exe
echo  Double-click it: a console window shows what it receives and your
echo  browser opens https://localhost:8443/  (accept the test certificate).
echo  In the hot folder app set  fiery.base-url=https://localhost:8443
echo  and fiery.api-key=MOCK-KEY, fiery.username=test, fiery.password=test
echo ======================================================================
pause
exit /b 0

:fail
echo.
echo Build FAILED. See the messages above.
pause
exit /b 1
