@echo off
setlocal EnableExtensions
title Print Hub - set up the database
rem ---------------------------------------------------------------------------
rem  Creates the PostgreSQL database "printhub" and its user "printhub", and
rem  stores that user's password in config\application.properties.
rem  Run once, after installing PostgreSQL. Needs the password of the
rem  "postgres" administrator chosen while installing PostgreSQL.
rem ---------------------------------------------------------------------------
cd /d "%~dp0"

set "PSQL="
for /d %%V in ("%ProgramFiles%\PostgreSQL\*") do if exist "%%V\bin\psql.exe" set "PSQL=%%V\bin\psql.exe"
if not defined PSQL (
  echo PostgreSQL was not found in "%ProgramFiles%\PostgreSQL".
  echo Install PostgreSQL 16 from https://www.postgresql.org/download/windows/ first, then run this again.
  pause
  exit /b 1
)
echo Using %PSQL%
echo.

echo Choose a password for the Print Hub's database user (letters and digits only, at least 8):
set /p "DBPASS=Password: "
echo %DBPASS%| findstr /r "^[A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9]*$" >nul
if errorlevel 1 (
  echo The password must be at least 8 letters or digits, with no spaces or symbols.
  pause
  exit /b 1
)

echo.
echo Now enter the password of the "postgres" administrator (chosen while installing PostgreSQL).
"%PSQL%" -U postgres -h localhost -v ON_ERROR_STOP=1 -c "CREATE ROLE printhub LOGIN PASSWORD '%DBPASS%'" -c "CREATE DATABASE printhub OWNER printhub ENCODING 'UTF8'"
if errorlevel 1 (
  echo.
  echo Could not create the database. If it already exists, that's fine - just make sure
  echo config\application.properties has the right password. Otherwise check the postgres password.
  pause
  exit /b 1
)

> "config\application.properties" echo spring.datasource.password=%DBPASS%
echo.
echo Done. The database "printhub" is ready and its password is saved in config\application.properties.
echo Next: right-click install-service.bat and choose "Run as administrator".
pause
