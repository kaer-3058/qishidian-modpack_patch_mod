# Sable Fix — 修复 Sable 2.0.5 退出世界必崩的 NPE

**问题 mod**：`sable-neoforge-1.21.1-2.0.5.jar`（modId `sable`）
**崩溃报告**：`crash-2026-10-02_02.22.12-client.txt`
**修复 mod**：`sablefix-1.0.0.jar`（2.4 KB，无依赖，不打包任何桩类）

---

## 一、崩溃现象

```
java.lang.NullPointerException: Cannot invoke
  "dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension.sable$getTrackingSubLevel()"
  because "entity" is null
    at dev.ryanhcode.sable.ActiveSableCompanion.getTrackingSubLevel(ActiveSableCompanion.java:435)
    at net.minecraft.client.renderer.GameRenderer.handler$iph000$sable$renderLevel(GameRenderer.java:24319)
    at net.minecraft.client.renderer.GameRenderer.renderLevel(GameRenderer.java)
    ...
    at net.minecraft.client.Minecraft.run(Minecraft.java:828)
```

**退出世界（回到标题界面）时 100% 复现。**

崩溃报告自身也自证了 `player` 为 null：

| 报告字段 | 值 |
|---|---|
| `All players` | `0 total; []` |
| `Server brand` | `~~ERROR~~ NullPointerException: Cannot read field "connection" because "this.minecraft.player" is null` |
| AllTheLeaks | `Server Stopped: 1`、`MinecraftServer: IntegratedServer: 1` |

---

## 二、根因

Sable 的 mixin `dev/ryanhcode/sable/mixin/entity/entities_turn_with_sub_levels/GameRendererMixin`：

```java
@Inject(method = {"renderLevel"}, at = {@At(value = "HEAD")})
public void renderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
    LocalPlayer player = this.minecraft.player;
    SubLevel standingSubLevel = Sable.HELPER.getTrackingSubLevel(player);   // ← player 没判空
    if (standingSubLevel != null && player.getVehicle() == null && ...     // ← 跑不到这里就炸了
```

被调用的方法，方法体第一件事就是拿参数强转：

```java
// ActiveSableCompanion.java:435
public SubLevel getTrackingSubLevel(Entity entity) {
    return ((EntityMovementExtension) entity).sable$getTrackingSubLevel();   // entity == null → NPE
}
```

注意注入点是 **`@At("HEAD")`**，比后面那句 `if (standingSubLevel != null ...)` 更早执行，
所以调用方自己那点判空完全救不了。

### 触发时序（latest.log 实锤）

```
02:22:09.641  Stopping singleplayer server as player logged out
02:22:10.415  Stopping server / Saving players / Saving worlds
02:22:11.068  Saving chunks for level 'ServerLevel[新的世界]'/minecraft:overworld
02:22:11.068  [Sable] Saving sub-levels for ... overworld
02:22:11.743  Saving chunks for level ... extradelight:cornfield     ← 全部维度保存完毕
02:22:12.507  [Puzzles Lib] Dispatching unloading event               ← 才开始卸载
02:22:12.513  [fastquit] "新的世界" was not registered in currently saving worlds!
02:22:12.561  [fastquit] Disconnected "新的世界" from the client.      ← player 变 null
02:22:12.934  [fastquit] 等待"新的世界"完成保存……
02:22:12.966  FATAL: Preparing crash report                          ← 崩
```

**FastQuit 是帮凶不是元凶**：它断开客户端后还挂着 `WaitingScreen` 持续渲染，把
"`player == null` 但仍在 `renderLevel`"这个窗口撑得很宽。但根因是 Sable 没判空——
正常退出时那一帧的竞态同样存在，只是窗口更窄。

**存档是安全的**：崩溃发生在全部维度保存完成（02:22:11.743）之后。这是"退世界必崩"，
不是"坏档"。

---

## 三、修法

在 `getTrackingSubLevel` 入口拦下 null，返回 null：

```java
@Mixin(targets = "dev.ryanhcode.sable.ActiveSableCompanion")
public abstract class ActiveSableCompanionMixin {
    @Inject(
        method = "getTrackingSubLevel(Lnet/minecraft/world/entity/Entity;)Ldev/ryanhcode/sable/sublevel/SubLevel;",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void sablefix$guardNullEntity(Entity entity, CallbackInfoReturnable<SubLevel> cir) {
        if (entity == null) { SableFix.reportOnce(); cir.setReturnValue(null); }
    }
}
```

**为什么返回 `null` 无害**：调用方自己的逻辑是

```java
if (standingSubLevel != null && player.getVehicle() == null && ...) { ...调朝向... }
else { this.sable$lastSubLevel = null; }     // ← 返回 null 正好落这里，只是重置缓存
```

### 一个容易踩的坑：只补一个方法就够

`ActiveSableCompanion` 里有**两个同名同参、只有返回类型不同**的方法：

```
public SubLevel        getTrackingSubLevel(net.minecraft.world.entity.Entity);
public SubLevelAccess  getTrackingSubLevel(net.minecraft.world.entity.Entity);
```

第二个是编译器为协变返回生成的 **bridge**，字节码里直接转发给第一个：

```
public dev.ryanhcode.sable.companion.SubLevelAccess getTrackingSubLevel(Entity);
   0: aload_0
   1: aload_1
   2: invokevirtual  getTrackingSubLevel:(Lnet/minecraft/world/entity/Entity;)Ldev/ryanhcode/sable/sublevel/SubLevel;
   5: areturn
```

而 `Sable.HELPER` 的静态类型就是 `ActiveSableCompanion`（`javap` 实测
`public static final dev.ryanhcode.sable.ActiveSableCompanion HELPER;`），
所以崩溃点命中的是第一个。补它一处，两个都安全。

---

## 四、验证方法

启动游戏 → 进世界 → 退出世界，然后在 `logs/latest.log` 里找：

```powershell
Select-String -LiteralPath "<实例>\logs\latest.log" -Pattern "\[sablefix\]" -Encoding UTF8
```

- 看到 `[sablefix] 拦下 Sable 的 getTrackingSubLevel(null) —— 退世界不再崩溃，修复已生效。`
  → ✅ 修复生效
- 什么都没看到且没崩 → 修复生效但没触发（也可能这次没走到那条路径）
- **崩了** → mixin 没应用，去看 `logs/latest.log` 里的 mixin 警告

---

## 五、构建方法

```powershell
$proj    = "…\sablefix"
$jdkBin  = "C:\Users\kaer1\AppData\Roaming\.minecraft\runtime\java-runtime-delta\bin"
$mixin   = "…\.minecraft\libraries\net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar"

# 1) 编译（-proc:none 必须加，否则 mixin 注解处理器会因缺 ASM 报
#    NoClassDefFoundError: org/objectweb/asm/tree/MethodNode）
javac -encoding UTF-8 --release 21 -nowarn -proc:none `
      -cp $mixin -d "$proj\build\classes" (所有 src\**\*.java)

# 2) 打包：只打 com\** 和 resources\**，
#    条目名必须用正斜杠（ZipFile.CreateFromDirectory 会写成反斜杠导致 jar 无效）
```

### 桩类警告

以下三个文件**只是编译期桩，绝对不能打进 jar**：

| 桩文件 | 运行时由谁提供 |
|---|---|
| `src\net\neoforged\fml\common\Mod.java` | NeoForge（`neoforge-*-universal.jar` 里没有） |
| `src\net\minecraft\world\entity\Entity.java` | Minecraft 本体 |
| `src\dev\ryanhcode\sable\sublevel\SubLevel.java` | Sable |

因为它们**不在 jar 里**，运行时解析到的就是真类，描述符自然对上。
（mixin 校验 handler 签名走的是描述符，`CallbackInfoReturnable<SubLevel>` 的泛型参数不参与匹配，
桩类纯粹是为了让 `javac` 能过。）

打包脚本里已带安全检查：若 jar 内出现 `net/minecraft`、`net/neoforged`、`dev/ryanhcode`
开头的条目就直接报警。

---

## 六、已知局限

1. `@Inject` 用了 `require = 0` + mixin 配置 `"required": false`：
   **将来 Sable 改签名或换实现，修复会静默失效（只记一条警告），而不是把游戏搞崩。**
   这是刻意选的安全取舍——届时回来看这个文件即可。
2. 这是给第三方 mod 打的补丁，**依赖 Sable 内部实现**（类名 `ActiveSableCompanion`、
   方法描述符）。Sable 大改就得重新适配。
3. **正确做法是上报给 Sable 作者**：<https://github.com/ryanhcode/sable/issues>

   上报内容一句话版：

   > `entities_turn_with_sub_levels/GameRendererMixin.renderLevel` 在 `@At("HEAD")` 读取
   > `this.minecraft.player` 后未判空就传给 `ActiveSableCompanion.getTrackingSubLevel(Entity)`，
   > 后者无条件强转使用该参数。退出世界时（`minecraft.player == null` 但渲染线程仍跑一帧
   > `renderLevel`）必然 NPE 崩溃。修法：调用前判空，或在 `getTrackingSubLevel` 内
   > `if (entity == null) return null;`（调用方 `if (standingSubLevel != null ...)`
   > 的 else 分支会正确处理）。
