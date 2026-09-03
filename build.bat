@echo off
setlocal
rem Compile and package the main program only. Run build-test.bat separately.
cd /d "%~dp0" || exit /b 1

set "JAVAC=javac"
set "JAR=jar"
set "_JDK="
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
echo JDK: %JAVAC%
"%JAVAC%" -version >nul 2>nul
if errorlevel 1 goto :jdkError
"%JAR%" --version >nul 2>nul
if errorlevel 1 goto :jdkError

rem Remove leftovers from an interrupted main build; keep test files untouched.
call :embedded cleanup
if errorlevel 1 goto :cleanError
mkdir "out"
if errorlevel 1 goto :failed
mkdir "work\build-main"
if errorlevel 1 goto :failed
call :embedded dependencies
if errorlevel 1 goto :failed
call :embedded licenses
if errorlevel 1 goto :failed
call :embedded notices
if errorlevel 1 goto :failed

echo Compiling main sources...
call :sourceList "src\main\java" "work\build-main\main-sources.txt"
if errorlevel 1 goto :failed
"%JAVAC%" -J-Dfile.encoding=UTF-8 --release 17 -encoding UTF-8 -proc:none -implicit:none -cp "lib/*" -d "out" -sourcepath "src\main\java" @work\build-main\main-sources.txt
if errorlevel 1 goto :failed

echo Packaging main classes only...
"%JAR%" --create --file "work\build-main\TemplateTool.jar" --main-class com.firefly.bootstrap.Bootstrap --manifest "work\build-main\MANIFEST.MF" -C "out" . -C "." dependencies.lock.json -C "work\build-main" THIRD_PARTY_NOTICES.txt -C "." LICENSE -C "src\main\resources" .
if errorlevel 1 goto :failed
rem Publish only after packaging succeeds, preserving the old JAR on compile failure.
powershell -NoProfile -Command "$ErrorActionPreference='Stop'; Move-Item -LiteralPath 'work\build-main\TemplateTool.jar' -Destination 'TemplateTool.jar' -Force"
if errorlevel 1 goto :failed
call :embedded cleanup
if errorlevel 1 goto :cleanError

echo.
echo Build OK: TemplateTool.jar
echo Runtime dependencies download automatically on first launch; include lib for offline distribution.
echo Run "build-test.bat" to compile and run tests.
echo Double click "launcher.bat" to run the application.
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
echo [ERROR] A full JDK 17 or later is required. Check JAVA_HOME.
goto :failed
:cleanError
echo [ERROR] Cannot clean main build temporary files. Close programs using these files.
:failed
call :embedded cleanup
if errorlevel 1 echo [ERROR] Temporary files could not be fully cleaned; retry after closing programs using them.
echo [ERROR] Main program build failed.
pause
exit /b 1

# POWERSHELL
# Internal helpers live here so the two build entry points remain self-contained.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $env:TEMPLATE_BUILD_SCRIPT))
function Clear-Build {
$ErrorActionPreference = 'Stop'
$Target = 'Main'
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
    if (-not $CheckOnly) {
        $work = Join-Path $projectRoot 'work/build-main'
        New-Item -ItemType Directory -Force -Path $work | Out-Null
        $lines = @('Manifest-Version: 1.0')
        [IO.File]::WriteAllText((Join-Path $work 'MANIFEST.MF'), ($lines -join "`r`n") + "`r`n`r`n", [Text.UTF8Encoding]::new($false))
    }
    Write-Output 'Excel dependencies verified.'
} catch { Write-Error $_; exit 1 }
}

function Verify-Licenses {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $path = Join-Path $projectRoot 'src/main/resources/META-INF/THIRD_PARTY_LICENSES.txt'
    $legal = [IO.File]::ReadAllText($path).Replace("`r`n", "`n")
    $dependencies = Get-Content -LiteralPath (Join-Path $projectRoot 'dependencies.lock.json') -Raw | ConvertFrom-Json
    if ([regex]::Matches($legal, '(?m)^Component: ').Count -ne $dependencies.Count) { throw 'License inventory does not match dependency lock' }
    foreach ($dependency in $dependencies) {
        $marker = 'Component: ' + $dependency.file + "`nSHA-256: " + $dependency.sha256 + "`nSource: " + $dependency.url
        if (-not $legal.Contains($marker)) { throw ('Missing or outdated license inventory: ' + $dependency.file) }
        $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $projectRoot ('lib/' + $dependency.file)))
        try {
            foreach ($entry in $zip.Entries | Where-Object { $_.Name -match '^(LICENSE|LICENCE|NOTICE|COPYING|COPYRIGHT|THIRD.PARTY)([._-].*)?$' -or $_.FullName -match '(^|/)(licenses|legal)/' }) {
                if (-not $entry.Name) { continue }
                $reader = [IO.StreamReader]::new($entry.Open())
                try { $original = $reader.ReadToEnd().Replace("`r`n", "`n") } finally { $reader.Dispose() }
                if (-not $legal.Contains($original)) { throw ('Missing original legal text: ' + $dependency.file + '/' + $entry.FullName) }
            }
        } finally { $zip.Dispose() }
    }
    foreach ($required in @('SparseBitSet/SparseBitSet-1.3/LICENSE', 'curvesapi/1.08/license.txt', 'Copyright (c) 2005, Graph Builder', 'Paladin Software International', 'Apache Harmony', 'University of Chicago, as Operator of Argonne National')) {
        if (-not $legal.Contains($required)) { throw ('Missing supplemental attribution: ' + $required) }
    }
    Write-Output 'Third-party license inventory and original notices verified.'
}

function Prepare-Notices {
    # Keep a single Markdown source; render the simple document syntax for Swing.
    $text = [IO.File]::ReadAllText((Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md'))
    if ($text -match '(?m)^\s*(\||```|~~~)') {
        throw 'Notices use unsupported tables or fences; use headings, paragraphs and lists.'
    }
    $text = [regex]::Replace($text, '(?m)^#{1,6}\s+', '')
    $text = [regex]::Replace($text, '(?m)^>\s?', '')
    $text = [regex]::Replace($text, '(?m)^(\s*)[-*+] ', ('$1' + [char]0x2022 + ' '))
    $text = [regex]::Replace($text, '\[([^\]]+)\]\(([^)]+)\)', '$1 ($2)')
    $text = [regex]::Replace($text, '`([^`]+)`', '$1')
    $text = [regex]::Replace($text, '\*\*([^*]+)\*\*', '$1')
    [IO.File]::WriteAllText((Join-Path $projectRoot 'work/build-main/THIRD_PARTY_NOTICES.txt'), $text, [Text.UTF8Encoding]::new($false))
}

switch ($env:TEMPLATE_BUILD_ACTION) {
    'cleanup' { Clear-Build }
    'dependencies' { Prepare-Dependencies }
    'licenses' { Verify-Licenses }
    'notices' { Prepare-Notices }
    default { throw 'Unknown internal build action' }
}
