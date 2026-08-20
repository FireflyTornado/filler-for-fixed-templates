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
rem
rem  NOTE: on this machine cmd's parser misbehaves when a
rem  parenthesized block directly follows another block
rem  (after a goto).  Blank "echo." separator lines between
rem  blocks keep it sane - do not delete them.
rem ============================================
cd /d "%~dp0"

rem Default to PATH tools; a JDK dir below overrides them.
set "JAVAC=javac"
set "JAR=jar"

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
    goto :have_jdk
)
if exist "%ProgramFiles%\Java" (
    for /f "delims=" %%d in ('dir /b /o-n "%ProgramFiles%\Java\jdk-*" 2^>nul') do (
        if exist "%ProgramFiles%\Java\%%d\bin\javac.exe" (
            set "JAVAC=%ProgramFiles%\Java\%%d\bin\javac.exe"
            set "JAR=%ProgramFiles%\Java\%%d\bin\jar.exe"
            goto :have_jdk
        )
    )
)

:have_jdk
echo.
echo JDK: %JAVAC%
"%JAVAC%" -version >nul 2>nul
if errorlevel 1 (
    echo [ERROR] javac not found. Please install JDK 17 or later, or set JAVA_HOME.
    pause
    exit /b 1
)
echo.
where jar >nul 2>nul
if errorlevel 1 (
    if "%JAR%"=="jar" (
        echo [ERROR] jar not found. Please install a full JDK, not just a JRE.
        pause
        exit /b 1
    )
)
echo.

if not exist "out" mkdir "out"
echo Compiling sources...
"%JAVAC%" --release 17 -encoding UTF-8 -d "out" -sourcepath "src" ^
    src\main\java\com\firefly\Main.java ^
    src\main\java\com\firefly\TemplateToolApp.java ^
    src\main\java\com\firefly\TemplateConstants.java ^
    src\main\java\com\firefly\core\ConfigStore.java ^
    src\main\java\com\firefly\core\ExpressionEvaluator.java ^
    src\main\java\com\firefly\core\LastValuesStore.java ^
    src\main\java\com\firefly\core\MiniJson.java ^
    src\main\java\com\firefly\core\TemplateParser.java ^
    src\main\java\com\firefly\core\TemplateRenderer.java ^
    src\main\java\com\firefly\core\TextFileWriter.java ^
    src\main\java\com\firefly\core\ValueNormalizer.java ^
    src\main\java\com\firefly\ui\ResultPanel.java ^
    src\main\java\com\firefly\ui\ScrollablePanel.java ^
    src\main\java\com\firefly\ui\StringInputPanel.java ^
    src\main\java\com\firefly\ui\VariableInputPanel.java
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)
echo.

echo Main-Class: com.firefly.Main> manifest.tmp
"%JAR%" cfm "TemplateTool.jar" manifest.tmp -C "out" .
if errorlevel 1 (
    echo [ERROR] Packaging failed.
    pause
    exit /b 1
)
echo.

del manifest.tmp
echo.
echo Build OK: TemplateTool.jar
echo Double click "launcher.bat" to run.
endlocal
