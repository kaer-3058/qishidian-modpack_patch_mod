# Sable Fix — 两个 Sable 客户端崩溃的补丁

**问题 mod**：`sable-neoforge-1.21.1-2.0.5.jar` / `sable-neoforge-1.21.1-2.0.6.jar`（modId `sable`）
**修复 mod**：`sablefix-1.0.1.jar`（4.6 KB，无依赖，不打包任何桩类）

| 版本 | 修的 bug | 崩溃报告 |
|---|---|---|
| 1.0.0 | Sable 2.0.5 退出世界必崩的 NPE | `crash-2026-10-02_02.22.12-client.txt` |
| 1.0.1 | Sable 2.0.6 在子层级(plot)里被卸载区块必断线 | `disconnect-2026-10-14_11.53.03-client.txt`、`…11.53.42-client.txt` |

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

---

# 1.0.1 新增：修复 Sable 2.0.6 在子层级里被卸载区块必断线

## 一、现象

两份报告（`disconnect-2026-10-14_11.53.03-client.txt` / `…11.53.42-client.txt`，相隔 39 秒）是同一个异常：

```
Description: Packet handling error
java.lang.UnsupportedOperationException: Cannot drop chunks in plot
  at ClientChunkCache.handler$jkn000$sable$drop(ClientChunkCache.java:4088)
  at ClientChunkCache.drop(ClientChunkCache.java)
  at ClientPacketListener.handleForgetLevelChunk(ClientPacketListener.java:781)
  at ClientboundForgetLevelChunkPacket.handle(ClientboundForgetLevelChunkPacket.java:27)
```

## 二、根因（反编译 sable-neoforge-1.21.1-2.0.6.jar）

`dev/ryanhcode/sable/mixin/plot/ClientChunkCacheMixin.drop`：

```java
private void drop(ChunkPos pos, CallbackInfo ci) {
    SubLevelContainer c = sable$getPlotContainer();
    if (c.inBounds(pos)) {
        ci.cancel();                                                            // ①
        throw new UnsupportedOperationException("Cannot drop chunks in plot");  // ②
    }
}
```

① 已经取消了这次丢弃（区块本来就保住了），② 是多余的。异常抛在**网络包处理线程**里，
被主循环当成包处理失败 → 直接断开连接。

触发条件：**人待在 Sable 子层级(plot)内部**时，服务端发来"忘记区块"包，且该位置落在 plot 边界内。
所以进/出 Sable 载具、站在船上时最容易中招。

2.0.6 是 1.21.1 上的最新版（Modrinth 版本接口核对过），上游未修 —— 只能自己打补丁。

## 三、修法

只包住 `handleForgetLevelChunk` 里对 `ClientChunkCache.drop` 的那一次调用：

```java
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Redirect(
        method = "handleForgetLevelChunk(Lnet/minecraft/network/protocol/game/ClientboundForgetLevelChunkPacket;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;drop(Lnet/minecraft/world/level/ChunkPos;)V"),
        require = 0)
    private void sablefix$dropChunkSafely(ClientChunkCache cache, ChunkPos pos) {
        try { cache.drop(pos); }
        catch (UnsupportedOperationException e) {
            if (!"Cannot drop chunks in plot".equals(e.getMessage())) { throw e; }
            SableFix.reportPlotDropOnce();
        }
    }
}
```

只吞 message 一字不差的这一个异常，其它异常原样抛出；只动这一个调用点，
其它地方（ClientLevel 卸载、视距变化等）的 `drop` 调用完全不受影响。

mixin 放在 `sablefix.mixins.json` 的 **`"client"`** 段，专用服务端上永远不会加载。

## 四、验证

进服务器 → 进/出 Sable 子层级（上船、进出载具）→ 看 `logs\latest.log`：

- 看到 `[sablefix] 拦下 Sable 的 UnsupportedOperationException("Cannot drop chunks in plot") —— 子层级里不再被踢出服务器，修复已生效。`
  → ✅ 修复生效
- 没看到但也没断线 → 这次没走到那条路径
- **又断线** → mixin 没应用，去看 `latest.log` 里的 mixin 警告

## 五、构建

```powershell
& "…\相关文件\DeepSeek写的mod\sablefix\build.ps1"
```

脚本先做前置校验：原版两个方法的签名、`handleForgetLevelChunk` 里 `ClientChunkCache.drop`
恰好被调用一次、**你实际在用的那个 Sable jar 里那句异常还在**；编译后做后置校验：
mixin 带对了 handler/target/字面量、mixins.json 是 client-only、toml 是 1.0.1 +
`displayTest = "IGNORE_ALL_VERSION"`。产物 `artifacts\sablefix-1.0.1.jar`。

1.0.0 那套手写桩类（`src\net`、`src\dev`）不再参与编译（改用真实 jar），但依旧不许进 jar。
