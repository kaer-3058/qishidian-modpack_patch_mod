package com.voidscapefix.mixin;

import java.util.List;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 目的：不让 Voidscape 把它的更新检查地址（https://gh.tamaized.com/Tamaized/Voidscape/update.json）
 * 装进 NeoForge 的 ModInfo 里。
 *
 * Voidscape 的 ModUpdateURLInterceptor 构造函数里做的是：
 *     ModList.get().getModFileById("voidscape").getMods().forEach(modInfo -> { ... 写入 updateJSONURL ... });
 * 也就是说，只要那句 forEach 不执行，更新地址就装不进去。
 *
 * 旧版补丁的写法是对构造函数用 cancellable 的 @Inject，而 Mixin 明确禁止对构造函数做可取消注入
 * （InvalidInjectionException: Found cancellable @Inject targetting a constructor），
 * 所以注入一直失败、补丁从来没生效。
 *
 * 现在改成把构造函数里的 List.forEach(...) 重定向成空操作：不执行 forEach，自然不会写入更新地址。
 * 用 targets = "..." 字符串而不是直接引用类，这样 Voidscape 不在时也不会因为这个补丁报错。
 */
@Mixin(targets = "tamaized.voidscape.network.ModUpdateURLInterceptor")
public abstract class ModUpdateURLInterceptorMixin {

    @Redirect(
            method = "<init>()V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"
            )
    )
    private static void voidscapefix$skipInstallingUpdateUrl(List<?> mods, Consumer<?> action) {
        // 故意留空：不遍历模组列表，也就不会把 update.json 地址写进 ModInfo
    }
}
