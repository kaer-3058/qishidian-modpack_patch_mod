# Verify the built aircraftdupefix jar.
#
# The realistic failure mode for this mod is NOT a crash, it is silently doing nothing:
#   - a handler method without @SubscribeEvent is never called
#   - @EventBusSubscriber(modid=...) that does not match the toml modId is never scanned
#   - a hard reference to immersive_aircraft would break loading when that mod updates
# So those are exactly what this script checks, against the built jar.
#
# ASCII only on purpose (Windows PowerShell 5.1 script encoding).

$ErrorActionPreference = 'Stop'

$proj = $PSScriptRoot
$root = (Resolve-Path (Join-Path $proj '..\..\..')).Path
$lib  = Join-Path $root '.minecraft\libraries'
$jdk  = 'C:\Users\kaer1\AppData\Roaming\.minecraft\runtime\java-runtime-delta\bin'

$jar       = Join-Path $proj 'artifacts\aircraftdupefix-1.0.0.jar'
$classFile = Join-Path $proj 'build\classes\com\aircraftdupefix\AircraftDupeFix.class'
$toml      = Join-Path $proj 'resources\META-INF\neoforge.mods.toml'

if (-not (Test-Path $jar))       { throw "jar not built yet: $jar" }
if (-not (Test-Path $classFile)) { throw "class not compiled yet: $classFile" }

$failures = New-Object System.Collections.Generic.List[string]
function Check([string]$name, [bool]$ok, [string]$detail) {
  if ($ok) { Write-Host ("  PASS  " + $name) }
  else { Write-Host ("  FAIL  " + $name + "  -> " + $detail); $failures.Add($name) }
}

# ---------------------------------------------------------------- classpath for javap
$cpParts = @(
  $jar
  (Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-client.jar')
  (Join-Path $lib 'net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar')
  (Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-universal.jar')
  (Join-Path $lib 'net\neoforged\bus\8.0.5\bus-8.0.5.jar')
  (Join-Path $lib 'net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar')
)
$javap = Join-Path $jdk 'javap.exe'
$cp = ($cpParts -join ';')

$verbose = (& $javap -v -p -cp $cp 'com.aircraftdupefix.AircraftDupeFix' 2>&1) -join "`n"
$sig     = (& $javap -s -p -cp $cp 'com.aircraftdupefix.AircraftDupeFix' 2>&1) -join "`n"

# ---------------------------------------------------------------- 1. jar structure
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
$entries = @($zip.Entries | ForEach-Object { $_.FullName })
$zip.Dispose()

Write-Host '[jar]'
Check 'contains com/aircraftdupefix/AircraftDupeFix.class' ($entries -contains 'com/aircraftdupefix/AircraftDupeFix.class') ('entries: ' + ($entries -join ', '))
Check 'contains META-INF/neoforge.mods.toml' ($entries -contains 'META-INF/neoforge.mods.toml') 'missing from jar'
Check 'no backslash entry names' (@($entries | Where-Object { $_.Contains('\') }).Count -eq 0) 'zip uses backslash separators'

# ---------------------------------------------------------------- 2. toml
$tomlBytes = [System.IO.File]::ReadAllBytes($toml)
$hasBom = ($tomlBytes.Length -ge 3 -and $tomlBytes[0] -eq 0xEF -and $tomlBytes[1] -eq 0xBB -and $tomlBytes[2] -eq 0xBF)
$tomlText = [System.IO.File]::ReadAllText($toml)
$tomlModId = ([regex]::Match($tomlText, '(?m)^\s*modId\s*=\s*"([^"]+)"')).Groups[1].Value
$tomlVersion = ([regex]::Match($tomlText, '(?m)^\s*version\s*=\s*"([^"]+)"')).Groups[1].Value

Write-Host '[mods.toml]'
Check 'no UTF-8 BOM' (-not $hasBom) 'BOM present, FML toml parser may reject it'
Check 'modId is aircraftdupefix' ($tomlModId -eq 'aircraftdupefix') ('found: ' + $tomlModId)
Check 'version present' ($tomlVersion -ne '') ('found: ' + $tomlVersion)

# ---------------------------------------------------------------- 3. class file version
$cls = [System.IO.File]::ReadAllBytes($classFile)
$major = $cls[6] * 256 + $cls[7]
Write-Host '[class file]'
Check 'class file major version 65 (Java 21)' ($major -eq 65) ('major = ' + $major)

# ---------------------------------------------------------------- 4. annotations
# javap indents a RuntimeVisibleAnnotations block deeper than its owner, and prints the
# annotation type name on the line after the "0: #n(...)" index line. So a block runs
# until a non-blank line dedents back to the owner's indentation or shallower.
$vLines = $verbose -split "`n"
$blocks = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt $vLines.Count; $i++) {
  $line = $vLines[$i]
  if ($line.Trim() -ne 'RuntimeVisibleAnnotations:') { continue }
  $indent = $line.Length - $line.TrimStart().Length
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.AppendLine($line)
  for ($j = $i + 1; $j -lt $vLines.Count; $j++) {
    $next = $vLines[$j]
    if ($next.Trim().Length -eq 0) { continue }
    $nextIndent = $next.Length - $next.TrimStart().Length
    if ($nextIndent -le $indent) { break }
    [void]$sb.AppendLine($next)
  }
  $blocks.Add($sb.ToString())
}
$subBlocks = @($blocks | Where-Object { $_ -match 'SubscribeEvent' })
$busBlocks = @($blocks | Where-Object { $_ -match 'EventBusSubscriber' })

Write-Host '[annotations]'
Check 'class carries @EventBusSubscriber' ($busBlocks.Count -eq 1) ('EventBusSubscriber blocks: ' + $busBlocks.Count + ' of ' + $blocks.Count)
Check 'exactly 2 methods carry @SubscribeEvent' ($subBlocks.Count -eq 2) ('SubscribeEvent blocks: ' + $subBlocks.Count)
Check 'class-level modid matches toml modId' ($busBlocks.Count -eq 1 -and $busBlocks[0].Contains('modid="' + $tomlModId + '"')) 'modid in the annotation differs from the toml modId'

# ---------------------------------------------------------------- 5. handler descriptors
Write-Host '[handlers]'
$wantLogout = '(Lnet/neoforged/neoforge/event/entity/player/PlayerEvent$PlayerLoggedOutEvent;)V'
$wantLogin  = '(Lnet/neoforged/neoforge/event/entity/player/PlayerEvent$PlayerLoggedInEvent;)V'
Check 'onPlayerLoggedOut descriptor' ($sig.Contains('onPlayerLoggedOut') -and $sig.Contains($wantLogout)) 'handler missing or wrong descriptor'
Check 'onPlayerLoggedIn descriptor'  ($sig.Contains('onPlayerLoggedIn')  -and $sig.Contains($wantLogin))  'handler missing or wrong descriptor'

# ---------------------------------------------------------------- 6. no hard dependency
Write-Host '[dependency isolation]'
Check 'no class ref to immersive_aircraft/ (slash form)' (-not $verbose.Contains('immersive_aircraft/')) 'compiled-in hard dependency found'
Check 'package filter string present (dot form)' ($verbose.Contains('immersive_aircraft.')) 'package filter constant missing'

# ---------------------------------------------------------------- 7. registration surface
# This is what decides whether a client has to install the mod at all. NeoForge 21.1
# validates a connection by comparing network channels / extensible enums / feature flags
# (the failure payload carries ResourceLocation keys, and the mismatch screen is only used
# from the configuration-phase connection listener) -- NOT by comparing the mod list. So a
# mod that registers nothing needs no client-side copy. These checks pin that down.
$registrationSurface = @(
  'PayloadRegistrar'
  'CustomPacketPayload'
  'DeferredRegister'
  'RegisterEvent'
  'IExtensibleEnum'
  'FeatureFlag'
  'net/neoforged/fml/common/Mod;'
)
Write-Host '[registration surface]'
foreach ($symbol in $registrationSurface) {
  Check ('registers nothing via ' + $symbol) (-not $verbose.Contains($symbol)) 'mod would need a matching client-side registration'
}

# ---------------------------------------------------------------- result
Write-Host ''
if ($failures.Count -gt 0) {
  Write-Host ("FAILED: " + $failures.Count + " check(s)")
  exit 1
}
Write-Host 'ALL CHECKS PASSED'
