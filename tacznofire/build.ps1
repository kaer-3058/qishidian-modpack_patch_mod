# Build tacznofire.
#
# Compiles against the REAL Minecraft / NeoForge / TACZ runtime jars instead of hand-written
# stubs, so javac checks every signature, descriptor and class/interface shape for us --
# including TACZ's GunShootEvent and IGun, which this mod calls directly.
#
# ASCII only on purpose: Windows PowerShell 5.1 mis-decodes non-BOM UTF-8 script files.
# The workspace path contains non-ASCII characters, so it is derived from $PSScriptRoot
# rather than hardcoded.

$ErrorActionPreference = 'Stop'

$proj = $PSScriptRoot
$root = (Resolve-Path (Join-Path $proj '..\..\..')).Path
$lib  = Join-Path $root '.minecraft\libraries'
$versions = Join-Path $root '.minecraft\versions'

# JDK: first candidate that actually has javac.exe wins.
$jdkCandidates = @(
  'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin',
  (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta\bin'),
  'C:\Program Files\Java\jdk-21\bin'
)
$jdk = $null
foreach ($candidate in $jdkCandidates) {
  if ($candidate -and (Test-Path (Join-Path $candidate 'javac.exe'))) { $jdk = $candidate; break }
}
if (-not $jdk) { throw 'no JDK with javac.exe found in the candidate paths' }

# TACZ ships as tacz-neoforge-1.21.1-<version>.jar -- searched for under every version dir,
# so the script stays ASCII-only even though the pack directory name is not.
$tacz = Get-ChildItem -Path $versions -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ChildItem -Path (Join-Path $_.FullName 'mods') -Filter 'tacz-neoforge-*.jar' -File -ErrorAction SilentlyContinue } |
        Sort-Object Name -Descending | Select-Object -First 1
if (-not $tacz) { throw ("TACZ jar not found under " + $versions) }

$jars = [ordered]@{
  neoforgeClient = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-client.jar'
  minecraftSrg   = Join-Path $lib 'net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar'
  neoforgeUni    = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-universal.jar'
  bus            = Join-Path $lib 'net\neoforged\bus\8.0.5\bus-8.0.5.jar'
  fmlLoader      = Join-Path $lib 'net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar'
  tacz           = $tacz.FullName
}
$distApi = Get-ChildItem -Path (Join-Path $lib 'net\neoforged') -Recurse -Filter 'mergetool-*-api.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($distApi) { $jars['distmarkerApi'] = $distApi.FullName }
$slf4j = Get-ChildItem -Path (Join-Path $lib 'org\slf4j') -Recurse -Filter 'slf4j-api-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($slf4j) { $jars['slf4j'] = $slf4j.FullName }
# Component#getString() is inherited from com.mojang.brigadier.Message, so brigadier is needed.
$brigadier = Get-ChildItem -Path (Join-Path $lib 'com\mojang\brigadier') -Recurse -Filter 'brigadier-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($brigadier) { $jars['brigadier'] = $brigadier.FullName }

foreach ($key in $jars.Keys) {
  if (-not (Test-Path $jars[$key])) { throw ("missing jar [" + $key + "]: " + $jars[$key]) }
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
$sources = @(Get-ChildItem -Path (Join-Path $proj 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw 'no sources found under src\' }

Write-Host ('javac: ' + (& $javac -version 2>&1))
Write-Host ("compiling " + $sources.Count + " source file(s) against " + $jars.Count + " real jar(s)")
& $javac -encoding UTF-8 --release 21 -Xlint:all -d $classes -cp $classpath @sources
if ($LASTEXITCODE -ne 0) { throw ("javac failed, exit code " + $LASTEXITCODE) }

Copy-Item (Join-Path $classes '*') $stage -Recurse -Force
Copy-Item (Join-Path $proj 'resources\*') $stage -Recurse -Force

$out = Join-Path $artifacts 'tacznofire-1.0.0.jar'
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
Write-Host ("jar: " + (Get-Item $out).Length + " bytes")
foreach ($e in $entries) { Write-Host ("  " + $e) }
Write-Host ''
if ($entries -notcontains 'com/tacznofire/TaczNoFire.class') {
  throw 'compiled class missing from jar (check entry separators)'
}
if ($entries -notcontains 'META-INF/neoforge.mods.toml') {
  throw 'META-INF/neoforge.mods.toml missing from jar'
}
Write-Host 'OK'
