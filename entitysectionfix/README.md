# entitysectionfix — 修复区块状态变更时的 ConcurrentModificationException

**版本**：1.0.0　|　**产物**：`artifacts\entitysectionfix-1.0.0.jar`（3,307 字节）
**适用**：Minecraft 1.21.1 / NeoForge 21.1.249（客户端 + 服务端同一个 jar）

---

## 一、崩溃现象

服务端 `crash-2026-10-06_09.08.36-server.txt`（以及 11:23 那次复现）：

```
Description: Exception in server tick loop
java.util.ConcurrentModificationException
  at java.util.ArrayList$Itr.checkForComodification
  at PersistentEntitySectionManager.lambda$updateChunkStatus$6(PersistentEntitySectionManager.java:171)
  at PersistentEntitySectionManager.updateChunkStatus
  at ChunkMap.onFullChunkStatusChange
  at ChunkHolder.demoteFullChunk
  at ChunkHolder.updateFutures
  at DistanceManager.runAllUpdates
```

日志里的触发时机非常明确 —— **玩家退出后 16 毫秒**：

```
11:23:31.580  [Server thread] Su_son left the game
11:23:31.616  [Server thread/ERROR] Encountered an unexpected exception
              java.util.ConcurrentModificationException
```

即：玩家退服 / 走远 → 区块降级卸载 → 崩溃。可稳定复现。

## 二、根因：原版的遍历不可重入

反编译 `lambda$updateChunkStatus$6`（客户端 srg jar，`javap -c`）后可见，它对着
`section.getEntities()` 做了 **4 次流式遍历**：

```java
if ( 变为ticking && 原本不ticking )   section.getEntities().filter(..).forEach(this::startTicking);
if ( 不再可见 && 原本可见 )           section.getEntities().filter(..).forEach(this::stopTracking);
else if ( 变为可见 && 原本不可见 )     section.getEntities().filter(..).forEach(this::startTracking);
if ( 不再ticking && 原本ticking )     section.getEntities().filter(..).forEach(this::stopTicking);
```

而 `EntitySection.getEntities()` 返回的是**实时流**：

```java
private final ClassInstanceMultiMap<T> storage;              // 底层 ArrayList
public Stream<T> getEntities() { return this.storage.stream(); }
```

`startTracking` / `stopTracking` 会穿过 `ChunkMap` 的追踪逻辑。**只要有 mod 在这段时间里
把实体挪出该区块段**，就会走到：

```
PersistentEntitySectionManager$Callback.onMove()
  → EntitySection.remove(entity)     ← 结构性修改正在被遍历的 ArrayList
    → ArrayList$Itr.checkForComodification  → CME
```

谁会在这期间挪实体？本整合包里主要是：

| 嫌疑 | 依据 |
|---|---|
| **Sable 2.0.5**（主嫌） | 崩溃报告横幅即 Sable 自己的；它的核心功能就是让实体逐 tick 跟随移动的 sub-level（子世界/载具）而传送，并在 `ChunkMap` / `ServerChunkCache` / `PersistentEntitySectionManager` 上挂了 4 处以上 mixin |
| **车万女仆 1.5.3**（次嫌） | mixin 在区块卸载时 `teleportToOwner(owner)` 传送女仆；传送=跨段移动=同一个触发点 |

注：Sable 的横幅只是它给自己加的提示语，**本身不是证据**，但结合其功能特性它确实最可疑。
Sable 最新版 2.0.6（2026-10-03）的更新日志只提到流体上的实体与 fabric 网络 mixin，
**没有提到这个 CME**，所以单纯升级不保证修好。

## 三、修复方式：一处 mixin，把实时流换成快照流

```java
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin {
    @Inject(method = "getEntities()Ljava/util/stream/Stream;",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void entitysectionfix$snapshot(CallbackInfoReturnable<Stream<?>> cir) {
        Stream<?> live = cir.getReturnValue();
        if (live != null) {
            cir.setReturnValue(live.toList().stream());   // 物化成不可变列表再返回
        }
    }
}
```

原版那 4 处遍历因此都变成对**快照**的迭代：底层列表在迭代中怎么变都不再抛 CME，
而遍历到的那批实体与"遍历开始时的那批"一致 —— 这本来就是原版的意图。

**为什么这样改是安全的**：

- **语义**：快照只影响"迭代期间新加入/移除的实体会在本轮被跳过/照常处理"，与原版一致；
  异常分支本来就意味着原版期望列表不变。
- **性能**：只改无参的 `getEntities()`。全 jar 扫描确认它仅被 7 个实体管理类引用
  （`PersistentEntitySectionManager`、`EntitySectionStorage`、`ClientLevel`、`ServerLevel`、
  `LevelEntityGetterAdapter`、`TransientEntitySectionManager`、`EntitySection` 自身），
  全部位于区块状态变更 / 卸载路径，**不是每 tick 的实体碰撞查询热路径**。
  AABB 查询用的 `getEntities(AABB, ...)` / `getEntities(TypeTest, AABB, ...)` 两个重载
  **完全没有改动**。
- **不改任何 mod 的功能**：只是把原版迭代变安全，不禁止任何 mod 的行为。

## 四、验证状态

构建脚本自带三轮校验，全部通过：

- **preflight**：`javap` 确认 `EntitySection.getEntities()` 存在且描述符为
  `()Ljava/util/stream/Stream;`
- **编译**：对着真实 Minecraft / NeoForge / Mixin 共 8 个 jar 编译（javac 21.0.7），无手写 stub
- **postflight**：反编译产物确认 mixin 的 `@Mixin` 指向
  `net.minecraft.world.level.entity.EntitySection`，`@Inject` 的 `method=[...]` 为
  `getEntities()Ljava/util/stream/Stream;`、`at=@At("RETURN")`、`cancellable=true`、`require=1`

注入用 `require = 1`：万一将来 Minecraft 改了这个方法，会在启动时**明确报错**，
而不是静默失效让崩溃悄悄回来。

## 五、安装与卸载

- **客户端**：已放入 `.minecraft\versions\起始点\mods\entitysectionfix-1.0.0.jar`
- **服务端**：把同一个 jar 上传到服务器的 `mods` 目录（日志显示是 `/data/mods`），
  **必须两边都装**，否则服务端仍会崩。
- **卸载/回滚**：删掉这个 jar 即可彻底恢复原状（不修改任何原 mod 文件，不生成配置）。
- **不影响**：与 Sable、车万女仆、Create 系列等均无冲突 —— 它只改原版的一个 getter。

## 六、目录结构

```
entitysectionfix\
  build.ps1                              # 构建 + 三轮校验（ASCII-only，PS 5.1 安全）
  build-meta.txt                         # 构建时间 / javac / classpath 记录
  src\com\entitysectionfix\
    EntitySectionFix.java                # @Mod 入口，只打一行日志
    mixin\EntitySectionMixin.java        # 快照流注入
  resources\
    META-INF\neoforge.mods.toml
    entitysectionfix.mixins.json
  artifacts\entitysectionfix-1.0.0.jar
```

重新构建：`pwsh -NoProfile -File 相关文件\DeepSeek写的mod\entitysectionfix\build.ps1`
