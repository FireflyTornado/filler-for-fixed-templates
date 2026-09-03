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
call :embedded dependencies
if errorlevel 1 goto :failed
call :embedded cleanup
if errorlevel 1 goto :cleanError
mkdir "out-test"
if errorlevel 1 goto :failed
mkdir "work\build-test\tmp"
if errorlevel 1 goto :failed

echo Compiling test sources against TemplateTool.jar...
call :sourceList "test\java" "work\build-test\test-sources.txt"
if errorlevel 1 goto :failed
"%JAVAC%" -J-Dfile.encoding=UTF-8 --release 17 -encoding UTF-8 -proc:none -implicit:none -cp "TemplateTool.jar;lib/*" -sourcepath "test\java" -d "out-test" @work\build-test\test-sources.txt
if errorlevel 1 goto :failed

echo Running regression tests...
"%BUILD_JAVA%" "-Djava.io.tmpdir=%CD%\work\build-test\tmp" -cp "TemplateTool.jar;lib/*;out-test" com.firefly.AllTests
if errorlevel 1 goto :failed
call :embedded cleanup
if errorlevel 1 goto :cleanError

echo.
echo Test build and regression tests passed.
exit /b 0

:embedded
rem Read the PowerShell section from this BAT directly; no helper files are generated.
set "TEMPLATE_BUILD_SCRIPT=%~f0"
set "TEMPLATE_BUILD_ACTION=%~1"
powershell -NoProfile -Command "$ErrorActionPreference='Stop'; try { $text=[IO.File]::ReadAllText($env:TEMPLATE_BUILD_SCRIPT); $code=($text -split '(?m)^# POWERSHELL\r?$',2)[1]; & ([ScriptBlock]::Create($code)) } catch { Write-Error $_; exit 1 }"
exit /b %errorlevel%

:sourceList
rem UTF-8 without BOM; recursive source discovery supports future packages.
powershell -NoProfile -Command "$ErrorActionPreference='Stop'; $root=(Get-Location).Path; $sources=@(Get-ChildItem -LiteralPath '%~1' -Filter '*.java' -File -Recurse | Sort-Object FullName | ForEach-Object { [char]34 + $_.FullName.Substring($root.Length+1).Replace('\','/') + [char]34 }); if ($sources.Count -eq 0) { throw 'No Java source files found' }; [IO.File]::WriteAllLines((Join-Path $root '%~2'), [string[]]$sources, [Text.UTF8Encoding]::new($false))"
exit /b %errorlevel%

:jdkError
echo [ERROR] JDK 17 or later with javac and java is required. Check JAVA_HOME.
goto :failed
:cleanError
echo [ERROR] Cannot clean test build temporary files. Close programs using these files.
:failed
call :embedded cleanup
if errorlevel 1 echo [ERROR] Temporary files could not be fully cleaned; retry after closing programs using them.
echo [ERROR] Test build or regression tests failed.
pause
exit /b 1

# POWERSHELL
# Internal helpers live here so the two build entry points remain self-contained.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $env:TEMPLATE_BUILD_SCRIPT))
function Clear-Build {
$ErrorActionPreference = 'Stop'
$Target = 'Test'
$prefix = $projectRoot.TrimEnd('\') + '\'

function Assert-SafePath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Cleanup path is outside the project: $absolute"
    }
    # Check existing ancestors as well: a junction must never redirect cleanup.
    $current = $absolute
    while ($current.Length -gt $projectRoot.Length) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Cleanup refuses a symbolic link or junction: $current"
            }
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
}

function Assert-SafeTree([string]$Path) {
    Assert-SafePath $Path
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $pending = New-Object 'System.Collections.Generic.Stack[string]'
    $pending.Push($Path)
    while ($pending.Count -gt 0) {
        $item = Get-Item -LiteralPath $pending.Pop() -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Cleanup refuses a symbolic link or junction: $($item.FullName)"
        }
        if ($item.PSIsContainer) {
            foreach ($child in Get-ChildItem -LiteralPath $item.FullName -Force) {
                $pending.Push($child.FullName)
            }
        }
    }
}

try {
    # Only build-owned locations are eligible; never remove the other build's files.
    $relativePaths = if ($Target -eq 'Main') { @('out', 'work/build-main') } else { @('out-test', 'work/build-test') }
    $paths = @($relativePaths | ForEach-Object { Join-Path $projectRoot $_ })
    foreach ($path in $paths) { Assert-SafeTree $path }
    foreach ($path in $paths) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    }
    $work = Join-Path $projectRoot 'work'
    Assert-SafePath $work
    if ((Test-Path -LiteralPath $work -PathType Container) -and @(Get-ChildItem -LiteralPath $work -Force).Count -eq 0) {
        Remove-Item -LiteralPath $work
    }
} catch {
    Write-Error $_
    exit 1
}
}

function Prepare-Dependencies {
$ErrorActionPreference = 'Stop'
$CheckOnly = $false
function Get-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose(); $stream.Dispose() }
}
try {
    $dependencies = Get-Content -LiteralPath (Join-Path $projectRoot 'dependencies.lock.json') -Raw | ConvertFrom-Json
    $lib = Join-Path $projectRoot 'lib'
    if (-not $CheckOnly) { New-Item -ItemType Directory -Force -Path $lib | Out-Null }
    foreach ($dependency in $dependencies) {
        if ($dependency.file -notmatch '^[A-Za-z0-9_.-]+\.jar$') { throw 'Invalid dependency filename' }
        $path = Join-Path $lib $dependency.file
        if (-not (Test-Path -LiteralPath $path) -or (Get-Sha256 $path) -ne $dependency.sha256) {
            if ($CheckOnly) { throw ('Missing dependency: ' + $dependency.file + '. Run build.bat first.') }
            if (-not $dependency.url.StartsWith('https://repo.maven.apache.org/maven2/')) { throw 'Unexpected dependency source' }
            Write-Output ('Downloading ' + $dependency.file)
            $temporary = Join-Path $lib ($dependency.file + '.' + [Guid]::NewGuid().ToString('N') + '.download')
            try {
                [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                $client = New-Object Net.WebClient
                try { $client.DownloadFile($dependency.url, $temporary) } finally { $client.Dispose() }
                if ((Get-Sha256 $temporary) -ne $dependency.sha256) { throw ('Checksum mismatch: ' + $dependency.file) }
                Move-Item -LiteralPath $temporary -Destination $path -Force
            } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary } }
        }
        if ((Get-Sha256 $path) -ne $dependency.sha256) { throw ('Checksum mismatch: ' + $dependency.file) }
    }
    Write-Output 'Excel dependencies verified.'
} catch { Write-Error $_; exit 1 }
}

switch ($env:TEMPLATE_BUILD_ACTION) {
    'cleanup' { Clear-Build }
    'dependencies' { Prepare-Dependencies }
    default { throw 'Unknown internal build action' }
}
