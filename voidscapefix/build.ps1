# voidscapefix 修复版：重新编译 + 打包
# 用法： pwsh -NoProfile -File build.ps1
$ErrorActionPreference = 'Stop'

$here     = $PSScriptRoot
$root     = (Resolve-Path (Join-Path $here '..\..\..')).Path        # 工作区根目录
$jdkBin   = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin'
$mixinJar = Join-Path $root '.minecraft\libraries\net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar'
$baseJar  = Join-Path $here '旧版备份\虚空工艺补丁 voidscapefix-1.0.0.jar'   # 用原始 jar 当骨架
$outJar   = Join-Path $here 'artifacts\虚空工艺补丁 voidscapefix-1.0.0.jar'
$build    = Join-Path $here 'build'

foreach ($p in @($jdkBin, $mixinJar, $baseJar)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "找不到：$p" }
}

Remove-Item -LiteralPath $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $build | Out-Null
[System.IO.Compression.ZipFile]::ExtractToDirectory($baseJar, $build)

$sources = (Get-ChildItem -LiteralPath (Join-Path $here 'src') -Recurse -Filter '*.java').FullName
& (Join-Path $jdkBin 'javac.exe') -proc:none --release 21 -encoding UTF-8 -cp $mixinJar -d $build $sources
if ($LASTEXITCODE -ne 0) { throw '编译失败' }

New-Item -ItemType Directory -Force -Path (Split-Path $outJar) | Out-Null
Remove-Item -LiteralPath $outJar -Force -ErrorAction SilentlyContinue
[System.IO.Compression.ZipFile]::CreateFromDirectory($build, $outJar, [System.IO.Compression.CompressionLevel]::Optimal, $false)

Write-Host ('打包完成：{0}  ({1} 字节)' -f $outJar, (Get-Item -LiteralPath $outJar).Length)
Write-Host '记得手动覆盖到 .minecraft\versions\起始点\mods\ 里才生效'
