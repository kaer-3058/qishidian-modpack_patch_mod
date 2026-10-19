# TACZ 禁用开火（tacznofire）

**一句话：让 TACZ 的枪开不出火来，并且给被禁的枪挂一行提示"损坏，无法开枪"。
默认只禁 TACZ 自带枪包（54 把：ak47、m4a1、deagle…），你自己装的枪包不受影响。**

- 目标游戏：Minecraft 1.21.1 + NeoForge 21.1.249，TACZ `tacz-neoforge-1.21.1-1.1.8-hotfix-r6`
- 产物：`artifacts\tacznofire-1.0.0.jar`（7,964 字节，SHA256 `8C7E7C8B8B4BC8581E7BBFCD5FD686B5A437A9F825B2325C764CB4C9376A2421`）
- **已经放进** `.minecraft\versions\起始点\mods\`（文件名 `TACZ禁用开火 tacznofire-1.0.0.jar`），
  **重启游戏生效**；启动后在日志里能看到一行 `[禁用开火] 已启用：拦截命名空间 [tacz]…`
- 被禁的枪鼠标移上去，提示框最下面会多一行红色斜体：**损坏，无法开枪**（文本可改，见第 2 节）

---

## 1. 装 / 卸

| 操作 | 做法 |
| --- | --- |
| 装 | 把 `artifacts\tacznofire-1.0.0.jar` 丢进 `.minecraft\versions\起始点\mods\`，重启游戏（服务端就丢进服务端的 mods 目录） |
| 临时关掉 | 改配置文件 `mode`（见下），保存即生效，不用重启 |
| 彻底卸掉 | 删掉 jar 和 `config\tacznofire.txt`，重启就恢复原样 |

## 2. 配置文件 `config\tacznofire.txt`

**启动游戏时**就会自动生成这个文件（就在游戏/服务端根目录的 `config\` 里），
内容如下，五个键都带注释：

```ini
mode = default_pack
block_namespaces = tacz
block_guns =
log_blocked = true
lore_text = 损坏，无法开枪
```

| 键 | 作用 | 常用改法 |
| --- | --- | --- |
| `mode` | `default_pack` = 只禁自带枪包；`all` = 禁所有 TACZ 的枪 | 全部都不能开火 → `mode = all` |
| `block_namespaces` | 要禁的**枪包命名空间**，逗号分隔。枪 id 的命名空间就是枪包的命名空间 | 连你那个 Create_Skin 包一起禁 → `block_namespaces = tacz,1001` |
| `block_guns` | 单独点名要禁的**枪 id**，逗号分隔 | 只禁两三把 → `block_namespaces =` 留空，`block_guns = tacz:ak47,1001:lyr` |
| `log_blocked` | 是否在日志里记录拦截（每种枪只记一次） | 嫌刷屏 → `false` |
| `lore_text` | 被禁的枪在提示框最下面多显示的那行字 | 改文案 → `lore_text = 已报废，扣不动扳机`；不想显示 → `lore_text =`（留空） |

**改完保存即生效**（文件时间一变就重新读），不用重启。

你现在的枪包情况（写配置时对照用）：

- TACZ 自带枪包 `tacz_default_gun` → 命名空间 **`tacz`**，54 把：aa12、ak47、m4a1、deagle、kar98、m107、minigun、rpg7……
- 你自己的第三方包 `Create_Skin` → 命名空间 **`1001`**，5 把：lyr、m249、mk14ebr、mk18、vector9

## 3. 怎么确认生效

1. 拿一把自带枪包的枪（比如 AK47）开枪：**没枪声、没弹道、没后坐力、没开火动画**。
2. **鼠标移到枪上看提示框**：最下面多了一行红色斜体的 **损坏，无法开枪**
   （1001 包的枪不该有这一行）。
3. 日志里会出现一行（每种枪只记一次）：
   `[禁用开火] 拦下 tacz:ak47（SERVER 侧，开火，开火者 某某）`
4. 反向验证"只禁原版"：拿 1001 包的枪（lyr / m249 / …）开枪，默认配置下应该**照常能打**。

枪用创造模式物品栏里 TACZ 那一栏拿就行。

## 3.1 那一行提示是怎么加上去的（为什么比"写进物品"好）

它不是写进枪的 `lore` 数据里，而是在**拼提示框的时候临时加上去**的
（监听的 `ItemTooltipEvent`）。理由：

- 写进物品就等于改存档 —— 世界里每一把已经存在的枪都得重写一遍，还要跟背包同步、
  往客户端广播，纯属白费流量；
- 配置一改（比如把某把枪放出黑名单、或换个文案），物品里的旧字就过时了，还得再刷一遍；
- 临时拼的话，规则是什么样就显示什么样，天然一致，**改配置保存后提示立刻跟着变**。

位置也对得上原版习惯：NeoForge 是在 `ItemStack#getTooltipLines` 的**最后一步**
（就贴在 `areturn` 前面）才发这个事件的，所以往列表里 add 的这一行必然落在提示框
**最下面** —— 正是原版 lore 的位置。这一条也在 verify.ps1 里对着 NeoForge 打过补丁的
`ItemStack` 字节码核对过。

## 4. 它干了什么（为什么不用 Mixin）

TACZ 在开火前会发一个**可取消**的事件，紧接着检查取消状态，被取消就返回
`ShootResult.FORGE_EVENT_CANCEL` 直接退出。这几处都在 TACZ 自己的字节码里逐条看过：

| 路径 | 方法 | 事件 |
| --- | --- | --- |
| 服务端开火 | `com.tacz.guns.entity.shooter.LivingEntityShoot#shoot` | `GunShootEvent` |
| 客户端开火 | `com.tacz.guns.client.gameplay.LocalPlayerShoot#shoot` | `GunShootEvent` |
| 脚本开火（KubeJS 枪械脚本） | `com.tacz.guns.item.ModernKineticGunScriptAPI` | `GunFireEvent` |
| 客户端开火动画 | `LocalPlayerShoot` 里用 `GunFireEvent` 门控 | `GunFireEvent` |

三个地方发的都是 `NeoForge.EVENT_BUS` —— 就是普通游戏事件总线。所以本 mod
**一个 Mixin 都不需要、不改任何人的字节码**：两个普通监听器负责取消开火，
第三个监听器（`ItemTooltipEvent`）负责给被禁的枪加那行提示。

## 5. 行为细节 / 边界

- **子弹会不会被扣**：客户端也装了这个 mod（正常情况）时，事件在客户端就被取消，
  连开火网络包都不发出去，所以子弹、枪声、动画全都没有，一发都不扣。
  如果只有服务端装了（某个玩家客户端没装），服务端对**闭膛枪**是先"上一发进膛"再发事件，
  那名玩家第一次扣扳机可能看到弹匣少一发、弹膛变成已上膛，之后不再减少 —— 但**绝不会打出子弹**
  （开膛枪连这一发都不扣）。
- **刺刀近战不受影响**：那是近战判定，不是开火，本 mod 不碰。
- **拦不住的**：KubeJS 脚本如果绕过 TACZ 的事件、自己直接生成子弹实体；
  别的 mod 自己的枪械系统（本 mod 只认 TACZ 的枪）。
- 玩家手里的枪是从 TACZ 的正规 API（`IGun.getIGunOrNull` → `getGunId`）读 id 的，
  不是去翻 NBT 字段，TACZ 换存档格式也不会失效。

## 6. 目录里都是什么

```
tacznofire\
├─ src\com\tacznofire\TaczNoFire.java        源码（含逐条说明的中文注释）
├─ resources\META-INF\neoforge.mods.toml     模组元数据（modId=tacznofire，依赖 tacz）
├─ verify\RuleTest.java                      规则引擎的功能测试（游戏外真跑）
├─ build.ps1                                 编译打包（对着真实 NeoForge/MC/TACZ jar 编译）
├─ verify.ps1                                验证脚本（38 项检查，见下）
├─ build-meta.txt                            构建记录（时间、javac 版本、用了哪些 jar）
└─ artifacts\tacznofire-1.0.0.jar            产物
```

## 7. 验证记录（2026-10-10，`verify.ps1`：38 项检查全部通过）

- **TACZ 侧（对着真实 TACZ jar 反汇编核对）**：`GunShootEvent` 可取消、
  `ICancellableEvent.setCanceled()` 存在；服务端/客户端/脚本开火三处**都是先发事件、
  紧跟着检查 `isCanceled`**，且都在 `NeoForge.EVENT_BUS` 上（这条要是错了，mod 会静默失效，
  所以专门查了）；`IGun.getGunId(ItemStack)`、`IGun.getIGunOrNull(ItemStack)` 都还在。
- **提示行**：监听器签名与 `ItemTooltipEvent` 吻合、确实往 `getToolTip()` 里加、
  样式确实是斜体+红色、判禁用的是同一套规则；并且对着 **NeoForge 打过补丁的 `ItemStack`**
  核对了事件是在 `getTooltipLines` 最后一步发的（所以这行必然在最下面）。
- **mod 侧**：类文件版本 65（Java 21）、modId 与源码一致、tacz 被声明为必需依赖、
  没有 mixin、有 `@Mod` 入口和 public 无参构造器、两个取消监听器签名与 TACZ 事件吻合；
  另外核对了"生成的默认配置里的文案"和源码里的内置默认文案是同一个字符串。
- **规则引擎**：不是"看代码断言"，而是把编译出来的规则类**真的跑了一遍**，20 条用例
  覆盖默认配置、`mode=all`、追加命名空间、点名枪 id、`lore_text` 的三种情形
  （没写这个键 / 自己写文案 / 留空关闭），以及注释/空格/大写/未知键等写法。
- 附带说明：编译时 javac 会报 **1 条无害警告**（`ChatFormatting` 上的 `@Contract` 注解类
  不在 `.minecraft\libraries` 里，找不到注解类而已），不影响产物。

复现：`pwsh -NoProfile -File build.ps1`（重新编译）→ `pwsh -NoProfile -File verify.ps1`（重新验证）。
