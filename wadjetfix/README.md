# wadjetfix — lionfishapi `DynamicChain.wrapAngles` 死循环修复

## 症状

游戏运行中画面突然完全静止（渲染线程 100% CPU 空转），日志里**没有任何 Java 异常**，
没有 `hs_err_pid*.log`，PCL 崩溃分析显示"0 个报错项 / 未找到可能的原因"，
最终 Windows 事件日志出现 `Application Hang`（`AppHangB1`，java.exe），进程被结束、返回码 1。

## 根因

`lionfishapi` 的 `com.github.L_Ender.lionfishapi.client.model.tools.DynamicChain.wrapAngles(Vec3)`
是一个没有迭代上限的循环（**2.8 / 3.0 / 3.1 字节码完全一致**）：

```java
private static Vec3 wrapAngles(Vec3 v) {
    double x = v.x, y = v.y, z = v.z;
    while (x >  Math.PI) x -= 2 * Math.PI;   // ← 无迭代上限
    while (x < -Math.PI) x += 2 * Math.PI;
    // y、z 同理
    return new Vec3(x, y, z);
}
```

当角度为 `±Infinity`，或绝对值大到 double 无法表示 "减 2π" 这一步
（`|a| ≳ 2^53·2π ≈ 5.6e16`，此时 `a - 2π == a`），循环**永远无法推进**。

触发路径（灾变 Cataclysm 的「瓦吉特 / Wadjet」实体）：

```
Wadjet_Model.setupAnim(Wadjet_Model.java:304)
  -> DynamicChain.updateChain(DynamicChain.java:186)
  -> DynamicChain.updateBendConstraint(DynamicChain.java:86)
  -> DynamicChain.wrapAngles(DynamicChain.java:341)   ← 死循环
```

## 修复

`@Overwrite` 该方法，把无界 while 换成取模一次算完。正常输入的输出区间与原实现一致，均为 `[-π, π]`。

### v1.0.1（当前）

```java
// WadjetMath.wrap
if (Double.isNaN(a)) return Double.NaN;                            // 原样透传，见下
if (Double.isInfinite(a) || Math.abs(a) > 1.0E6) return Double.NaN; // 原本会死循环，交给上游重建
double r = a % TWO_PI;
if (r >  Math.PI) r -= TWO_PI;
else if (r < -Math.PI) r += TWO_PI;
return r;
```

**关键：NaN 必须原样透传，不能改成 0。**

javac 给 `>` 生成 `dcmpl`、给 `<` 生成 `dcmpg`，两者遇到 NaN 都让循环直接退出——
也就是说原实现对 NaN 是「安全穿透」的，而这**不是巧合**：

`DynamicChain.updateChain` 第 159 行正是用 `Double.isNaN(this.p[1].x)` 作为
「链条算崩了，重建它」的判据：

```java
if (this.p.length != chainOrig.length || Double.isNaN(this.p[1].x)) {
    this.setChain(chainOrig, chainDynamic);   // 作者的"重建"路径
}
```

### v1.0.0 的错误（已修正）

v1.0.0 用的是 `Double.isFinite(x) ? x % 2π : 0.0`，把 NaN 也归零了。
后果：NaN 信号被掐断 → 链条永远不重建 → 尾巴卡在一个固定但错误的角度（**接不上**）；
偶尔真实值又通过 → 来回跳（**一闪一闪**）。

游戏不再卡死是真的，但尾巴的物理是坏的——本质上是这个 mod 本来就有的上游问题，
只是以前被"死循环"掩盖了，v1.0.0 把它暴露了出来。

## 目录结构

```
wadjetfix/
  src/com/wadjetfix/WadjetFix.java              NeoForge @Mod 入口
  src/com/wadjetfix/WadjetMath.java             角度处理 + 一次性诊断输出
  src/com/wadjetfix/mixin/DynamicChainMixin.java 真正的修复
  src/net/minecraft/world/phys/Vec3.java        仅编译期桩类，不打包
  src/net/neoforged/fml/common/Mod.java         仅编译期桩类，不打包
  resources/META-INF/neoforge.mods.toml
  resources/wadjetfix.mixins.json
  build/wadjetfix-1.0.1.jar                     产物
```

## 诊断输出

v1.0.1 会往 `logs/latest.log` 打最多三行 `[wadjetfix] ...`：

- `首个 NaN 角度（原样透传...）` —— NaN 确实存在
- `首个异常角度: <值> -> 按 NaN 处理` —— **如果打印出 `Infinity`，那就是卡死的元凶**
- `尾巴骨节长度 d[] = ...` —— 看 `d[1..5]` 有没有退化成 `0`

最后一条很关键：`updateBendConstraint` 里有一处除零

```java
this.ra[i] = this.T[i].scale(1.0f / (this.m[i+1] * this.d[i+1] * this.d[i+1]));
```

`d[i]` 是尾巴骨节长度（模型里 tail1..tailend 的间距，静止姿势约 18/18/15/15/15）。
只要 `d[i] == 0`，`1.0f/0.0f` 就是 `Infinity` → 顺着 `rv[i]` 流到 `wrapAngles` → 死循环。
如果日志里 `d[]` 出现了 0，那就可以直接夹住 `d` 从根上解决，不用再兜 `wrapAngles`。

> `@Mixin(targets = "...")` 用的是字符串形式，所以编译时不需要 lionfishapi 参与。
> `Vec3` 和 `@Mod` 用桩类顶替，只为了算出正确的字节码描述符，
> 运行时链接到真正的类。**这两个桩类绝对不能被打进 jar。**

## 重新编译

```powershell
$root = "E:\MC文件\起始点 132231123321321231\wadjetfix"
$lib  = "E:\MC文件\起始点 132231123321321231\.minecraft\libraries"
$jdk  = "$env:APPDATA\.minecraft\runtime\java-runtime-delta\bin"
$mixin = "$lib\net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar"

$classes = "$root\build\classes"
Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classes -Force | Out-Null

& "$jdk\javac.exe" -encoding UTF-8 --release 21 -nowarn -proc:none `
  -cp $mixin -d $classes (Get-ChildItem "$root\src" -Recurse -Filter *.java).FullName
```

打包时**只拷 `com/**` 和 resources，排除 `net/**`**，
并且 zip 条目必须用正斜杠 `/`（用 .NET 的 `ZipFile.CreateFromDirectory` 会写成反斜杠，jar 会失效）。

## 验证是否生效

游戏启动后看 `logs/latest.log`，应该出现 wadjetfix 加载且**没有 mixin 报错**：

```
Loaded mod wadjetfix ...
```

如果出现 `Mixin apply failed` / `wadjetfix.mixins.json` 相关错误，把日志发出来。

## 注意

- mixin 配置里 `"required": false`，所以即使 lionfishapi 不在或方法签名变了，
  **游戏也只会警告、不会崩**（但修复也不会生效）。
- 如果 lionfishapi 以后更新并改了这个方法，本修复会失效，需要重新适配。
