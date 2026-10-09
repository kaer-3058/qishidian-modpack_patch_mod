package com.voidscapefix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import tamaized.voidscape.network.ModUpdateURLInterceptor;

/**
 * 让 Voidscape 不再把自己的更新检查地址塞进 NeoForge 的 {@code ModInfo}。
 *
 * <p>原始字节码（1.21.1-1.9.588）构造函数里做两件事：
 * <ol>
 *   <li>反射拿到 {@code ModInfo.updateJSONURL} 字段的 setter（MethodHandle）</li>
 *   <li>对 voidscape 的每个 ModInfo 调用
 *       {@code invokeExact(modInfo, Optional.of(URI.create(
 *       "https://gh.tamaized.com/Tamaized/Voidscape/update.json?m=%s&l=%s&v=%s").toURL()))}</li>
 * </ol>
 *
 * <p>它在 HEAD 直接取消：这个类没有被其它任何类引用，字段保持 null 也不会有人去读，
 * 所以取消构造是安全的。取消后 NeoForge 端不存在 voidscape 的更新检查 URL，
 * 该请求也就不会发生。
 */
@Mixin(ModUpdateURLInterceptor.class)
public abstract class ModUpdateURLInterceptorMixin {

    @Inject(
            method = "<init>()V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void voidscapefix$noUpdateUrlInjection(CallbackInfo ci) {
        ci.cancel();
    }
}
