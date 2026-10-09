# Build entitysectionfix.
#
# Compiles against the REAL Minecraft (srg client jar) / NeoForge / Mixin jars instead of
# hand-written stubs, so javac checks every signature and descriptor for us.
#
# ASCII only on purpose: Windows PowerShell 5.1 mis-decodes non-BOM UTF-8 script files.
# The workspace path contains non-ASCII characters, so everything is derived from $PSScriptRoot.
#
# Target of this fix is a VANILLA class (net.minecraft.world.level.entity.EntitySection).
# NeoForge 1.21.1 runs with official (mojmap) names, which is what the srg client jar in
# .minecraft\libraries is named with, so the descriptor below is what the runtime sees.

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

$mcJar = Find-Jar (Join-Path $lib 'net\minecraft\client') 'client-1.21.1-*-srg.jar'

$jars = [ordered]@{
  neoforgeClient = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-client.jar'
  neoforgeUni    = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-universal.jar'
  minecraftSrg   = $mcJar
  bus            = Join-Path $lib 'net\neoforged\bus\8.0.5\bus-8.0.5.jar'
  fmlLoader      = Join-Path $lib 'net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar'
  spongeMixin    = Find-Jar (Join-Path $lib 'net\fabricmc') 'sponge-mixin-*.jar'
  slf4jApi       = Find-Jar (Join-Path $lib 'org\slf4j') 'slf4j-api-*.jar'
  distmarkerApi  = Find-Jar (Join-Path $lib 'net\neoforged') 'mergetool-*-api.jar'
}
foreach ($key in $jars.Keys) {
  if (-not (Test-Path -LiteralPath $jars[$key])) { throw ("missing jar [" + $key + "]: " + $jars[$key]) }
}
$classpath = ($jars.Values -join ';')

# --- preflight: the injection point must exist in the installed Minecraft -------------
$probe = Join-Path $proj 'build\probe'
if (Test-Path $probe) { Remove-Item $probe -Recurse -Force }
New-Item -ItemType Directory -Path $probe -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($mcJar)
$e = $zip.Entries | Where-Object { $_.FullName -eq 'net/minecraft/world/level/entity/EntitySection.class' }
if (-not $e) { $zip.Dispose(); throw 'EntitySection.class missing from the Minecraft jar' }
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $probe 'EntitySection.class'), $true)
$zip.Dispose()

$sectionDump = (& $javap -p -s (Join-Path $probe 'EntitySection.class') 2>&1) -join "`n"
if ($sectionDump -notmatch 'public java\.util\.stream\.Stream<T> getEntities\(\);') {
  throw 'EntitySection.getEntities() with no arguments not found - wrong Minecraft version?'
}
if ($sectionDump -notmatch 'descriptor: \(\)Ljava/util/stream/Stream;') {
  throw 'EntitySection.getEntities() descriptor ()Ljava/util/stream/Stream; not found'
}
Write-Host 'preflight ok: EntitySection.getEntities() -> ()Ljava/util/stream/Stream; exists'

# --- compile --------------------------------------------------------------------------
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

$out = Join-Path $artifacts 'entitysectionfix-1.0.0.jar'
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
    'com/entitysectionfix/EntitySectionFix.class',
    'com/entitysectionfix/mixin/EntitySectionMixin.class',
    'entitysectionfix.mixins.json',
    'META-INF/neoforge.mods.toml')) {
  if ($entries -notcontains $needed) { throw ('compiled output missing from jar: ' + $needed) }
}
# Minecraft classes must never be bundled.
foreach ($e2 in $entries) {
  if ($e2 -like 'net/minecraft/*') { throw ('Minecraft class bundled into the fix jar: ' + $e2) }
}

# --- postflight: the mixin must carry the injected handler and the right target -------
$check = Join-Path $proj 'build\check'
if (Test-Path $check) { Remove-Item $check -Recurse -Force }
New-Item -ItemType Directory -Path $check -Force | Out-Null
$zip = [System.IO.Compression.ZipFile]::OpenRead($out)
$e3 = $zip.Entries | Where-Object { $_.FullName -eq 'com/entitysectionfix/mixin/EntitySectionMixin.class' }
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($e3, (Join-Path $check 'EntitySectionMixin.class'), $true)
$zip.Dispose()

$mixinDump = (& $javap -v -p (Join-Path $check 'EntitySectionMixin.class') 2>&1) -join "`n"
if ($mixinDump -notmatch 'entitysectionfix\$snapshot') {
  throw 'EntitySectionMixin is missing its injected handler method'
}
if ($mixinDump -notmatch 'getEntities\(\)Ljava/util/stream/Stream;') {
  throw 'EntitySectionMixin does not target getEntities()Ljava/util/stream/Stream;'
}
if ($mixinDump -notmatch 'Lnet/minecraft/world/level/entity/EntitySection;') {
  throw 'EntitySectionMixin does not target net.minecraft.world.level.entity.EntitySection'
}
Write-Host 'postflight ok: mixin targets EntitySection.getEntities() and carries the handler'

$meta = @()
$meta += ('built     : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$meta += ('javac     : ' + ((& $javac -version 2>&1) -join ' '))
$meta += 'classpath : real runtime jars, no hand-written stubs'
foreach ($key in $jars.Keys) { $meta += ('  ' + $key.PadRight(16) + $jars[$key]) }
$meta += ('jar       : ' + $out)
$meta | Set-Content -Path (Join-Path $proj 'build-meta.txt') -Encoding UTF8

Write-Host ''
Write-Host ("jar: {0} bytes" -f (Get-Item $out).Length)
foreach ($e4 in $entries) { Write-Host ('  ' + $e4) }
Write-Host ''
Write-Host 'OK'
