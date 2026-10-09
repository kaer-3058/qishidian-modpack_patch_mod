# Build aircraftdupefix.
#
# Compiles against the REAL Minecraft / NeoForge runtime jars instead of hand-written
# stubs. That is deliberate: the previous hand-stub build shipped a class whose stub had
# the wrong shape (a class where the real type was an interface) and crashed the game with
# IncompatibleClassChangeError. Compiling against the real jars makes javac check every
# signature, descriptor and class/interface shape for us.
#
# ASCII only on purpose: Windows PowerShell 5.1 mis-decodes non-BOM UTF-8 script files.
# The workspace path contains non-ASCII characters, so it is derived from $PSScriptRoot
# rather than hardcoded.

$ErrorActionPreference = 'Stop'

$proj = $PSScriptRoot
$root = (Resolve-Path (Join-Path $proj '..\..\..')).Path
$lib  = Join-Path $root '.minecraft\libraries'
$jdk  = 'C:\Users\kaer1\AppData\Roaming\.minecraft\runtime\java-runtime-delta\bin'

$jars = [ordered]@{
  neoforgeClient = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-client.jar'
  minecraftSrg   = Join-Path $lib 'net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar'
  neoforgeUni    = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-universal.jar'
  bus            = Join-Path $lib 'net\neoforged\bus\8.0.5\bus-8.0.5.jar'
  fmlLoader      = Join-Path $lib 'net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar'
}
$distApi = Get-ChildItem -Path (Join-Path $lib 'net\neoforged') -Recurse -Filter 'mergetool-*-api.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($distApi) { $jars['distmarkerApi'] = $distApi.FullName }

foreach ($key in $jars.Keys) {
  if (-not (Test-Path $jars[$key])) { throw ("missing jar [$key]: " + $jars[$key]) }
}
$classpath = ($jars.Values -join ';')

$classes   = Join-Path $proj 'build\classes'
$stage     = Join-Path $proj 'build\stage'
$artifacts = Join-Path $proj 'artifacts'
foreach ($dir in @($classes, $stage, $artifacts)) {
  if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
  New-Item -ItemType Directory -Path $dir -Force | Out-Null
}

$javac = Join-Path $jdk 'javac.exe'
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }

$sources = @(Get-ChildItem -Path (Join-Path $proj 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw 'no sources found under src\' }

Write-Host ('javac: ' + (& $javac -version 2>&1))
Write-Host ("compiling {0} source file(s) against {1} real jar(s)" -f $sources.Count, $jars.Count)
& $javac -encoding UTF-8 --release 21 -Xlint:all -d $classes -cp $classpath @sources
if ($LASTEXITCODE -ne 0) { throw ("javac failed, exit code " + $LASTEXITCODE) }

Copy-Item (Join-Path $classes '*') $stage -Recurse -Force
Copy-Item (Join-Path $proj 'resources\*') $stage -Recurse -Force

$out = Join-Path $artifacts 'aircraftdupefix-1.0.0.jar'
if (Test-Path $out) { Remove-Item $out -Force }

$jarExe = Join-Path $jdk 'jar.exe'
if (Test-Path $jarExe) {
  & $jarExe --create --file $out -C $stage .
  if ($LASTEXITCODE -ne 0) { throw ("jar failed, exit code " + $LASTEXITCODE) }
} else {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $out)
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($out)
$entries = @($zip.Entries | ForEach-Object { $_.FullName })
$zip.Dispose()

$meta = @()
$meta += ('built     : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$meta += ('javac     : ' + ((& $javac -version 2>&1) -join ' '))
$meta += 'classpath : real runtime jars, no hand-written stubs'
foreach ($key in $jars.Keys) { $meta += ('  ' + $key.PadRight(16) + $jars[$key]) }
$meta += ('jar       : ' + $out)
$meta | Set-Content -Path (Join-Path $proj 'build-meta.txt') -Encoding UTF8

Write-Host ''
Write-Host ("jar: {0} bytes" -f (Get-Item $out).Length)
foreach ($e in $entries) { Write-Host ("  " + $e) }
Write-Host ''
if ($entries -notcontains 'com/aircraftdupefix/AircraftDupeFix.class') {
  throw 'compiled class missing from jar (check entry separators)'
}
if ($entries -notcontains 'META-INF/neoforge.mods.toml') {
  throw 'META-INF/neoforge.mods.toml missing from jar'
}
Write-Host 'OK'
