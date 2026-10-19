# Build sablefix 1.0.1 (two client-side patches for Sable).
#
# Compiles against the REAL Minecraft (srg client jar) / NeoForge / Mixin / Sable jars instead of
# hand-written stubs, so javac checks every signature and descriptor for us. The src\net and src\dev
# stub files that the 1.0.0 build used are NOT compiled any more (real jars replace them) and must
# never end up inside the jar.
#
# ASCII only on purpose: Windows PowerShell 5.1 mis-decodes non-BOM UTF-8 script files.
# The workspace path contains non-ASCII characters, so everything is derived from $PSScriptRoot.
#
# Target of the new mixin is a VANILLA class (net.minecraft.client.multiplayer.ClientPacketListener).
# NeoForge 1.21.1 runs with official (mojmap) names, which is what the srg client jar in
# .minecraft\libraries is named with, so the descriptors below are what the runtime sees.

$ErrorActionPreference = 'Stop'

$proj = $PSScriptRoot
$root = (Resolve-Path (Join-Path $proj '..\..\..')).Path
$lib  = Join-Path $root '.minecraft\libraries'

$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin'
if (-not (Test-Path (Join-Path $jdk 'javac.exe'))) {
  $jdk = 'C:\Users\kaer1\AppData\Roaming\.minecraft\runtime\java-runtime-delta\bin'
}
$javac  = Join-Path $jdk 'javac.exe'
$jarExe = Join-Path $jdk 'jar.exe'
$javap  = Join-Path $jdk 'javap.exe'
if (-not (Test-Path $javac)) { throw ("javac not found: " + $javac) }

function Find-Jar([string]$base, [string]$pattern) {
  $hit = Get-ChildItem -Path $base -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
  if (-not $hit) { throw ("missing jar: " + $pattern + " under " + $base) }
  return $hit.FullName
}

$mcJar = Find-Jar (Join-Path $lib 'net\minecraft\client') 'client-1.21.1-*-srg.jar'

# The Sable jar is only needed at compile time (SubLevel appears in a generic signature) and for the
# preflight that proves the buggy code is really there. Search the installed instances, not $lib, and
# take the highest version so the preflight checks the jar the player actually runs.
$sableCandidates = @(Get-ChildItem -Path (Join-Path $root '.minecraft\versions') -Recurse -Filter 'sable-neoforge-*.jar' -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notmatch 'sources|javadoc' })
if ($sableCandidates.Count -eq 0) { throw 'no sable-neoforge-*.jar found under .minecraft\versions' }
$sableJar = ($sableCandidates | Sort-Object {
    $m = [regex]::Match($_.Name, '(\d+\.\d+\.\d+)\.jar$')
    if ($m.Success) { [version]$m.Groups[1].Value } else { [version]'0.0.0' }
  } -Descending | Select-Object -First 1).FullName

$jars = [ordered]@{
  neoforgeClient = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-client.jar'
  neoforgeUni    = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-universal.jar'
  minecraftSrg   = $mcJar
  bus            = Join-Path $lib 'net\neoforged\bus\8.0.5\bus-8.0.5.jar'
  fmlLoader      = Join-Path $lib 'net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar'
  spongeMixin    = Find-Jar (Join-Path $lib 'net\fabricmc') 'sponge-mixin-*.jar'
  datafixer      = Find-Jar (Join-Path $lib 'com\mojang') 'datafixerupper-*.jar'
  slf4jApi       = Find-Jar (Join-Path $lib 'org\slf4j') 'slf4j-api-*.jar'
  distmarkerApi  = Find-Jar (Join-Path $lib 'net\neoforged') 'mergetool-*-api.jar'
  sable          = $sableJar
}
foreach ($key in $jars.Keys) {
  if (-not (Test-Path -LiteralPath $jars[$key])) { throw ("missing jar [" + $key + "]: " + $jars[$key]) }
}
$classpath = ($jars.Values -join ';')

function Get-Javap([string]$class) {
  # javap wraps long lines; strip all whitespace so descriptors can be matched as one token.
  $raw = (& $javap -p -s -cp $classpath $class 2>&1) -join "`n"
  return ($raw -replace '\s', '')
}

# --- preflight 1: the two vanilla methods this mod hangs on -------------------------------
$dropDescriptor = '(Lnet/minecraft/world/level/ChunkPos;)V'
$cpl = Get-Javap 'net.minecraft.client.multiplayer.ClientPacketListener'
if ($cpl -notmatch [regex]::Escape('handleForgetLevelChunk(net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket)')) {
  throw 'ClientPacketListener.handleForgetLevelChunk(ClientboundForgetLevelChunkPacket) not found - wrong Minecraft version?'
}
if ($cpl -notmatch [regex]::Escape('descriptor:(Lnet/minecraft/network/protocol/game/ClientboundForgetLevelChunkPacket;)V')) {
  throw 'ClientPacketListener.handleForgetLevelChunk has an unexpected descriptor'
}
$ccc = Get-Javap 'net.minecraft.client.multiplayer.ClientChunkCache'
if ($ccc -notmatch [regex]::Escape('voiddrop(net.minecraft.world.level.ChunkPos)')) {
  throw 'ClientChunkCache.drop(ChunkPos) not found - wrong Minecraft version?'
}
if ($ccc -notmatch [regex]::Escape('descriptor:' + $dropDescriptor)) {
  throw 'ClientChunkCache.drop has an unexpected descriptor'
}
Write-Host 'preflight ok: handleForgetLevelChunk(...) and ClientChunkCache.drop(ChunkPos) exist'

# --- preflight 2: handleForgetLevelChunk must call drop exactly once ----------------------
$cplCode = (& $javap -p -c -cp $classpath net.minecraft.client.multiplayer.ClientPacketListener 2>&1) -join "`n"
$body = [regex]::Match($cplCode, '(?s)public void handleForgetLevelChunk\(.*?\n(?=  \S)').Value
if (-not $body) { throw 'could not slice the handleForgetLevelChunk bytecode' }
$bodyFlat = $body -replace '\s', ''
$call = 'ClientChunkCache.drop:(Lnet/minecraft/world/level/ChunkPos;)V'
$callCount = ([regex]::Matches($bodyFlat, [regex]::Escape($call))).Count
if ($callCount -ne 1) {
  throw ('expected exactly 1 ClientChunkCache.drop call in handleForgetLevelChunk, found ' + $callCount + ' - the @Redirect would not apply')
}
Write-Host 'preflight ok: handleForgetLevelChunk calls ClientChunkCache.drop exactly once'

# --- preflight 3: the Sable side of the bug is really in the installed jar ----------------
$zip = [System.IO.Compression.ZipFile]::OpenRead($sableJar)
$sableEntries = @($zip.Entries | ForEach-Object { $_.FullName })
$plotMixinEntry = $zip.Entries | Where-Object { $_.FullName -eq 'dev/ryanhcode/sable/mixin/plot/ClientChunkCacheMixin.class' }
if (-not $plotMixinEntry) { $zip.Dispose(); throw 'Sable plot ClientChunkCacheMixin.class not found - Sable version changed?' }
$ms = New-Object System.IO.MemoryStream
$stream = $plotMixinEntry.Open()
$stream.CopyTo($ms)
$stream.Close()
$zip.Dispose()
$plotBytes = $ms.ToArray()
$plotText = [System.Text.Encoding]::ASCII.GetString($plotBytes)
if ($plotText -notmatch 'Cannot drop chunks in plot') {
  throw 'Sable plot mixin no longer contains "Cannot drop chunks in plot" - maybe Sable fixed it upstream; check before shipping'
}
foreach ($needed in @('dev/ryanhcode/sable/sublevel/SubLevel.class', 'dev/ryanhcode/sable/ActiveSableCompanion.class')) {
  if ($sableEntries -notcontains $needed) { throw ('Sable jar is missing: ' + $needed) }
}
Write-Host ('preflight ok: Sable plot mixin still throws "Cannot drop chunks in plot" (jar: ' + (Split-Path $sableJar -Leaf) + ')')

# --- compile ------------------------------------------------------------------------------
$classes   = Join-Path $proj 'build\classes'
$stage     = Join-Path $proj 'build\stage'
$artifacts = Join-Path $proj 'artifacts'
foreach ($dir in @($classes, $stage, $artifacts)) {
  if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
  New-Item -ItemType Directory -Path $dir -Force | Out-Null
}

# Only com\sablefix\** is compiled. src\net and src\dev are the old hand-written stubs.
$sources = @(Get-ChildItem -Path (Join-Path $proj 'src\com') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw 'no sources found under src\com\' }
foreach ($s in $sources) {
  if ($s -match '\\src\\(net|dev)\\') { throw ('stub source would be compiled: ' + $s) }
}

Write-Host ('javac: ' + (& $javac -version 2>&1))
Write-Host ("compiling {0} source file(s) against {1} real jar(s)" -f $sources.Count, $jars.Count)
& $javac -encoding UTF-8 --release 21 -proc:none -d $classes -cp $classpath @sources
if ($LASTEXITCODE -ne 0) { throw ("javac failed, exit code " + $LASTEXITCODE) }

Copy-Item (Join-Path $classes '*') $stage -Recurse -Force
Copy-Item (Join-Path $proj 'resources\*') $stage -Recurse -Force

$out = Join-Path $artifacts 'sablefix-1.0.1.jar'
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
    'com/sablefix/SableFix.class',
    'com/sablefix/mixin/ActiveSableCompanionMixin.class',
    'com/sablefix/mixin/ClientPacketListenerMixin.class',
    'sablefix.mixins.json',
    'META-INF/neoforge.mods.toml')) {
  if ($entries -notcontains $needed) { throw ('compiled output missing from jar: ' + $needed) }
}
# Neither Minecraft classes nor the hand-written stubs may be bundled.
foreach ($e in $entries) {
  if ($e -like 'net/minecraft/*') { throw ('Minecraft class bundled into the mod jar: ' + $e) }
  if ($e -like 'dev/ryanhcode/*') { throw ('Sable class bundled into the mod jar: ' + $e) }
  if ($e -like 'net/neoforged/*') { throw ('NeoForge class bundled into the mod jar: ' + $e) }
}

# --- postflight: the built mixin must carry the right handler, target and message ---------
$check = Join-Path $proj 'build\check'
if (Test-Path $check) { Remove-Item $check -Recurse -Force }
New-Item -ItemType Directory -Path $check -Force | Out-Null
$zip = [System.IO.Compression.ZipFile]::OpenRead($out)
$e3 = $zip.Entries | Where-Object { $_.FullName -eq 'com/sablefix/mixin/ClientPacketListenerMixin.class' }
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($e3, (Join-Path $check 'ClientPacketListenerMixin.class'), $true)
$zip.Dispose()

$mixinDump = ((& $javap -v -p (Join-Path $check 'ClientPacketListenerMixin.class') 2>&1) -join "`n") -replace '\s', ''
foreach ($need in @(
    'sablefix$dropChunkSafely',
    'Lorg/spongepowered/asm/mixin/Mixin;',
    'Lorg/spongepowered/asm/mixin/injection/Redirect;',
    'Lnet/minecraft/client/multiplayer/ClientPacketListener;',
    'Lnet/minecraft/client/multiplayer/ClientChunkCache;',
    'Lnet/minecraft/world/level/ChunkPos;',
    'handleForgetLevelChunk(Lnet/minecraft/network/protocol/game/ClientboundForgetLevelChunkPacket;)V',
    'Cannotdropchunksinplot')) {
  if ($mixinDump -notmatch [regex]::Escape($need)) { throw ('ClientPacketListenerMixin is missing: ' + $need) }
}
Write-Host 'postflight ok: mixin targets ClientPacketListener.handleForgetLevelChunk -> ClientChunkCache.drop(ChunkPos)'

# --- postflight: mixins.json declares the client-only mixin, toml is 1.0.1 + displayTest --
$mixinsJson = Get-Content -LiteralPath (Join-Path $proj 'resources\sablefix.mixins.json') -Raw -Encoding UTF8
if ($mixinsJson -notmatch '"client"\s*:\s*\[\s*"ClientPacketListenerMixin"\s*\]') {
  throw 'sablefix.mixins.json does not list ClientPacketListenerMixin under "client" (it must never load on a dedicated server)'
}
if ($mixinsJson -notmatch '"required"\s*:\s*false') {
  throw 'sablefix.mixins.json is not marked required=false (a future Sable change must not crash the game)'
}
Write-Host 'mixins.json ok: ClientPacketListenerMixin is client-only, required = false'

$toml = Get-Content -LiteralPath (Join-Path $proj 'resources\META-INF\neoforge.mods.toml') -Raw -Encoding UTF8
if ($toml -notmatch 'version\s*=\s*"1\.0\.1"') { throw 'mods.toml version is not 1.0.1' }
if ($toml -notmatch 'displayTest\s*=\s*"IGNORE_ALL_VERSION"') {
  throw 'mods.toml does not set displayTest = "IGNORE_ALL_VERSION" (a client-only patch must not break joining)'
}
Write-Host 'mods.toml ok: version = 1.0.1, displayTest = IGNORE_ALL_VERSION'

$meta = @()
$meta += ('built     : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$meta += ('javac     : ' + ((& $javac -version 2>&1) -join ' '))
$meta += 'classpath : real runtime jars, no hand-written stubs'
foreach ($key in $jars.Keys) { $meta += ('  ' + $key.PadRight(16) + $jars[$key]) }
$meta += ('hook      : ClientPacketListener.handleForgetLevelChunk -> ClientChunkCache.drop' + $dropDescriptor)
$meta += ('sable     : ' + $sableJar)
$meta += ('jar       : ' + $out)
$meta | Set-Content -Path (Join-Path $proj 'build-meta.txt') -Encoding UTF8

Write-Host ''
Write-Host ("jar: {0} bytes" -f (Get-Item $out).Length)
foreach ($e4 in $entries) { Write-Host ('  ' + $e4) }
Write-Host ''
Write-Host 'OK'
