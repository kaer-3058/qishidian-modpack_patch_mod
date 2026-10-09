# ============================================================================
#  verify.ps1 —— 抓 "编译期桩类 / 假实现 的形状与真实 API 不一致" 这类错误
#
#  注意：本文件必须保存为 **UTF-8 with BOM**。
#        PowerShell 5.1 会把无 BOM 的 .ps1 按系统 ANSI(GBK) 读，
#        中文注释会变成乱码并直接导致语法错误。
#        如果重新生成了这个文件，记得补 BOM：
#          $c = Get-Content -LiteralPath $p -Raw -Encoding UTF8
#          [System.IO.File]::WriteAllText($p, $c, (New-Object System.Text.UTF8Encoding($true)))
#
#  为什么需要它：
#    本项目靠手写桩类（net.minecraft.* / net.neoforged.*）在没有 Minecraft 的
#    情况下编译。桩类的**类型种类**（interface / class）如果和真实的相反，
#    javac 会按桩类选出错误的调用指令：
#        真实是 interface 而桩写成 class  -> javac 编出 Methodref
#        -> 运行时报 IncompatibleClassChangeError: must be InterfaceMethodref constant
#    编译期完全看不出来，本地单元测试也看不出来（假实现和桩类一起错）。
#    真实案例：tastejournalfix 1.1.0 把 net.minecraft.network.chat.Component
#    写成了 class（真身是 interface），一悬停带标记的物品就崩游戏。
#
#  原理：
#    读 javap -c 输出里每条方法引用。javap 会把常量池条目的种类打出来：
#       // Method <owner>.<name>:<desc>            常量池是 Methodref
#       // InterfaceMethod <owner>.<name>:<desc>   常量池是 InterfaceMethodref
#    JVM 要求：owner 是接口 <=> 必须用 InterfaceMethodref。
#    所以只要拿真实 jar javap 一下每个 owner 的种类，就能对出矛盾。
#
#  用法：& .\verify.ps1
# ============================================================================

$ErrorActionPreference = 'Stop'

$proj   = $PSScriptRoot
$jar    = Join-Path $proj 'build\tastejournalfix-1.1.1.jar'
$javap  = 'C:\Users\kaer1\AppData\Roaming\.minecraft\runtime\java-runtime-delta\bin\javap.exe'
$mcdir  = 'E:\MC文件\起始点 132231123321321231\.minecraft\libraries'

$realJars = @(
    (Get-ChildItem -LiteralPath "$mcdir\net\minecraft" -Recurse -Filter 'client-1.21.1*srg.jar' | Select-Object -First 1).FullName,
    (Get-ChildItem -LiteralPath "$mcdir\net\neoforged\neoforge" -Recurse -Filter 'neoforge-*-universal.jar' | Select-Object -First 1).FullName,
    (Get-ChildItem -LiteralPath "$mcdir\net\neoforged" -Recurse -Filter 'loader-*.jar' | Select-Object -First 1).FullName,
    (Get-ChildItem -LiteralPath "$mcdir\net\neoforged" -Recurse -Filter 'bus-*.jar' | Select-Object -First 1).FullName
)
$cp = ($realJars | Where-Object { $_ }) -join ';'

Write-Host "jar      : $jar"
Write-Host "参照 jars: $cp"
Write-Host ''

Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---- 0. 桩类不得泄漏进 jar -------------------------------------------------
$z = [System.IO.Compression.ZipFile]::OpenRead($jar)
$entries = @($z.Entries | ForEach-Object { $_.FullName })
$z.Dispose()
$leaked = @($entries | Where-Object { $_ -match '^(net/minecraft|net/neoforged)/' })
if ($leaked.Count -gt 0) {
    Write-Host "[FAIL] 桩类泄漏进 jar:" -ForegroundColor Red
    $leaked | ForEach-Object { Write-Host "         $_" }
} else {
    Write-Host "[OK]   无桩类泄漏（jar 内只有 com/ 与资源）" -ForegroundColor Green
}

# ---- 1. 从自己的 class 里收集 "owner -> 用到的常量池种类" -------------------
$myClasses = @($entries | Where-Object { $_ -like 'com/*.class' } |
               ForEach-Object { $_ -replace '\.class$','' -replace '/','.' })

$refs = @{}
foreach ($c in $myClasses) {
    $out = & $javap -c -p -classpath $jar $c 2>&1
    foreach ($line in $out) {
        if ($line -match '//\s+(InterfaceMethod|Method)\s+(\S+?):') {
            $kind = $matches[1]
            $full = $matches[2]
            $dot  = $full.LastIndexOf('.')
            if ($dot -lt 1) { continue }
            $owner = $full.Substring(0, $dot)
            if (-not $refs.ContainsKey($owner)) { $refs[$owner] = @{} }
            $refs[$owner][$kind] = $true
        }
    }
}

Write-Host ''
Write-Host "被引用的类型 owner 共 $($refs.Count) 个"
Write-Host ''

# ---- 2. 对每个 owner 查真实种类并比对 --------------------------------------
$fail = 0
$unknown = 0
foreach ($owner in ($refs.Keys | Sort-Object)) {
    $ownerDot = $owner -replace '/','.'
    $decl = & $javap -classpath $cp $ownerDot 2>&1 |
            Where-Object { $_ -match '\b(interface|class|enum)\s' } |
            Select-Object -First 1
    if (-not $decl) {
        Write-Host ("[????]  {0,-70} 找不到真实类，无法判定" -f $owner) -ForegroundColor Yellow
        $unknown++
        continue
    }
    $isIface = $decl -match '\binterface\s'
    $kinds = @($refs[$owner].Keys)
    $usedIfaceRef = $kinds -contains 'InterfaceMethod'

    if ($isIface -and -not $usedIfaceRef) {
        Write-Host ("[FAIL]  {0}" -f $owner) -ForegroundColor Red
        Write-Host  "        真实是 interface，但代码用的是 Methodref"
        Write-Host  "        -> 运行时会 IncompatibleClassChangeError: must be InterfaceMethodref constant"
        Write-Host  "        修法：把桩类改成 interface"
        $fail++
    } elseif ((-not $isIface) -and $usedIfaceRef) {
        Write-Host ("[FAIL]  {0}" -f $owner) -ForegroundColor Red
        Write-Host  "        真实是 class，但代码用的是 InterfaceMethodref"
        Write-Host  "        修法：把桩类改成 class"
        $fail++
    } else {
        $tag = if ($isIface) { 'interface' } else { 'class' }
        Write-Host ("[OK]    {0,-70} {1}" -f $owner, $tag) -ForegroundColor Green
    }
}

Write-Host ''
if ($fail -eq 0) {
    Write-Host "校验通过：$($refs.Count) 个引用的种类全部与真实 API 一致（$unknown 个无法判定）" -ForegroundColor Green
    exit 0
} else {
    Write-Host "校验失败：$fail 个类型种类冲突" -ForegroundColor Red
    exit 1
}
