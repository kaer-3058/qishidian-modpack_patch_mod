# createpkgfix — 修复「匠魂武器打机械动力包裹导致栈溢出崩服」

**版本**：1.0.0　|　**产物**：`artifacts\createpkgfix-1.0.0.jar`（3,402 字节）
**适用**：Minecraft 1.21.1 / NeoForge 21.1.249 / Create 6.0.10 / 匠魂 3.13.1（客户端 + 服务端同一个 jar）

---

## 一、崩溃现象

服务端 `crash-2026-10-06_13.06.56-server.txt`：

```
Description: Exception in server tick loop
java.lang.StackOverflowError

  at CommonHooks.onPlayerAttackTarget            (NeoForge)
  at PackageEntity.hurt                          (Create, PackageEntity.java:303)
  at ToolAttackUtil.performAttack                (匠魂, ToolAttackUtil.java:270)
  at ToolAttackUtil.attackEntity                 (匠魂, :178)
  at EntityInteractionModifierHook.leftClickEntity (匠魂, :140)
  at ModifiableItem.onLeftClickEntity            (匠魂, ModifiableItem.java:266)
  at CommonHooks.onPlayerAttackTarget            (NeoForge, :774)   ← 回到起点
  …（崩溃报告里这段循环出现 170 次后爆栈）
```

触发动作：**玩家用匠魂的可改造武器（左键）打机械动力的快递包裹（PackageEntity）**。

## 二、根因：两边各自合理，组合起来无限递归

1. Create 官方代码在 `PackageEntity.hurt()` 里（第 303 行附近）又调了一次 NeoForge 的 hook：

   ```java
   // Create: PackageEntity.hurt
   if (source.getEntity() instanceof Player player) {
       CommonHooks.onPlayerAttackTarget(player, this);   // ← 第 303 行
   }
   ```

2. 这个 hook 会「触发 `AttackEntityEvent` + 回调 `Item.onLeftClickEntity`」：

   ```java
   // NeoForge: CommonHooks.onPlayerAttackTarget
   if (AttackEntityEvent.BUS.post(new AttackEntityEvent(player, target))) return false;  // :771
   ItemStack stack = player.getMainHandItem();
   return stack.getItem().onLeftClickEntity(stack, player, target);                     // :774
   ```

3. 匠魂的可改造武器**正是在 `onLeftClickEntity` 里自己结算伤害**：

   ```java
   // 匠魂: ModifiableItem.onLeftClickEntity → EntityInteractionModifierHook.leftClickEntity
   //        → ToolAttackUtil.attackEntity → ToolAttackUtil.performAttack
   //        → target.hurt(damageSource, damage)        ← 又回到第 1 步
   ```

于是形成闭环：

```
attack → onLeftClickEntity(匠魂) → hurt → onPlayerAttackTarget → onLeftClickEntity(匠魂) → hurt → …
```

**为什么普通武器不会炸**：原版 `Item.onLeftClickEntity` 默认返回 `false` 且不造成伤害，
所以 hook 回调它不会再触发 `hurt`，链条到此为止。只有「在 `onLeftClickEntity` 里真正
结算伤害」的 mod 武器（匠魂，以及少数同类 mod）才会踩中这个死循环。

> 已确认本机 Create 是**官方原版代码**（"改 v.4" 只是资源包/数据包层面的改动，没有改代码），
> 所以这是 **Create 6.0.10 × 匠魂 3.13.1 的官方冲突**，不是改版引起的。

## 三、修复：给 `PackageEntity.hurt` 加「同一实体不可重入」保护

```java
@Mixin(PackageEntity.class)
public abstract class PackageEntityMixin {

    @Unique
    private boolean createpkgfix$inHurt;

    @WrapMethod(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", require = 1)
    private boolean createpkgfix$guardReentry(DamageSource source, float amount, Operation<Boolean> original) {
        if (this.createpkgfix$inHurt) {
            return false;                 // 重入的那次 hurt：丢弃，打断递归
        }
        this.createpkgfix$inHurt = true;
        try {
            return original.call(source, amount);   // 最外层正常执行（伤害结算一次）
        } finally {
            this.createpkgfix$inHurt = false;       // 异常也不会残留标记
        }
    }
}
```

**语义说明**：

- 最外层那次 `hurt` 完全照常执行 —— 伤害、掉落、包裹破损逻辑都不变。
- 递归进来的那次 `hurt`（本来就是 Create 的 hook 重复触发的多余攻击）直接返回 `false`，
  链子在这里断掉 → 不再爆栈，包裹最终**只承受一次伤害**（与普通武器攻击一致）。
- 用 `try/finally` 保证标记一定被清除，即使 `hurt` 抛异常也不会让包裹变成"无敌"。
- **不改动任何伤害数值**，也不触碰 Create 或匠魂的其他逻辑。
- 用的是 MixinExtras 的 `@WrapMethod`（0.5.3，NeoForge 21.1.249 已内置，无需额外依赖）。

## 四、验证状态

构建脚本自带三轮校验，全部通过：

| 阶段 | 检查内容 | 结果 |
|---|---|---|
| preflight | `javap` 确认 Create 的 `PackageEntity.hurt(DamageSource,float)Z` 存在且**仍然调用** `CommonHooks.onPlayerAttackTarget` | ✓ |
| 编译 | 对着真实 jar（Minecraft srg / NeoForge 21.1.249 / Create 6.0.10 / Sponge Mixin / MixinExtras 0.5.3 共 9 个）编译，javac 21.0.7 | ✓ |
| postflight | 反编译产物确认 `@Mixin` 指向 `com.simibubi.create.content.logistics.box.PackageEntity`、`@WrapMethod(method=["hurt(...)Z"], require=1)`、`@Unique` 守卫字段齐全；jar 内没有打包任何 MC / Create 的类 | ✓ |

`require = 1`：万一将来 Create 改了这个方法签名，会在启动时**明确报错**，而不是静默失效。
另外 preflight 里加了一条：如果哪天 Create 自己不再调用那个 hook，会打印警告提示这个补丁已多余。

## 五、安装与卸载

- **客户端**：已放入 `.minecraft\versions\起始点\mods\createpkgfix-1.0.0.jar`
- **服务端**：把同一个 jar 上传到服务器 `mods` 目录（`/data/mods`）—— 崩溃发生在服务端，
  **两边都要装**。
- 也可以直接用已备好的上传目录：`相关文件\服务端要上传的mod\`（含本补丁与之前的 `entitysectionfix`）
- **回滚**：删掉这个 jar 即可，不改任何原 mod 文件、不生成配置。
- **兼容性**：只包了 Create 的一个方法，与匠魂、其他 Create 附属、Sable 等均无冲突。

## 六、临时规避（未装补丁时）

**别用匠魂武器左键打快递包裹**（含地上掉的、传送带/蛙港上正在运输的包裹）。
用原版武器或其他 mod 武器打不会崩。

## 七、建议上报上游

这是两个 mod 的接口用法冲突，值得报给任意一方（任一方改动都能根治）：

- **Create**：`PackageEntity.hurt` 内部调用 `CommonHooks.onPlayerAttackTarget` 会让
  「在 `onLeftClickEntity` 里结算伤害的物品」把 `hurt` 重入一次。建议 Create 加一层重入保护，
  或改用只触发事件、不回调物品的方式。
- **匠魂**：`ToolAttackUtil.performAttack` 直接调 `target.hurt`，与任何「在 hurt 里再触发攻击回调」
  的目标实体都会互相递归。建议在 `ToolAttackUtil` 侧加一个"本次攻击已在结算"的标记。

## 八、目录结构

```
createpkgfix\
  build.ps1                                  # 构建 + 三轮校验（ASCII-only，PS 5.1 安全）
  build-meta.txt                             # 构建时间 / javac / classpath 记录
  lib\mixinextras-neoforge-0.5.3.jar         # 从 NeoForge 内置 jarjar 提取，供编译使用
  src\com\createpkgfix\
    CreatePkgFix.java                        # @Mod 入口，只打一行日志
    mixin\PackageEntityMixin.java            # 重入保护
  resources\
    META-INF\neoforge.mods.toml
    createpkgfix.mixins.json
  artifacts\createpkgfix-1.0.0.jar
```

重新构建：`pwsh -NoProfile -File 相关文件\DeepSeek写的mod\createpkgfix\build.ps1`

## 九、附带观察（与本次崩溃无关）

本次日志里还有大量 `byepregen` 的警告（非致命，只刷屏）：

```
[Worker-Main-19/WARN] [NoiseChunk]: Disabling biome density column evaluators for one NoiseChunk
java.lang.IllegalStateException: Cannot bind an unwrapped density subtree containing Interpolated:
    net.minecraft.world.level.levelgen.DensityFunctions$Marker
  at com.moepus.byepregen...BiomeColumnTemplates.bind
```

这是世界生成 mod `byepregen`（1.1.2.5）与当前地形 datapack 的密度函数不兼容导致的降级警告，
不会崩服，但会让出生点/新区域的生物群系按退化模式生成。若你在意，可以单独看这个问题。
