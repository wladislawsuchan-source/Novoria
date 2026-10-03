@echo off
setlocal
cd /d "%~dp0"
call mvn clean package
if errorlevel 1 exit /b 1
if not exist build mkdir build
copy /Y "novo-hub\target\NovoHub.jar" "build\NovoHub.jar" >nul
copy /Y "novo-smp\target\NovoSMP.jar" "build\NovoSMP.jar" >nul
echo.
echo Fertig: build\NovoHub.jar und build\NovoSMP.jar
pause
