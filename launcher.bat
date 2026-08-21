@echo off
rem ============================================
rem  Template Filler Tool - launcher
rem  Double click this file to run the tool.
rem ============================================
setlocal
cd /d "%~dp0"

set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"

"%JAVA%" -version >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java not found. Please install JDK 17 or later.
    pause
    exit /b 1
)

if not exist "TemplateTool.jar" (
    echo [ERROR] TemplateTool.jar not found. Please run build.bat first.
    pause
    exit /b 1
)

"%JAVA%" -jar "TemplateTool.jar"
endlocal
