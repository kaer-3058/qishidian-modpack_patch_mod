# Static + functional verification of the built tacznofire jar.
#
# Proves, without launching Minecraft, the three things that decide whether the mod works:
#   1. the jar is a well-formed NeoForge mod (entries, modId, class file version);
#   2. TACZ really does respect cancellation of GunShootEvent -- checked in TACZ's own
#      bytecode, on both the server path (LivingEntityShoot) and the client path
#      (LocalPlayerShoot). If that ever changed, the mod would silently do nothing;
#   3. the shipped config semantics are what the README claims -- the rule engine is
#      executed for real against a table of inputs.
#
# ASCII only on purpose (Windows PowerShell 5.1 mis-decodes non-BOM UTF-8 script files).

$ErrorActionPreference = 'Stop'

$proj = $PSScriptRoot
$root = (Resolve-Path (Join-Path $proj '..\..\..')).Path
$lib  = Join-Path $root '.minecraft\libraries'
$versions = Join-Path $root '.minecraft\versions'

$jdkCandidates = @(
  'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin',
  (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta\bin'),
  'C:\Program Files\Java\jdk-21\bin'
)
$jdk = $null
foreach ($candidate in $jdkCandidates) {
  if ($candidate -and (Test-Path (Join-Path $candidate 'javac.exe'))) { $jdk = $candidate; break }
}
if (-not $jdk) { throw 'no JDK with javac.exe found' }

$javac = Join-Path $jdk 'javac.exe'
$javap = Join-Path $jdk 'javap.exe'
$java  = Join-Path $jdk 'java.exe'

$jar  = Join-Path $proj 'artifacts\tacznofire-1.0.0.jar'
$tacz = Get-ChildItem -Path $versions -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ChildItem -Path (Join-Path $_.FullName 'mods') -Filter 'tacz-neoforge-*.jar' -File -ErrorAction SilentlyContinue } |
        Sort-Object Name -Descending | Select-Object -First 1
if (-not $tacz) { throw 'TACZ jar not found' }
if (-not (Test-Path $jar)) { throw ('built jar not found: ' + $jar + ' (run build.ps1 first)') }

$mcJar = Join-Path $lib 'net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar'
function FindLib([string]$pattern) {
  $found = Get-ChildItem -Path $lib -Recurse -Filter $pattern -File -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object Name -Descending | Select-Object -First 1
  if ($found) { return $found.FullName }
  return $null
}

$libJars = @(
  (Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-client.jar'),
  (Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-universal.jar'),
  (Join-Path $lib 'net\neoforged\bus\8.0.5\bus-8.0.5.jar'),
  (Join-Path $lib 'net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar'),
  $mcJar,
  $tacz.FullName
)
# Minecraft's own dependencies: needed because the rule-engine test really loads
# net.minecraft.resources.ResourceLocation (its <clinit> builds a Codec / StreamCodec).
foreach ($pattern in @('brigadier-*.jar', 'slf4j-api-*.jar', 'datafixerupper-*.jar', 'guava-*.jar',
                       'failureaccess-*.jar', 'fastutil-*.jar', 'commons-lang3-*.jar', 'commons-io-*.jar',
                       'gson-*.jar', 'netty-buffer-*.jar', 'netty-common-*.jar', 'netty-codec-*.jar',
                       'authlib-*.jar', 'joml-*.jar')) {
  $found = FindLib $pattern
  if ($found) { $libJars += $found }
}
$classpath = ($libJars | Select-Object -Unique) -join ';'

$results = New-Object System.Collections.Generic.List[object]
function Check([string]$name, [bool]$ok, [string]$detail) {
  $results.Add([pscustomobject]@{ Name = $name; Ok = $ok })
  $tag = if ($ok) { 'PASS' } else { 'FAIL' }
  $suffix = ''
  if ($detail) { $suffix = '  (' + $detail + ')' }
  Write-Host ('  [' + $tag + '] ' + $name + $suffix)
}
function Section([string]$title) { Write-Host ''; Write-Host ('== ' + $title + ' ==') }

Write-Host ('jar  : ' + $jar)
Write-Host ('tacz : ' + $tacz.Name)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
  $entries = @($zip.Entries | ForEach-Object { $_.FullName })
  Section 'jar layout'
  Check 'com/tacznofire/TaczNoFire.class in jar' ($entries -contains 'com/tacznofire/TaczNoFire.class')
  Check 'META-INF/neoforge.mods.toml in jar' ($entries -contains 'META-INF/neoforge.mods.toml')
  Check 'no java sources / stray files shipped' (@($entries | Where-Object { $_ -like '*.java' }).Count -eq 0)

  Section 'class file version'
  $entry = $zip.GetEntry('com/tacznofire/TaczNoFire.class')
  $stream = $entry.Open()
  $head = New-Object byte[] 8
  $read = $stream.Read($head, 0, 8)
  $stream.Dispose()
  $major = ($head[6] -shl 8) + $head[7]
  Check 'class file major version is 65 (Java 21)' ($read -eq 8 -and $major -eq 65) ('major=' + $major)
} finally {
  $zip.Dispose()
}

Section 'mods.toml'
$tomlPath = Join-Path $proj 'resources\META-INF\neoforge.mods.toml'
$toml = Get-Content -LiteralPath $tomlPath -Raw
$srcPath = Join-Path $proj 'src\com\tacznofire\TaczNoFire.java'
$src = Get-Content -LiteralPath $srcPath -Raw
$tomlId = [regex]::Match($toml, 'modId\s*=\s*"([^"]+)"').Groups[1].Value
$srcId  = [regex]::Match($src, 'MOD_ID\s*=\s*"([^"]+)"').Groups[1].Value
Check 'modId in toml matches MOD_ID in source' ($tomlId -eq $srcId -and $tomlId -ne '') ($tomlId)
Check 'tacz declared as a required dependency' ([regex]::IsMatch($toml, '(?s)dependencies\.tacznofire\]\][^[]*modId\s*=\s*"tacz"[^[]*type\s*=\s*"required"'))
Check 'neoforge + minecraft declared required' (([regex]::IsMatch($toml, 'modId\s*=\s*"neoforge"')) -and ([regex]::IsMatch($toml, 'modId\s*=\s*"minecraft"')))
$defaultNs = [regex]::Match($src, 'BUILTIN_NAMESPACE\s*=\s*"([^"]+)"').Groups[1].Value
Check 'default blocked namespace is the built-in pack "tacz"' ($defaultNs -eq 'tacz') ($defaultNs)

# The text the mod writes into a fresh config must be the same text it falls back to.
$srcLore = [regex]::Match($src, 'DEFAULT_LORE_TEXT\s*=\s*"([^"]+)"').Groups[1].Value
$cfgLore = [regex]::Match($src, '(?m)^\s*lore_text = (.+?)\s*$').Groups[1].Value
Check 'generated config ships the same lore text as the built-in default' ($srcLore -ne '' -and $srcLore -eq $cfgLore) ($srcLore)
Check 'mod uses no mixins' (-not (Test-Path (Join-Path $proj 'resources\tacznofire.mixins.json')))

Section 'compiled mod calls the TACZ API correctly'
$disasm = (& $javap -p -c -cp $jar com.tacznofire.TaczNoFire 2>&1) -join "`n"
# annotations only show up in javap -v
$verbose = (& $javap -v -p -cp $jar com.tacznofire.TaczNoFire 2>&1) -join "`n"
Check 'onGunShoot(GunShootEvent) present' ([regex]::IsMatch($disasm, 'onGunShoot\(com\.tacz\.guns\.api\.event\.common\.GunShootEvent\)'))
Check 'onGunFire(GunFireEvent) present' ([regex]::IsMatch($disasm, 'onGunFire\(com\.tacz\.guns\.api\.event\.common\.GunFireEvent\)'))
Check 'cancels the event (setCanceled)' ([regex]::IsMatch($disasm, 'setCanceled'))
Check 'reads the gun id through IGun API' (([regex]::IsMatch($disasm, 'IGun\.getIGunOrNull')) -and ([regex]::IsMatch($disasm, 'getGunId')))
Check 'registered via @EventBusSubscriber' ([regex]::IsMatch($verbose, 'EventBusSubscriber'))
Check 'declared as a @Mod entry point' ([regex]::IsMatch($verbose, 'net\.neoforged\.fml\.common\.Mod'))
Check 'has a public no-arg constructor (FML needs it)' ([regex]::IsMatch($disasm, 'public com\.tacznofire\.TaczNoFire\(\)'))

# The matching core is plain Java and is executed for real below; the ResourceLocation
# adapter in front of it is only three lines, so it is checked here in the bytecode.
$ruleDisasm = (& $javap -p -c -cp $jar 'com.tacznofire.TaczNoFire$Rule' 2>&1) -join "`n"
Check 'ResourceLocation adapter reads getNamespace()' ([regex]::IsMatch($ruleDisasm, 'ResourceLocation\.getNamespace'))
Check 'ResourceLocation adapter passes the full gun id' ([regex]::IsMatch($ruleDisasm, 'ResourceLocation\.toString'))
Check 'matcher tests the parsed sets with Set.contains' ([regex]::IsMatch($ruleDisasm, 'Set\.contains'))

# The lore line: handler present, reads the mutable tooltip list, styled italic+coloured.
Check 'onItemTooltip(ItemTooltipEvent) present' ([regex]::IsMatch($disasm, 'onItemTooltip\(net\.neoforged\.neoforge\.event\.entity\.player\.ItemTooltipEvent\)'))
Check 'tooltip line is appended to getToolTip()' ([regex]::IsMatch($disasm, 'ItemTooltipEvent\.getToolTip'))
Check 'tooltip line is styled (italic + colour), like vanilla lore' (([regex]::IsMatch($disasm, 'Style\.withItalic')) -and ([regex]::IsMatch($disasm, 'Style\.withColor')))
Check 'tooltip line reads the same rule as the firing block' ([regex]::IsMatch($disasm, 'TaczNoFire\$Rule\.blocks'))

Section 'the lore line lands where vanilla lore lives (checked in the patched ItemStack)'
$nfcJar = Join-Path $lib 'net\neoforged\neoforge\21.1.249\neoforge-21.1.249-client.jar'
$patchedStack = (& $javap -p -c -cp $nfcJar net.minecraft.world.item.ItemStack 2>&1) -join "`n"
Check 'ItemStack fires ItemTooltipEvent as the very last step' ([regex]::IsMatch($patchedStack, 'onItemTooltip[\s\S]{0,400}areturn'))

Section 'TACZ really honours the cancellation (checked in TACZ bytecode)'
$event = (& $javap -p -cp $tacz.FullName com.tacz.guns.api.event.common.GunShootEvent 2>&1) -join "`n"
$busJar = Join-Path $lib 'net\neoforged\bus\8.0.5\bus-8.0.5.jar'
$busApi = (& $javap -p -cp $busJar net.neoforged.bus.api.ICancellableEvent 2>&1) -join "`n"
Check 'GunShootEvent is cancellable' ([regex]::IsMatch($event, 'ICancellableEvent'))
Check 'ICancellableEvent provides setCanceled()' ([regex]::IsMatch($busApi, 'setCanceled'))
$igun = (& $javap -p -cp $tacz.FullName com.tacz.guns.api.item.IGun 2>&1) -join "`n"
Check 'IGun.getGunId(ItemStack) still exists' ([regex]::IsMatch($igun, 'getGunId\(net\.minecraft\.world\.item\.ItemStack\)'))
Check 'IGun.getIGunOrNull(ItemStack) still exists' ([regex]::IsMatch($igun, 'getIGunOrNull\(net\.minecraft\.world\.item\.ItemStack\)'))
$server = (& $javap -p -c -cp $tacz.FullName com.tacz.guns.entity.shooter.LivingEntityShoot 2>&1) -join "`n"
Check 'server path posts GunShootEvent then checks isCanceled' (([regex]::IsMatch($server, 'GunShootEvent')) -and ([regex]::IsMatch($server, 'GunShootEvent\.isCanceled')))
$client = (& $javap -p -c -cp $tacz.FullName com.tacz.guns.client.gameplay.LocalPlayerShoot 2>&1) -join "`n"
Check 'client path posts GunShootEvent then checks isCanceled' (([regex]::IsMatch($client, 'GunShootEvent')) -and ([regex]::IsMatch($client, 'GunShootEvent\.isCanceled')))
Check 'cancelling yields a ShootResult (no shot, no ammo use)' ([regex]::IsMatch($client, 'FORGE_EVENT_CANCEL') -or [regex]::IsMatch($server, 'FORGE_EVENT_CANCEL'))
# Make-or-break detail: everything is posted on the plain game bus, which is exactly the bus
# @EventBusSubscriber(Bus.GAME) registers into. If TACZ ever moved to its own bus, the mod
# would load fine and silently do nothing.
Check 'server posts on NeoForge.EVENT_BUS' ([regex]::IsMatch($server, 'NeoForge\.EVENT_BUS'))
Check 'client posts on NeoForge.EVENT_BUS' ([regex]::IsMatch($client, 'NeoForge\.EVENT_BUS'))
# The scripted-firing path (KubeJS gun scripts) is a second, independent firing path.
$script = (& $javap -p -c -cp $tacz.FullName com.tacz.guns.item.ModernKineticGunScriptAPI 2>&1) -join "`n"
Check 'script API posts GunFireEvent then checks isCanceled' (([regex]::IsMatch($script, 'GunFireEvent')) -and ([regex]::IsMatch($script, 'GunFireEvent\.isCanceled')))
Check 'script API posts on NeoForge.EVENT_BUS' ([regex]::IsMatch($script, 'NeoForge\.EVENT_BUS'))
Check 'client fire animation is gated on GunFireEvent.isCanceled' (([regex]::IsMatch($client, 'GunFireEvent')) -and ([regex]::IsMatch($client, 'GunFireEvent\.isCanceled')))

Section 'rule engine executed for real'
$verifyClasses = Join-Path $proj 'build\verify'
if (Test-Path $verifyClasses) { Remove-Item $verifyClasses -Recurse -Force }
New-Item -ItemType Directory -Path $verifyClasses -Force | Out-Null
$testSource = Join-Path $proj 'verify\RuleTest.java'
& $javac -encoding UTF-8 --release 21 -nowarn -d $verifyClasses -cp ($classpath + ';' + (Join-Path $proj 'build\classes')) $testSource
if ($LASTEXITCODE -ne 0) { throw 'RuleTest compile failed' }
$output = & $java '-Dstdout.encoding=UTF-8' -cp ($verifyClasses + ';' + (Join-Path $proj 'build\classes') + ';' + $classpath) RuleTest 2>&1
$output | ForEach-Object { Write-Host ('    ' + $_) }
Check 'rule engine table test passed' ($LASTEXITCODE -eq 0)

Write-Host ''
$failed = @($results | Where-Object { -not $_.Ok })
Write-Host ('checks: ' + $results.Count + ', failed: ' + $failed.Count)
if ($failed.Count -gt 0) {
  Write-Host 'FAILED:'
  foreach ($f in $failed) { Write-Host ('  - ' + $f.Name) }
  exit 1
}
Write-Host 'OK'
