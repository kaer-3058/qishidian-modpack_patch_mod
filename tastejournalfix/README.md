# tastejournalfix —— 修「味蕾乐事」给非食物物品盖戳导致堆叠失效

版本 **1.1.1**

- `1.0.0` 只修堆叠
- `1.1.0` 增加 tooltip 说明 —— **但这一版一悬停带标记的物品就崩游戏**，原因见下面的「1.1.0 的崩溃」
- `1.1.1` 修掉 1.1.0 的崩溃，并新增 `verify.ps1` 自动校验这一类错误

> ⚠️ 手上如果还有 `tastejournalfix-1.1.0.jar`，**删掉它**。

## 结论

**bug 在 `tastejournal` 0.9.10 自己身上**，不在原版、也不在其它 mod。

出事的类是 `dev.tastejournal.data.HomemadeFoodTracker`：

```java
public final class HomemadeFoodTracker {
    private static final String MAKER_KEY = "maker";

    public static void mark(ItemStack stack, Player player) {
        if (!stack.isEmpty()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
                CompoundTag marker = root.contains("tastejournal", 10)
                        ? root.getCompound("tastejournal") : new CompoundTag();
                marker.putUUID(MAKER_KEY, player.getUUID());
                root.put("tastejournal", marker);
            });
        }
    }
    // isMadeBy(stack, player) 反过来读 root.getCompound("tastejournal").getUUID("maker")
}
```

它的目的是标记「这叠食物是玩家自己做的」，好让品鉴记录能区分**自制 / 采集**。它挂在这两个事件上（`dev.tastejournal.event.CommonEvents`）：

```java
@SubscribeEvent
public static void onItemCrafted(PlayerEvent.ItemCraftedEvent e) {
    HomemadeFoodTracker.mark(e.getCrafting(), e.getEntity());   // 合成【任意】物品
}
@SubscribeEvent
public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent e) {
    HomemadeFoodTracker.mark(e.getSmelting(), e.getEntity());   // 烧炼【任意】物品
}
```

**它从来没检查过这个物品是不是食物。** 这就是「不止木板」：木棍、玻璃、铁锭，凡手搓和烧出来的全都中招。

## 三条现象各自的成因

### ① 为什么鼠标单点取出来的木板带 NBT

NeoForge 是从这里发事件的（`ResultSlot.checkTakeAchievements`）：

```java
if (this.removeCount > 0) {
    EventHooks.firePlayerCraftingEvent(this.player, stack, this.craftSlots);
}
this.removeCount = 0;
```

事件携带的就是它拿到的那**一个 `ItemStack` 对象**。而原版鼠标单点走的是 `AbstractContainerMenu.doClick` 的 PICKUP 分支：

```java
Optional optional1 = slot7.tryRemove(j3, Integer.MAX_VALUE, player);
optional1.ifPresent(stack -> {
    this.setCarried(stack);          // 先把这个对象放到鼠标上
    slot7.onTake(player, stack);     // 再把同一个对象交给 onTake → 事件
});
```

**放上鼠标的和传给事件的 literally 是同一个对象** → `mark()` 就地改它 → 鼠标上那叠带上了戳。

### ② 为什么 Shift 点击拿出来的没有

Shift 点击走 `CraftingMenu.quickMoveStack`：

```java
ItemStack slotStack = slot.getItem();
result = slotStack.copy();
if (index == 0) {
    if (!this.moveItemStackTo(slotStack, 10, 46, true)) return ItemStack.EMPTY;  // 先把 slotStack 抽干
    slot.onQuickCraft(slotStack, result);                                        // 传的是已经空的它
}
...
slot.onTake(player, slotStack);          // 还是那个空栈
```

`moveItemStackTo` 是**就地把传入的栈清零**的（`p_38904_.setCount(0)` / `shrink(...)`），搬进背包的是目标槽原有对象或 `split()` 的副本，**都不是这个对象**。于是事件带着一个**已清空的栈**到达 `mark()`，被它自己那句 `if (!stack.isEmpty())` 挡掉了。

**所以「单点有、Shift 没有」纯粹是原版两条路径调用顺序不同造成的意外，不是设计意图。** 合成出口里还没取出来的那叠自然也没有 —— 盖章只发生在取的那一刻。

### ③ 为什么堆叠坏了、为什么没法连续取

1.20.5 之后 `ItemStack.isSameItemSameComponents` 比的是**完整组件集**。一个栈有 `custom_data.tastejournal`、另一个没有，就永远合不到一起。

「没法连续取」也是同一个原因：拿过一次之后，鼠标上那叠**带戳**，合成出口里新出炉的**不带**，原版判定两者不是同一种物品，就不肯往光标上继续并。

## 修法

### 第一道：只让真正的食物/饮品保留这个戳

复刻 tastejournal 自己认的「能吃」的定义（`CommonEvents.isTasteableConsumedItem`）：

```java
public static boolean isCulinary(ItemStack stack) {
    if (stack.isEmpty()) return false;
    if (stack.has(DataComponents.FOOD)) return true;      // 有食物属性
    return stack.getUseAnimation() == UseAnim.DRINK;      // 或者是"喝"的东西
}
```

然后挂在**同样两个事件**上，用 `EventPriority.LOWEST` 排在 tastejournal 自己的处理（默认 NORMAL）之后，把它误加的戳摘掉。

摘的时候只动 `tastejournal` 那一个子标签，`custom_data` 里别的 mod 写的东西一个字节都不碰；本来就没有这个标签的连 `custom_data` 组件对象都不替换（替换本身也会破坏堆叠）。

### 第二道：把留下的标记显示出来

原设计真正的问题不只是漏了个食物判断，而是**它制造了一个玩家看不见、却改变行为的隐藏状态**：物品莫名其妙不堆叠，玩家只能自己猜。作者把标记写进物品是必要的（要回答「这一叠是不是我做的」就得让它跟着物品走），但从来不显示出来，这就成了纯粹的暗坑。

所以加一行 tooltip：

```java
@SubscribeEvent
public static void onTooltip(ItemTooltipEvent event) {
    ItemStack stack = event.getItemStack();
    if (!isMarked(stack)) return;
    event.getToolTip().add(Component.literal(isCulinary(stack) ? LINE_HOMEMADE : LINE_LEGACY));
}
```

| 情况 | 显示 |
|---|---|
| 自制食物 / 饮品 | `自制 · 与普通食物不堆叠` |
| 修复前产出的旧物品（非食物却带戳） | `味蕾乐事旧标记 · 无法与同类堆叠` |

两种提示都是**纯显示层**：不写组件、不改组件，所以**不会让堆叠问题变得更糟**。这是这个方案的关键 —— 一个只是"告诉玩家发生了什么"的改动，不引入任何新的物品状态。

（原 mod 只在它自己那个日志本物品 `TasteJournalItem` 上有 tooltip，食物身上一个字的提示都没有；`assets/tastejournal/lang/` 里也没有任何与「自制」相关的键。）

### 为什么不用 mixin

更彻底的做法是 mixin 进 `HomemadeFoodTracker.mark` 直接拦掉，能覆盖所有调用点。这里**故意没这么做**，因为：

- mixin 写错会导致**游戏根本起不来**，而事件方式最坏情况只是修复不生效（bug 照旧，不会崩）；
- `mark()` 在 0.9.10 里只有那两个调用点（已用 CFR 反编译确认），事件方式覆盖完整；
- 事件方式只用公开 API，且能打日志，**生效与否一眼可查**。

代价：如果以后升级 tastejournal，作者从别的地方调用了 `mark()`，需要重新检查本修复是否还覆盖得住。

## 测试

`test/` 下有一套**有真实行为**的假 MC 类，拿 `build/tastejournalfix-1.1.1.jar` 里真实的 `TasteJournalFix.class` 直接跑，**29/29 通过**。

```
---- 一、摘戳（非食物）----
  [OK]   木板(单点路径): custom_data 被整个摘掉
  [OK]   铁锭(烧炼路径): custom_data 被整个摘掉
  [OK]   面包(有 FOOD 组件): tastejournal 戳保留
  [OK]   绿茶(DRINK 动画): tastejournal 戳保留
  [OK]   木板+其它mod数据: tastejournal 被摘掉
  [OK]   木板+其它mod数据: othermod 原样保留
  [OK]   木板无戳: custom_data 对象未被替换
  [OK]   空栈: 未抛异常

---- 二、tooltip（显示层）----
  [OK]   自制食物: tooltip 加了 1 行
  [OK]   自制食物: 该行说明'自制'
  [OK]   自制食物: 该行说明'不堆叠'
  [OK]   旧遗留物品: tooltip 加了 1 行
  [OK]   旧遗留物品: 该行是旧标记警示
  [OK]   普通物品: tooltip 未改动
  [OK]   修复后新合成的木板: tooltip 无提示
  [OK]   tooltip: 未替换 custom_data 组件对象
  [OK]   tooltip: 未摘掉食物身上的戳
  [OK]   第 1/2/3 次渲染: 恰好 1 行
  [OK]   已有其它行: 追加而不是覆盖
  [OK]   已有其它行: 别人的行还在

---- 三、判据 ----
  [OK]   isCulinary / isMarked 共 7 项

PASS 29 / FAIL 0
```

复现：

```powershell
$p = "相关文件\DeepSeek写的mod\tastejournalfix"
$jdk = "C:\Users\kaer1\AppData\Roaming\.minecraft\runtime\java-runtime-delta\bin"

# 编译 mod
& "$jdk\javac.exe" -encoding UTF-8 --release 21 -nowarn -proc:none -d "$p\build\classes" (Get-ChildItem "$p\src" -Recurse -Filter *.java).FullName

# 类型种类校验（这一步才能在进游戏前抓出 1.1.0 那种崩法）
& "$p\verify.ps1"

# 逻辑测试
& "$jdk\javac.exe" -encoding UTF-8 --release 21 -nowarn -proc:none -cp "$p\build\tastejournalfix-1.1.1.jar" -d "$p\build\testclasses" (Get-ChildItem "$p\test" -Recurse -Filter *.java).FullName
& "$jdk\java.exe" -cp "$p\build\testclasses;$p\build\tastejournalfix-1.1.1.jar" com.tastejournalfix.FixTest
```

### 核实过的运行时契约（不是猜的）

| 项 | 结论 | 依据 |
|---|---|---|
| `PlayerEvent$ItemCraftedEvent.getCrafting()` | 返回 `ItemStack` | javap 实测 |
| `PlayerEvent$ItemSmeltedEvent.getSmelting()` | 返回 `ItemStack` | javap 实测 |
| `@EventBusSubscriber.bus()` 默认值 | `Bus.GAME` | `javap -v` 的 AnnotationDefault |
| `ItemStack.get/has` 的真实归属 | `DataComponentHolder` 的 default 方法 | javap 实测 |
| `Component.literal` 返回类型 | `MutableComponent`（返回类型参与方法描述符） | javap 实测 |
| **`Component` 是 interface 还是 class** | **interface**（`MutableComponent` 才是 class） | javap 实测 —— **1.1.0 就是死在这一格** |
| `CustomData.contains(String)` | 存在，可免去每帧 `copyTag()` | javap 实测 |
| tooltip 的 list 是否每次新建 | 是，游戏内不会累积重复行 | NeoForge patch 版 `ItemStack.getTooltipLines` 第 657/702 行 |

⚠️ 加粗那一行是补的。1.1.0 发布前我确实做了 javap，但**我用 `Select-String` 把输出过滤得只剩方法行，从来没看到声明行上的 `public interface`**，于是把 `Component` 写成 `class`，还把它标进了"已核实"。教训：**查类型种类必须看声明行，只看方法签名不算核实。**

## 1.1.0 的崩溃

现象（游戏里一悬停带 `tastejournal` 标记的物品就崩，崩在 `CraftingScreen.render` → `renderTooltip`）：

```
java.lang.IncompatibleClassChangeError: Method
'net.minecraft.network.chat.MutableComponent net.minecraft.network.chat.Component.literal(java.lang.String)'
must be InterfaceMethodref constant
    at tastejournalfix@1.1.0/com.tastejournalfix.TasteJournalFix.onTooltip(TasteJournalFix.java:107)
```

原因：`net.minecraft.network.chat.Component` 的**真身是 interface**：

```java
public interface net.minecraft.network.chat.Component
        extends com.mojang.brigadier.Message, net.minecraft.network.chat.FormattedText
```

而我的编译期桩类把它写成了 `class`。javac 于是按桩类选了 `invokevirtual` + `Methodref` 常量；JVM 要求接口必须用 `InterfaceMethodref`，链接期直接抛错。

修法：桩类改成 `interface`，`MutableComponent` 改成 `implements Component`。改完编译出的是：

```
31: invokestatic  #85  // InterfaceMethod net/minecraft/network/chat/Component.literal:(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;
```

**这个错误编译期不报，逻辑测试也抓不到** —— 假实现和桩类是一起写错的，测试只在自洽的假世界里跑。所以补了下面这个校验器。

## verify.ps1 —— 自动抓"桩类形状与真实 API 不一致"

原理：`javap -c` 会把每条方法引用所用常量池条目的种类打出来（`// Method` 还是 `// InterfaceMethod`），而 JVM 的规则是 **owner 是接口 ⇔ 必须用 InterfaceMethodref**。于是拿真实 jar 逐个查每个 owner 的种类，就能对出矛盾。

在**修好的** jar 上：

```
[OK]   无桩类泄漏（jar 内只有 com/ 与资源）
被引用的类型 owner 共 11 个
[OK]    java/util/List                                              interface
[OK]    net/minecraft/network/chat/Component                        interface
[OK]    net/minecraft/world/item/ItemStack                          class
[OK]    net/neoforged/neoforge/event/entity/player/ItemTooltipEvent class
[OK]    net/neoforged/neoforge/event/entity/player/PlayerEvent$ItemCraftedEvent class
...
校验通过：11 个引用的种类全部与真实 API 一致（0 个无法判定）
```

在 **1.1.0 那个坏 jar** 上跑，它会直接把凶手点出来：

```
[FAIL]  net/minecraft/network/chat/Component
        真实是 interface，但代码用的是 Methodref
        -> 运行时会 IncompatibleClassChangeError: must be InterfaceMethodref constant
        修法：把桩类改成 interface
```

（脚本本身必须存成 **UTF-8 with BOM**：PowerShell 5.1 会把无 BOM 的 `.ps1` 按系统 ANSI/GBK 读，中文注释会直接引发语法错误。）

## 安装

已装进两个实例（坏掉的 1.1.0 已移除，旧的 1.0.0 也已删除）：

- `.minecraft\versions\起始点\mods\tastejournalfix-1.1.1.jar`
- `.minecraft\versions\起始点2\mods\tastejournalfix-1.1.1.jar`

## 怎么确认生效

进游戏合成任意木板/木棍，看 `latest.log`：

```
[tastejournalfix] stripped the tastejournal tag from a non-food item - the fix is active. First hit: 1 oak_planks
```

只打一次，不刷屏。按 F3+H 打开高级提示框，鼠标上的木板应该不再有 `custom_data`。

食物身上应该多出一行 `自制 · 与普通食物不堆叠`。

## 已知边界

- **修复只对以后新合成的物品生效。** 你存档里**已经做出来的**带戳木板/木棍不会自动变干净 —— 它们的 `custom_data` 里已经写进去了，没有任何安全的批量清理办法（要扫遍所有容器）。把鼠标移上去会显示 `味蕾乐事旧标记 · 无法与同类堆叠`，方便你找出来扔掉。
- **Shift 点击那条路径本来就是没有戳的**，本修复只是让单点这条路也变得干净 —— 两条路现在一致了。
- **食物仍然会有「自制 / 非自制」之分**：自制食物和捡来的同种食物依旧不能堆叠。这是 tastejournal 这个功能设计上固有的代价（标记必须跟着物品走），不是 bug。现在至少玩家能看见原因了。
- 真正的食物和饮品**仍然会被盖章**，功能完整保留。
- 想彻底关掉盖章（连食物也不盖）的话，把 `isCulinary` 直接改成 `return true;` 编译即可 —— 代价是品鉴记录里再也分不出自制。
- 不想要旧物品那条警示的话，删掉 `LINE_LEGACY` 那个分支即可。

## 回退

删掉那两个 `tastejournalfix-1.1.1.jar` 即可，不留任何存档 / 配置痕迹。

## 建议给作者提的 issue

> `HomemadeFoodTracker.mark()` 挂在 `ItemCraftedEvent` / `ItemSmeltedEvent` 上却没有食物判断，会给所有合成/烧炼产物写 `custom_data.tastejournal`。因为 1.20.5+ 按完整组件集比较堆叠，这些物品再也无法与未盖章的同类堆叠；又因为 NeoForge 的 `ItemCraftedEvent` 只在鼠标单点路径上携带真实取得的栈（`CraftingMenu.quickMoveStack` 里 `moveItemStackTo` 会先把该栈清零，事件拿到的是空栈），同一个合成会因点击方式不同而产出带戳或不带戳的物品。
>
> 两个建议：
> 1. 在 `mark()` 里加 `stack.has(DataComponents.FOOD)` 判断，并改用能拿到真实产出物品的挂载点；
> 2. 给带标记的物品加一行 tooltip 说明它是自制的、以及为什么不与普通食物堆叠 —— 标记会影响堆叠行为，玩家就应当看得见它。目前这个标记是完全隐藏的，玩家只能自己猜为什么东西合不到一起。

## 来源

- 反编译目标：`mods\tastejournal-neoforge-1.21.1-latest.jar`（modId `tastejournal`，版本 `0.9.10`，无 mixin 配置）
- 反编译工具：CFR 0.152
- 原版参照：`libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar`
- NeoForge 参照：`libraries\net\neoforged\neoforge\21.1.249\neoforge-21.1.249-{client,universal}.jar`、`libraries\net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar`
