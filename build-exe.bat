@echo off
setlocal
rem ======================================================================
rem  Builds the Windows app:  dist\Fiery Hot Folder\Fiery Hot Folder.exe
rem  Java is bundled inside, so the shop PC does NOT need Java installed.
rem
rem  Needs on the PC that BUILDS it (only once):
rem    - JDK 17 or newer, e.g. Eclipse Temurin 21  (provides "jpackage")
rem    - Apache Maven                              (provides "mvn")
rem
rem  Usage:  double-click this file, or run  build-exe.bat  in a terminal.
rem          build-exe.bat installer   also makes a Setup .exe (needs WiX Toolset 3)
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

echo [1/3] Building the jar with Maven...
call mvn -q -B package
if errorlevel 1 goto :fail

echo [2/3] Preparing files...
if exist target\jpackage-input rmdir /s /q target\jpackage-input
mkdir target\jpackage-input
copy /y target\fiery-hotfolder-1.0.0-all.jar target\jpackage-input\ >nul
if exist "dist\Fiery Hot Folder" rmdir /s /q "dist\Fiery Hot Folder"

echo [3/3] Creating Fiery Hot Folder.exe with bundled Java...
jpackage --type app-image ^
  --name "Fiery Hot Folder" ^
  --app-version 1.0.0 ^
  --vendor "Chithira Printers" ^
  --description "Hot folder for Xerox Fiery" ^
  --icon assets\app.ico ^
  --input target\jpackage-input ^
  --main-jar fiery-hotfolder-1.0.0-all.jar ^
  --main-class com.chithira.fierydesk.App ^
  --java-options "-Xmx2g" ^
  --dest dist
if errorlevel 1 goto :fail

copy /y fiery-hotfolder.properties "dist\Fiery Hot Folder\" >nul

if /i "%~1"=="installer" (
    echo Creating Setup installer...
    jpackage --type exe ^
      --app-image "dist\Fiery Hot Folder" ^
      --name "Fiery Hot Folder" ^
      --app-version 1.0.0 ^
      --vendor "Chithira Printers" ^
      --win-shortcut --win-menu --win-dir-chooser ^
      --dest dist
    if errorlevel 1 goto :fail
)

echo.
echo ======================================================================
echo  Done:  dist\Fiery Hot Folder\Fiery Hot Folder.exe
echo.
echo  1. Edit dist\Fiery Hot Folder\fiery-hotfolder.properties
echo     (Fiery address, API key, user, password, hot folder).
echo  2. Copy the whole "Fiery Hot Folder" folder to the shop PC
echo     and double-click "Fiery Hot Folder.exe".
echo ======================================================================
pause
exit /b 0

:fail
echo.
echo Build FAILED. See the messages above.
pause
exit /b 1
