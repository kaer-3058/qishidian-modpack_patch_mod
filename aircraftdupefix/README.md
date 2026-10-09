# aircraftdupefix

修复「沉浸式飞行」（immersive_aircraft）的载具在**多人联机 / 服务器**里玩家退出重进时原地复制、
连货舱物资一起复制的问题。

- 版本：1.0.0
- 目标：Minecraft 1.21.1 + NeoForge 21.1.x（对 `immersive_aircraft 1.4.6+1.21.1` 实测的机制编写）
- 产物：`artifacts\aircraftdupefix-1.0.0.jar`（3457 字节）
- 没有 mixin，没有配置文件，没有指令。

---

## 1. 根因不在这个 mod 里，在原版的「载具随玩家存档」机制

三环相扣，行号取自**运行时的类**（`neoforge-21.1.249-client.jar` 里打过 NeoForge 补丁的
`PlayerList`），不是猜测：

**第一环 —— 登出时把整条载具写进玩家存档。**

`PlayerList#remove`：

```java
public void remove(ServerPlayer player) {
    EventHooks.firePlayerLoggedOut(player);   // 353 行：先发事件
    ServerLevel level = player.serverLevel();
    player.awardStat(Stats.LEAVE_GAME);
    this.save(player);                        // 356 行：这里才写盘
    if (player.isPassenger() && (root = player.getRootVehicle()).hasExactlyOnePlayerPassenger()) {
        player.stopRiding();
        root.getPassengersAndSelf().forEach(e -> e.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER));
    }
    ...
}
```

`save(player)` 走 `ServerPlayer#addAdditionalSaveData`，里面有一段：只要玩家骑着一个
**船上正好一名玩家**的载具，就把**这条载具的整份 NBT**（UUID、坐标，以及 immersive_aircraft
存在实体 NBT 里的货舱）写进 playerdata 的 `RootVehicle` 字段。

**第二环 —— 重进时照着这份 NBT 把载具重新造出来。**

`PlayerList#placeNewPlayer`：

```java
line 242: serverlevel1.addNewPlayer(player);
line 245: if (optional1.isPresent() && optional1.get().contains("RootVehicle", 10)
             && (entity = EntityType.loadEntityRecursive(
                     compoundtag.getCompound("RootVehicle").getCompound("Entity"),
                     serverlevel1, e -> !serverlevel1.addWithUUID(e) ? null : e)) != null) {
             ...  // 拆出来重建一条，并让玩家骑上去
         }
line 266: EventHooks.firePlayerLoggedIn(player);
```

**第三环 —— 「丢弃多余副本」的清理只在重新上船失败时才执行。**
重建出来的实体一旦成功加进世界、玩家一旦成功骑上去，清理分支就被跳过。

**结论：这不是 immersive_aircraft 的 bug。** 对这个包做过全量扫描：

| 搜索项 | 命中 |
|---|---|
| `addFreshEntity` | 只有 `BulletWeapon`（子弹）和 `item/VehicleItem`（从物品放置载具，服务端放完会 `shrink(1)`） |
| `loadEntityRecursive` | 只有 `BombBay`（投弹） |
| `PlayerTickEvent` / `ServerTickEvent` / `EntityJoinLevelEvent` / 登录登出钩子 | **一个都没有** |

所以「凭空多出一条船」只可能来自上面这条原版路径。

**为什么后果特别严重：** 这个 mod 把**整个货舱**挂在实体自己的 NBT 上
（`InventoryVehicleEntity` 的 `addAdditionalSaveData` 写 `Inventory`，
`readAdditionalSaveData` 读回来），而拆成物品时又会把货舱写进物品的 `CONTAINER` 组件。
于是复制是指数的：多一条船 = 多一整船物资；船拆成物品、物品再放下 = 又来一条。

---

## 2. 本 mod 怎么修

**介入点：登出事件。把玩家从载具上摘下来。**

`PlayerList#remove` 第 353 行发事件、第 356 行才写盘 —— **事件在前**。
所以在 `PlayerLoggedOutEvent` 里调一次 `stopRiding()`，等到 356 行保存时
`getVehicle()` 已经是 `null`，`RootVehicle` 根本不会被写入，第一环就不成立，
后面两环自然无从发生。

顺带一个好处：`isPassenger()` 也变成 `false`，所以原版「Removing player mount」那段
不会把船标成 `UNLOADED_WITH_PLAYER`，**船会正常留在世界里**，比原版行为更合理。

**第二个事件：重进时把玩家放回船上。**

摘下来之后，玩家重进会站在原来座位的位置上；如果当时正在半空飞行，不处理就会掉下去。
所以 `PlayerLoggedInEvent` 里会找「玩家站的那条船」重新骑上 —— 骑的是**世界里已经存在**的
那条船，不创建任何东西，所以不会引入新的复制。

判定条件刻意写得很紧，避免误抓旁边停着的另一条船：

- 实体类在 `immersive_aircraft.` 包下（顺继承链比对包名）
- 船体宽度 ≥ 1.0（排除同包下的子弹、TNT 这些小实体）
- 船上没有任何乘客
- 玩家坐标落在**这条船的判定箱（放宽 1 格）内部**

登出那一侧的条件则严格照抄原版那一行（`isPassenger() && hasExactlyOnePlayerPassenger()`），
只在「原版本来就会写 RootVehicle」时才动手 —— 多人共乘一艘船时原版不写，这里也不碰。

---

## 3. 为什么这次不用 mixin

因为**事件顺序是被证实的**，不需要改任何人的字节码：`PlayerLoggedOutEvent` 确实在
`save(player)` 之前。用事件就够了，而且：

- 不会因为别人的类改结构而在启动时炸掉
- 不与其它改同一处的 mod 抢优先级
- 上游刷新版本时不会因为混淆名对不上而静默失效

## 4. 为什么没有对 immersive_aircraft 的编译期依赖

载具是靠**包名字符串**认出来的，没有 import 它任何类。这样：

- `immersive_aircraft` 被移除时，本 mod 只是个空操作，不会 `NoClassDefFoundError` 连累启动
- `immersive_aircraft` 升级改名也不会让本 mod 一起挂掉
- 连它的附属/换名版本（类仍在同包下）也照样认得

`verify.ps1` 专门检查了这一点：编译产物里**没有** `immersive_aircraft/` 形式的类引用，
只有包名字符串常量。

---

## 5. 构建与校验

```
build.ps1    编译 + 打包 → artifacts\aircraftdupefix-1.0.0.jar
verify.ps1   对产物做 13 项结构校验
```

**这次不写编译桩。** 直接对着游戏**真实运行时的那几个 jar** 编译：

```
neoforge-21.1.249-client.jar      打补丁后的 Minecraft（优先，覆盖未打补丁的）
client-1.21.1-...-srg.jar         未打补丁的 Minecraft
neoforge-21.1.249-universal.jar   NeoForge 事件
bus-8.0.5.jar                     @SubscribeEvent
loader-4.0.44.jar                 @EventBusSubscriber
mergetool-2.0.0-api.jar           Dist
```

原因：上一次 tastejournalfix 的崩溃，是手写桩把 `Component` 写成了类、而真实类型是接口，
编译能过、游戏一加载就 `IncompatibleClassChangeError`。对着真实 jar 编译，
每个签名、每个描述符、每个类/接口形状都由 javac 替我们核对，这类错误从结构上消失。

**校验项（当前全部通过）**

| 类别 | 检查 |
|---|---|
| jar | 含 `com/aircraftdupefix/AircraftDupeFix.class`；含 `META-INF/neoforge.mods.toml`；zip 条目无反斜杠 |
| mods.toml | 无 UTF-8 BOM；`modId = "aircraftdupefix"`；version 存在 |
| class | class 文件版本 65（Java 21） |
| 注解 | 类上有 `@EventBusSubscriber`；**恰好两个**方法带 `@SubscribeEvent`；注解里的 modid 与 toml 一致 |
| 处理函数 | `onPlayerLoggedOut` / `onPlayerLoggedIn` 的描述符正确 |
| 依赖隔离 | 无 `immersive_aircraft/` 类引用；包名过滤字符串存在 |

其中「注解」那一组是抓**静默失效**的：漏写 `@SubscribeEvent`，或者
`@EventBusSubscriber(modid=...)` 和 toml 的 modId 不一致，都不会报错、不会崩，
只是这个 mod 从此什么都不做 —— 这类问题必须靠校验挡。

---

## 6. 已知边界（如实说明）

1. **只管 immersive_aircraft 的载具。** 船、矿车、马这些的行为保持原样（原版那套
   载具随存档机制对它们同样存在，但不属于这次要修的问题）。
2. **装之前就已经写进 playerdata 的 `RootVehicle` 会让"装机后的第一次登录"仍然复制一次。**
   之后每次登出都是整份重写 playerdata，所以这条旧数据会被自然清掉，不会再犯。
   建议：装好后进一次游戏，**骑着船退出一次**，再进去确认只有一条船。
3. **已经复制出去的船和物资不会回收。** 这个 mod 只堵住新的复制。
4. **服务端侧生效**：两个事件都在服务端触发，所以联机里由服务器/主机装即可；
   单人存档同样有效（集成服务器）。
5. 需要 Java 21 与 NeoForge 21.1.x。与 `immersive_optimization` 没有交互（它只决定
   哪些实体 tick，不创建实体）。

---

## 7. 安装

把 `artifacts\aircraftdupefix-1.0.0.jar` 放进 `mods` 文件夹即可，客户端和服务端都要放
（联机时服务端是必须的）。

在游戏里的验证步骤：

1. 骑上飞艇，装点东西到货舱里，飞到离地较高的地方；
2. 退出到主菜单；
3. 重进 —— 应该仍然在船上（本 mod 把你放回去），而不是掉下去；
4. 反复几次，船不会变多；
5. `logs\latest.log` 里 `aircraftdupefix` 应出现在 mod 列表中，且没有相关报错。

---

## 8. 文件

```
aircraftdupefix\
  src\com\aircraftdupefix\AircraftDupeFix.java     源码（含逐段中文注释）
  resources\META-INF\neoforge.mods.toml            mod 描述
  build.ps1                                        编译 + 打包
  verify.ps1                                       产物校验
  artifacts\aircraftdupefix-1.0.0.jar              成品
  build-meta.txt                                   本次构建用的类路径记录
```

两个 `.ps1` 刻意写成纯 ASCII：Windows PowerShell 5.1 会误解码无 BOM 的 UTF-8 脚本，
而工程路径本身含中文，所以脚本用 `$PSScriptRoot` 反推路径，不硬编码。
