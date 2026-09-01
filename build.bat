@echo off
setlocal
rem ============================================
rem  Template Filler Tool - build script
rem  Compiles sources and packages TemplateTool.jar
rem  Requires a JDK 17 or later (javac + jar).
rem
rem  JDK resolution order:
rem    1. %%JAVA_HOME%% if set
rem    2. newest jdk-* folder under %%ProgramFiles%%\Java
rem    3. javac / jar on PATH
rem ============================================
cd /d "%~dp0"

rem Clear stale classes from a previous build, so they never leak into the jar.
if exist "out" rd /s /q "out"

rem Default to PATH tools; a JDK dir below overrides them.
set "JAVAC=javac"
set "JAR=jar"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
    set "_JDK=1"
)
if not defined _JDK if exist "%ProgramFiles%\Java" (
    for /f "delims=" %%d in ('dir /b /o-n "%ProgramFiles%\Java\jdk-*" 2^>nul') do (
        if not defined _JDK if exist "%ProgramFiles%\Java\%%d\bin\javac.exe" (
            set "JAVAC=%ProgramFiles%\Java\%%d\bin\javac.exe"
            set "JAR=%ProgramFiles%\Java\%%d\bin\jar.exe"
            set "_JDK=1"
        )
    )
)

echo.
echo JDK: %JAVAC%
"%JAVAC%" -version >nul 2>nul
if errorlevel 1 (
    echo [ERROR] javac not found. Please install JDK 17 or later, or set JAVA_HOME.
    pause
    exit /b 1
)
"%JAR%" --version >nul 2>nul
if errorlevel 1 (
    echo [ERROR] jar not found. Please install a full JDK, not just a JRE.
    pause
    exit /b 1
)
echo.

echo Compiling sources...
"%JAVAC%" --release 17 -encoding UTF-8 -d "out" -sourcepath "src\main\java" ^
    src\main\java\com\firefly\*.java ^
    src\main\java\com\firefly\core\*.java ^
    src\main\java\com\firefly\ui\*.java
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)
echo.

echo Packaging TemplateTool.jar...
"%JAR%" --create --file "TemplateTool.jar" --main-class com.firefly.Main -C "out" .
if errorlevel 1 (
    echo [ERROR] Packaging failed.
    pause
    exit /b 1
)
echo.

echo Build OK: TemplateTool.jar
echo Double click "launcher.bat" to run.
endlocal
