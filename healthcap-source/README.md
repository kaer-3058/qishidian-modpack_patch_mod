# Entity Health Cap

适用于 Minecraft 1.21.1 和 NeoForge 21.1。模组把原版 `generic.max_health` 属性的硬上限从 1024 提高到 16000000。它不修改任何实体的默认生命值，不再读取实体类型配置，也不使用 Mixin。

## 安装

使用 Java 21 运行 `gradlew.bat build`。生成的文件是 `build/libs/healthcap-1.1.0.jar`。将它放进客户端和服务器的 `mods` 文件夹。

## 修改单个实体

在开启作弊的世界中，先召唤一个僵尸，再运行：

```mcfunction
/summon minecraft:zombie ~ ~ ~
/attribute @e[type=minecraft:zombie,sort=nearest,limit=1] minecraft:generic.max_health base set 4096
/attribute @e[type=minecraft:zombie,sort=nearest,limit=1] minecraft:generic.max_health get
/attribute @e[type=minecraft:zombie,sort=nearest,limit=1] minecraft:generic.max_health base get
```

后两条命令应显示 4096。`/attribute` 修改原版属性的基础值。属性修饰符、伤害和治疗继续使用原版机制。命令不会自动补满当前生命值。原版会将基础值保存在该实体的数据中，所以保存并重进世界后仍会保留。实体死亡并重新生成后，新实体仍使用原版默认值。

要一次修改多个实体，可用原版 `execute` 命令：

```mcfunction
/execute as @e[type=minecraft:zombie] run attribute @s minecraft:generic.max_health base set 4096
```

要将一个普通僵尸改回默认值，可将基础值设为 `20`。其他实体的原版默认值可能不同，请先用 `base get` 查看。玩家也能用 `/attribute @s minecraft:generic.max_health base set 4096` 修改，但死亡重生会创建新的玩家实体。

## 实现范围

Access Transformer 仅开放 `RangedAttribute.maxValue` 字段。模组在 NeoForge 的属性修改事件中提高共享 `MAX_HEALTH` 属性的上限。所有实体因此都允许使用更高的属性值，但默认生命值保持原样。当前生命值使用 `float`，数值很大时会失去精度。
