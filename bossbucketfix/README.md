# bossbucketfix —— 灾变 Boss「面板越高伤害越低」修复

修复内容：**技能专精（GokiSkills）的剑术会用「取消原版 `hurt` + 自己重放一次」的方式改伤害数值，
而重放会再走一遍灾变 Boss 覆写的 `hurt`，导致单次伤害桶（`damageBucket`）被记账两次。**

一次挥砍记账两次的直接后果（`damage_cap = 22`）：

```
第一遍（随后被整次取消）：bucket += A
第二遍（真正结算）      ：newBucket = A + A
                          A * 2 <= 22  -> amount = A          （看起来正常）
                          11 < A < 22  -> amount = 22 - A     ← 面板越高伤害越低
                          A >= 22      -> amount = 0.1f       ← 血条纹丝不动
```

所以用户实测会被这个模型**逐个精确复现**（加成 0.375，即剑术约 14~15 级；桶残留 = 上一次挥砍还没散完的部分）：

| 桶残留 | 面板 | 修复前实际 | 用户报告 |
|---|---|---|---|
| 0 | 8 | **11.0** | 11 |
| 3 | 11 | **8.0** | 8 |
| 5 | 13 | **4.0** | 4 |
| 0 | 24 | **0.1** | 0（血条不动） |

同一组输入在修复后：`11.0 / 15.1 / 17.0 / 22.0` —— 单调递增且不超过 `damage_cap`。

## 根因（含证据）

1. `GokiSkills` 的 `io.github.tigercrl.gokiskills.mixin.LivingEntityMixin#hurt`（HEAD，cancellable）：
   `fencing` 开启且主手在 `#minecraft:swords` 时 `amount *= (1 + bonus)`，随后
   `gokiskills$hurtEntity(self, source, amount)` → `entity.hurt(source, amount)`（**虚方法重放**），
   再 `cir.setReturnValue(true)` 取消本次调用。重放同样命中灾变的 `hurt` 覆写。
   - 匠魂的 `dagger/sword/cleaver/swasher` 也在 `#minecraft:swords` 里
     （`TinkersConstruct-1.21.1-3.13.1.jar: data/minecraft/tags/item/swords.json`），
     所以当初用劈刀测也是同样现象 —— **根因与匠魂无关**，原版下界合金剑一样触发。
2. `灾变 结构重置 v.04.jar` 的
   `com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster#hurt`
   字节码偏移：`127 oldBucket` / `144 bucket+amount` / `151 DamageCap()` /
   `165 cap-bucket` / `187 bucket=cap` / `193 0.1f` / `201..206 bucket+=amount` / `243 回滚`。
   `com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.IABoss_monster#hurt`
   是同一份逻辑的复制（各自持有 `private float damageBucket`）。
3. 因为 GokiSkills 是通过 `setReturnValue(true)` 取消的，灾变那句
   `if (!flag) damageBucket = oldBucket;` 的回滚**不会执行**，第一遍记进桶的量就留下了。
4. 技能等级存在玩家 NBT `GokiSkills.levels.*`（老档 `fencing=25`、新档全 0），
   属于玩家持久数据 —— 摘饰品、清背包、死亡都不会清，所以"新档正常、老档异常"。

## 修复原理

`BucketGuard` 记录「本次挥砍开始前的桶值」，并在检测到**重入**（同一次挥砍里嵌进来的第二遍 hurt）时
先把桶恢复回挥砍前，再让第二遍（真正结算的那遍）正常记账：

```java
@Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("HEAD"))
// 第一遍：记下挥砍前的桶值
// 重入遍：bucket = 挥砍前的桶值（撤销第一遍的临时记账），然后用技能加成后的数值正常记账
```

为什么不是"干脆跳过第一遍的记账"：**没有重放时（绝大多数怪物、或玩家没点剑术），
第一遍就是唯一一遍** —— 跳过它会让 `damage_cap`/DPS 上限全局失效。
本方案在无重放时不会进入恢复分支，行为与原版**逐位一致**（仿真里 20 组组合全部一致）。

覆盖范围：`LLibrary_Boss_Monster`（先驱者、末影守卫、末影傀儡、伊格尼斯、紫水晶蟹、利维坦…）
与 `IABoss_monster`（下界合金巨兽、斯库拉、玛勒迪克图斯、上古残魂）。

## 验证

`build/sim-test-report.txt`：把灾变那段记账 + GokiSkills 的重放一起建模，跑出的结论

- 修复前：`8→11 / 11→11 / 13→9 / 16→6 / 22→0.1 / 24→0.1`（反比例 + 归零）
- 修复后：等于「只结算一遍、用加成后数值」的理论值（`8→11 / 11→15.125 / 13→17.875 / 16→22 / 22→22 / 24→22`）
- `bonus=0`（无重放）20 组组合修复前后**完全一致**
- 桶有残留时仍然尊重 `damage_cap`（额度用尽依旧只给 0.1）

游戏内验证：进老档打先驱者，看 `logs/latest.log` 里

```
[bossbucketfix] loaded: Cataclysm boss damage-bucket reentrancy fix is active
[bossbucketfix] replay detected #1 on LLibrary_Boss_Monster: bucket 22.0 -> 0.0 (outer charge discarded), this pass amount=13.2
```

第二条说明重入确实被拦下并撤销了外层记账（最多打 5 条，之后静默）。
伤害表现：面板 8 → 约 11（剑术加成生效），面板 11 → 约 15，面板 24 → 满 22；**不再出现面板越高伤害越低、也不再出现 0 伤害**。
如果日志里出现 `Mixin apply failed`，把这个文件贴出来即可。

## 安装 / 卸载

安装：把 `artifacts/bossbucketfix-1.0.0.jar` 放进 `.minecraft/versions/起始点/mods/`，重启游戏。
卸载：删掉那个 jar 即可（不改动任何存档、配置或其它 mod）。

## 已知局限

- 只修灾变的伤害桶。若有**别的 mod**也用「取消 `hurt` + 重放」这种写法，且它在 `hurt` 里也做每击记账，
  那套记账仍会被走两遍（这不是灾变的问题，也不是本 mod 的职责范围）。
- 先驱者自己的 `hurt` 覆写也会被重放一遍，`idleHeadUpdates += 3` 变成 `+6`（纯头部动画表现），
  `destroyBlocksTick = 20` 重复赋值无影响；这些不涉及伤害数值，未改动。
- 不改变 GokiSkills 的任何数值/等级，也不改变灾变的 `damage_cap`/`dps_cap` 语义。
- 依赖灾变 3.32 的字段名 `damageBucket` 与方法签名；`build.ps1` 里带了预检，版本不对会直接报错而不是静默失败。

## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "相关文件\DeepSeek写的mod\bossbucketfix\build.ps1"
```

编译对着**真实运行库**（NeoForge 21.1.249 / MC 1.21.1 / sponge-mixin / 灾变 jar，灾变 jar 按内容特征定位，
不依赖它那个含中文的文件名），产出 `artifacts/bossbucketfix-1.0.0.jar` 与 `build-meta.txt`。
`test/` 下的仿真测试不打进 jar，编译/运行方式见 `BucketGuardSimTest` 的类注释。
