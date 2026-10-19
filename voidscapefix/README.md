# voidscapefix 修复记录（2026-10-09）

## 问题

原补丁 `虚空工艺补丁 voidscapefix-1.0.0.jar`（3796 字节）里的 `ModUpdateURLInterceptorMixin`
**从来没有生效过**。日志里每次都有一条：

```
[mixin/] Mixin apply for mod voidscapefix failed voidscapefix.mixins.json:ModUpdateURLInterceptorMixin
org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException:
    Found cancellable @Inject targetting a constructor in injector
    tamaized/voidscape/network/ModUpdateURLInterceptor::voidscapefix$noUpdateUrlInjection
```

原因：Mixin **不允许对构造函数做"可取消"的 `@Inject`**（构造函数没有返回值，取消不了）。
所以这个注入每次都在模组加载阶段失败；NeoForge 只记一条 WARN 然后跳过，游戏照常启动
（所以它跟 2026-10-09 21:55 的那次崩溃无关，那次是 Xaero 世界地图的 OpenGL 1281）。

## 改法

不动构造函数，改成把构造函数里那句 `ModList...getMods().forEach(...)` **重定向成空操作**：

```java
@Mixin(targets = "tamaized.voidscape.network.ModUpdateURLInterceptor")
public abstract class ModUpdateURLInterceptorMixin {
    @Redirect(
        method = "<init>()V",
        at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private static void voidscapefix$skipInstallingUpdateUrl(List<?> mods, Consumer<?> action) {
        // 不遍历 → 不把 update.json 地址写进 NeoForge 的 ModInfo
    }
}
```

* 目标类用**字符串**写法（`targets = "..."`），没有硬引用虚空工艺的类，卸载它也不会报错；
* 该类构造函数只做这一件事（已用 javap 确认），拦掉 forEach 不影响任何其它功能。

## 目录内容

| 路径 | 说明 |
|---|---|
| `src\com\voidscapefix\mixin\ModUpdateURLInterceptorMixin.java` | 修好后的源码 |
| `artifacts\虚空工艺补丁 voidscapefix-1.0.0.jar` | 编译好的修复版（3286 字节） |
| `旧版备份\虚空工艺补丁 voidscapefix-1.0.0.jar` | 原来的失败版本（3796 字节），想还原就覆盖回去 |
| `build.ps1` | 一键重新编译 + 打包 |

已安装位置：`.minecraft\versions\起始点\mods\虚空工艺补丁 voidscapefix-1.0.0.jar`

## 重新编译

```
pwsh -NoProfile -File build.ps1
```

脚本做的事：用 `旧版备份` 里的原始 jar 当骨架解包 → 用 JDK 21 编译 `src` 覆盖进去 →
重新打包到 `artifacts`。

**必须加 `-proc:none`**：Mixin 自带的注解处理器会崩（缺 ASM），编译不需要它。
其余文件（`DonatorHandlerMixin.class`、`voidscapefix.mixins.json`、`neoforge.mods.toml`）保持原样。

## 验证方法

启动一次游戏后看 `logs\debug.log`：

* 不再出现 `Mixin apply for mod voidscapefix failed`；
* 出现 `Mixing ModUpdateURLInterceptorMixin ... into tamaized.voidscape.network.ModUpdateURLInterceptor` 且后面没有失败提示。

## 附注

* 补丁里另一个 mixin（`DonatorHandlerMixin`，挡掉 DonatorHandler 那一下网络读取）本来就是好的，
  真正防"网络卡死"的是它；这次只修了失败的这一个。
* 打包发布用的 `起始点 1.0.1 beta.zip` 里 `overrides/mods/` 装的还是旧版（3796 字节），
  下次打包前记得换成修复版。
