# Build voidscapefix.
#
# Compiles against the REAL Voidscape / NeoForge / Mixin runtime jars instead of
# hand-written stubs, so javac checks every signature and descriptor for us.
#
# ASCII only on purpose: Windows PowerShell 5.1 mis-decodes non-BOM UTF-8 script files.
# The workspace path contains non-ASCII characters, so everything is derived from
# $PSScriptRoot, and the Voidscape jar is located by its CONTENT (it ships as a
# renamed jar whose file name contains non-ASCII characters: "[虚空工艺] Voidscape-....jar").

$ErrorActionPreference = 'Stop'

$proj = $PSScriptRoot
$root = (Resolve-Path (Join-Path $proj '..\..\..')).Path
$lib  = Join-Path $root '.minecraft\libraries'

$jdk = 'C:\Users\kaer1\AppData\Roaming\.minecraft\runtime\java-runtime-delta\bin'
if (-not (Test-Path (Join-Path $jdk 'javac.exe'))) {
  $jdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin'
}
$javac  = Join-Path $jdk 'javac.exe'
$jarExe = Join-Path $jdk 'jar.exe'
$javap  = Join-Path $jdk 'javap.exe'
if (-not (Test-Path $javac)) { throw ("javac not found: " + $javac) }

function Find-Jar([string]$base, [string]$pattern) {
  $hit = Get-ChildItem -Path $base -Recurse -Filter $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $hit) { throw ("missing jar: " + $pattern + " under " + $base) }
  return $hit.FullName
}

# --- locate the Voidscape jar by content --------------------------------------
$vsTarget = 'tamaized/voidscape/network/DonatorHandler.class'
function Find-VoidscapeJar {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $versions = Join-Path $root '.minecraft\versions'
  foreach ($ver in (Get-ChildItem -Path $versions -Directory)) {
    $modsDir = Join-Path $ver.FullName 'mods'
    if (-not (Test-Path -LiteralPath $modsDir)) { continue }
    foreach ($cand in (Get-ChildItem -LiteralPath $modsDir -Filter '*.jar' -File)) {
      try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($cand.FullName)
        $found = $false
        foreach ($e in $zip.Entries) { if ($e.FullName -eq $vsTarget) { $found = $true; break } }
        $zip.Dispose()
        if ($found) { return $cand.FullName }
      } catch { continue }
    }
  }
  throw 'Voidscape jar not found (searched .minecraft\versions\*\mods\*.jar)'
}
$vsJar = Find-VoidscapeJar

$jars = [ordered]@{
  neoforgeClient = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-client.jar'
  neoforgeUni    = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-universal.jar'
  minecraftSrg   = Join-Path $lib 'net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar'
  bus            = Join-Path $lib 'net\neoforged\bus\8.0.5\bus-8.0.5.jar'
  fmlLoader      = Join-Path $lib 'net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar'
  spongeMixin    = Find-Jar (Join-Path $lib 'net\fabricmc') 'sponge-mixin-*.jar'
  slf4jApi       = Find-Jar (Join-Path $lib 'org\slf4j') 'slf4j-api-*.jar'
  distmarkerApi  = Find-Jar (Join-Path $lib 'net\neoforged') 'mergetool-*-api.jar'
  voidscape      = $vsJar
}
foreach ($key in $jars.Keys) {
  if (-not (Test-Path -LiteralPath $jars[$key])) { throw ("missing jar [" + $key + "]: " + $jars[$key]) }
}
$classpath = ($jars.Values -join ';')

# --- preflight: the injection points must exist in the installed Voidscape -----
$probe = Join-Path $proj 'build\probe'
if (Test-Path $probe) { Remove-Item $probe -Recurse -Force }
New-Item -ItemType Directory -Path $probe -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($vsJar)
foreach ($n in @($vsTarget, 'tamaized/voidscape/network/ModUpdateURLInterceptor.class')) {
  $e = $zip.Entries | Where-Object { $_.FullName -eq $n }
  if (-not $e) { $zip.Dispose(); throw ("class missing from Voidscape jar: " + $n) }
  $leaf = ($n -split '/')[-1]
  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $probe $leaf), $true)
}
$zip.Dispose()

$donatorDump = (& $javap -p -c (Join-Path $probe 'DonatorHandler.class') 2>&1) -join "`n"
if ($donatorDump -notmatch 'private java\.util\.List<java\.util\.UUID> run\(\);') {
  throw 'DonatorHandler.run() not found - wrong Voidscape version?'
}
if ($donatorDump -notmatch 'public java\.util\.List<java\.util\.UUID> getDonators\(\);') {
  throw 'DonatorHandler.getDonators() not found - wrong Voidscape version?'
}
if ($donatorDump -notmatch 'CompletableFuture\.join') {
  throw 'CompletableFuture.join() not found in DonatorHandler - wrong Voidscape version?'
}
Write-Host 'preflight ok: DonatorHandler declares run(), getDonators() and uses CompletableFuture.join()'

$interceptorDump = (& $javap -p (Join-Path $probe 'ModUpdateURLInterceptor.class') 2>&1) -join "`n"
if ($interceptorDump -notmatch 'public tamaized\.voidscape\.network\.ModUpdateURLInterceptor\(\);') {
  throw 'ModUpdateURLInterceptor() constructor not found - wrong Voidscape version?'
}
Write-Host 'preflight ok: ModUpdateURLInterceptor declares a no-arg constructor'

# --- compile -----------------------------------------------------------------
$classes   = Join-Path $proj 'build\classes'
$stage     = Join-Path $proj 'build\stage'
$artifacts = Join-Path $proj 'artifacts'
foreach ($dir in @($classes, $stage, $artifacts)) {
  if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
  New-Item -ItemType Directory -Path $dir -Force | Out-Null
}

$sources = @(Get-ChildItem -Path (Join-Path $proj 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw 'no sources found under src\' }

Write-Host ('javac: ' + (& $javac -version 2>&1))
Write-Host ("compiling {0} source file(s) against {1} real jar(s)" -f $sources.Count, $jars.Count)
& $javac -encoding UTF-8 --release 21 -proc:none -d $classes -cp $classpath @sources
if ($LASTEXITCODE -ne 0) { throw ("javac failed, exit code " + $LASTEXITCODE) }

Copy-Item (Join-Path $classes '*') $stage -Recurse -Force
Copy-Item (Join-Path $proj 'resources\*') $stage -Recurse -Force

$out = Join-Path $artifacts 'voidscapefix-1.0.0.jar'
if (Test-Path $out) { Remove-Item $out -Force }
if (Test-Path $jarExe) {
  & $jarExe --create --file $out -C $stage .
  if ($LASTEXITCODE -ne 0) { throw ("jar failed, exit code " + $LASTEXITCODE) }
} else {
  [System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $out)
}

$zip = [System.IO.Compression.ZipFile]::OpenRead($out)
$entries = @($zip.Entries | ForEach-Object { $_.FullName })
$zip.Dispose()

foreach ($needed in @(
    'com/voidscapefix/VoidscapeFix.class',
    'com/voidscapefix/mixin/DonatorHandlerMixin.class',
    'com/voidscapefix/mixin/ModUpdateURLInterceptorMixin.class',
    'voidscapefix.mixins.json',
    'META-INF/neoforge.mods.toml')) {
  if ($entries -notcontains $needed) { throw ('compiled output missing from jar: ' + $needed) }
}
# Voidscape classes must never be bundled.
foreach ($e in $entries) {
  if ($e -like 'tamaized/*') { throw ('Voidscape class bundled into the fix jar: ' + $e) }
}

# --- postflight: verify the injected classes carry our handler methods --------
$check = Join-Path $proj 'build\check'
if (Test-Path $check) { Remove-Item $check -Recurse -Force }
New-Item -ItemType Directory -Path $check -Force | Out-Null
$zip = [System.IO.Compression.ZipFile]::OpenRead($out)
foreach ($n in @('com/voidscapefix/mixin/DonatorHandlerMixin.class', 'com/voidscapefix/mixin/ModUpdateURLInterceptorMixin.class')) {
  $e = $zip.Entries | Where-Object { $_.FullName -eq $n }
  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $check (($n -split '/')[-1])), $true)
}
$zip.Dispose()
$mixin1 = (& $javap -p (Join-Path $check 'DonatorHandlerMixin.class') 2>&1) -join "`n"
if ($mixin1 -notmatch 'voidscapefix\$blockDonatorRequest' -or $mixin1 -notmatch 'voidscapefix\$neverJoin') {
  throw 'DonatorHandlerMixin is missing its injected handler methods'
}
Write-Host 'postflight ok: DonatorHandlerMixin carries both injected handlers'

$meta = @()
$meta += ('built     : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$meta += ('javac     : ' + ((& $javac -version 2>&1) -join ' '))
$meta += 'classpath : real runtime jars, no hand-written stubs'
foreach ($key in $jars.Keys) { $meta += ('  ' + $key.PadRight(16) + $jars[$key]) }
$meta += ('jar       : ' + $out)
$meta | Set-Content -Path (Join-Path $proj 'build-meta.txt') -Encoding UTF8

Write-Host ''
Write-Host ("jar: {0} bytes" -f (Get-Item $out).Length)
foreach ($e in $entries) { Write-Host ('  ' + $e) }
Write-Host ''
Write-Host 'OK'
