@echo off
setlocal
rem Compile and run tests against the existing application JAR.
rem Never rebuild production sources, modify out, or replace the application JAR.
cd /d "%~dp0" || exit /b 1

if not exist "TemplateTool.jar" (
    echo [ERROR] TemplateTool.jar is missing. Run build.bat first.
    goto :failed
)

set "JAVAC=javac"
set "BUILD_JAVA=java"
set "_JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "BUILD_JAVA=%JAVA_HOME%\bin\java.exe"
    set "_JDK=1"
)
if not defined _JDK if exist "%ProgramFiles%\Java" (
    for /f "delims=" %%d in ('dir /b /o-n "%ProgramFiles%\Java\jdk-*" 2^>nul') do (
        if not defined _JDK if exist "%ProgramFiles%\Java\%%d\bin\javac.exe" (
            set "JAVAC=%ProgramFiles%\Java\%%d\bin\javac.exe"
            set "BUILD_JAVA=%ProgramFiles%\Java\%%d\bin\java.exe"
            set "_JDK=1"
        )
    )
)
echo JDK: %JAVAC%
"%JAVAC%" -version >nul 2>nul
if errorlevel 1 goto :jdkError
"%BUILD_JAVA%" -version >nul 2>nul
if errorlevel 1 goto :jdkError

rem Clean only test output, relative to the script directory.
if exist "out-test" rd /s /q "out-test"
if exist "out-test" goto :cleanError
mkdir "out-test"
if errorlevel 1 goto :failed
if not exist "work\build" mkdir "work\build"

echo Compiling test sources against TemplateTool.jar...
call :sourceList "test\java" "work\build\test-sources.txt"
if errorlevel 1 goto :failed
"%JAVAC%" -J-Dfile.encoding=UTF-8 --release 17 -encoding UTF-8 -implicit:none -cp "TemplateTool.jar" -sourcepath "test\java" -d "out-test" @work\build\test-sources.txt
if errorlevel 1 goto :failed

echo Running regression tests...
"%BUILD_JAVA%" -cp "TemplateTool.jar;out-test" com.firefly.AllTests
if errorlevel 1 goto :failed

echo.
echo Test build and regression tests passed.
exit /b 0

:sourceList
rem UTF-8 without BOM; recursive source discovery supports future packages.
powershell -NoProfile -Command "$ErrorActionPreference='Stop'; $root=(Get-Location).Path; $sources=@(Get-ChildItem -LiteralPath '%~1' -Filter '*.java' -File -Recurse | Sort-Object FullName | ForEach-Object { [char]34 + $_.FullName.Substring($root.Length+1).Replace('\','/') + [char]34 }); if ($sources.Count -eq 0) { throw 'No Java source files found' }; [IO.File]::WriteAllLines((Join-Path $root '%~2'), [string[]]$sources, [Text.UTF8Encoding]::new($false))"
exit /b %errorlevel%

:jdkError
echo [ERROR] JDK 17 or later with javac and java is required. Check JAVA_HOME.
goto :failed
:cleanError
echo [ERROR] Cannot clean out-test. Close programs using this directory.
:failed
echo [ERROR] Test build or regression tests failed.
pause
exit /b 1
