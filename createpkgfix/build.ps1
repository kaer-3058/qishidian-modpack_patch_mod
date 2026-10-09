# Build createpkgfix.
#
# Compiles against the REAL Create / Minecraft (srg client jar) / NeoForge / Mixin /
# MixinExtras jars instead of hand-written stubs, so javac checks every signature for us.
#
# ASCII only on purpose: Windows PowerShell 5.1 mis-decodes non-BOM UTF-8 script files.
# The workspace path contains non-ASCII characters, so everything is derived from $PSScriptRoot.
#
# Target is a MOD class (com.simibubi.create.content.logistics.box.PackageEntity).
# NeoForge 1.21.1 runs with official (mojmap) names and mod jars ship in that naming,
# so no refmap is needed.

$ErrorActionPreference = 'Stop'

$proj = $PSScriptRoot
$root = (Resolve-Path (Join-Path $proj '..\..\..')).Path
$lib  = Join-Path $root '.minecraft\libraries'
$mods = Join-Path $root '.minecraft\versions\起始点\mods'

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

# Create ships under a non-ASCII file name in this pack ("[机械动力] create-1.21.1-...jar"),
# and -Filter is prefix-anchored, so match with a regex instead.
$createJar = (Get-ChildItem -LiteralPath $mods -File |
    Where-Object { $_.Name -match '(?i)(^|[^a-z])create-1\.21\.1-[^\\/]*\.jar$' } |
    Select-Object -First 1)
if (-not $createJar) { throw 'the Create jar was not found in the mods folder' }
$createJar = $createJar.FullName
Write-Host ('create jar: ' + (Split-Path -Leaf $createJar))

$jars = [ordered]@{
  neoforgeClient = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-client.jar'
  neoforgeUni    = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-universal.jar'
  minecraftSrg   = Find-Jar (Join-Path $lib 'net\minecraft\client') 'client-1.21.1-*-srg.jar'
  create         = $createJar
  bus            = Join-Path $lib 'net\neoforged\bus\8.0.5\bus-8.0.5.jar'
  fmlLoader      = Join-Path $lib 'net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar'
  spongeMixin    = Find-Jar (Join-Path $lib 'net\fabricmc') 'sponge-mixin-*.jar'
  mixinExtras    = Find-Jar (Join-Path $proj 'lib') 'mixinextras-neoforge-*.jar'
  slf4jApi       = Find-Jar (Join-Path $lib 'org\slf4j') 'slf4j-api-*.jar'
}
foreach ($key in $jars.Keys) {
  if (-not (Test-Path -LiteralPath $jars[$key])) { throw ("missing jar [" + $key + "]: " + $jars[$key]) }
}
$classpath = ($jars.Values -join ';')

# --- preflight: the target method (and the offending hook call) must exist -------------
$probe = Join-Path $proj 'build\probe'
if (Test-Path $probe) { Remove-Item $probe -Recurse -Force }
New-Item -ItemType Directory -Path $probe -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($createJar)
$e = $zip.Entries | Where-Object { $_.FullName -eq 'com/simibubi/create/content/logistics/box/PackageEntity.class' }
if (-not $e) { $zip.Dispose(); throw 'PackageEntity.class missing from the Create jar' }
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $probe 'PackageEntity.class'), $true)
$zip.Dispose()

$dump = (& $javap -p -s -c (Join-Path $probe 'PackageEntity.class') 2>&1) -join "`n"
if ($dump -notmatch 'public boolean hurt\(net\.minecraft\.world\.damagesource\.DamageSource, float\);') {
  throw 'PackageEntity.hurt(DamageSource,float) not found - Create version changed?'
}
if ($dump -notmatch 'descriptor: \(Lnet/minecraft/world/damagesource/DamageSource;F\)Z') {
  throw 'PackageEntity.hurt descriptor (Lnet/minecraft/world/damagesource/DamageSource;F)Z not found'
}
if ($dump -notmatch 'CommonHooks\.onPlayerAttackTarget') {
  Write-Warning 'PackageEntity.hurt no longer calls CommonHooks.onPlayerAttackTarget - Create may have fixed this itself; the guard is then unnecessary (but harmless).'
}
Write-Host 'preflight ok: PackageEntity.hurt(DamageSource,float)Z exists and still calls the attack hook'

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

$out = Join-Path $artifacts 'createpkgfix-1.0.0.jar'
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
    'com/createpkgfix/CreatePkgFix.class',
    'com/createpkgfix/mixin/PackageEntityMixin.class',
    'createpkgfix.mixins.json',
    'META-INF/neoforge.mods.toml')) {
  if ($entries -notcontains $needed) { throw ('compiled output missing from jar: ' + $needed) }
}
foreach ($e2 in $entries) {
  if ($e2 -like 'net/minecraft/*') { throw ('Minecraft class bundled into the fix jar: ' + $e2) }
  if ($e2 -like 'com/simibubi/*') { throw ('Create class bundled into the fix jar: ' + $e2) }
}

# --- postflight: the mixin must carry the wrapper and the right target -----------------
$check = Join-Path $proj 'build\check'
if (Test-Path $check) { Remove-Item $check -Recurse -Force }
New-Item -ItemType Directory -Path $check -Force | Out-Null
$zip = [System.IO.Compression.ZipFile]::OpenRead($out)
$e3 = $zip.Entries | Where-Object { $_.FullName -eq 'com/createpkgfix/mixin/PackageEntityMixin.class' }
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($e3, (Join-Path $check 'PackageEntityMixin.class'), $true)
$zip.Dispose()

$mixinDump = (& $javap -v -p (Join-Path $check 'PackageEntityMixin.class') 2>&1) -join "`n"
if ($mixinDump -notmatch 'createpkgfix\$guardReentry') {
  throw 'PackageEntityMixin is missing its wrapper handler method'
}
if ($mixinDump -notmatch 'hurt\(Lnet/minecraft/world/damagesource/DamageSource;F\)Z') {
  throw 'PackageEntityMixin does not wrap hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z'
}
if ($mixinDump -notmatch 'Lcom/simibubi/create/content/logistics/box/PackageEntity;') {
  throw 'PackageEntityMixin does not target com.simibubi.create.content.logistics.box.PackageEntity'
}
if ($mixinDump -notmatch 'WrapMethod') {
  throw 'PackageEntityMixin is missing the @WrapMethod annotation'
}
if ($mixinDump -notmatch 'createpkgfix\$inHurt') {
  throw 'PackageEntityMixin is missing its @Unique guard field'
}
Write-Host 'postflight ok: mixin wraps PackageEntity.hurt with the re-entrancy guard'

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
