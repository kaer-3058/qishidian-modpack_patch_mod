package com.voidscapefix.mixin;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import tamaized.voidscape.network.DonatorHandler;

/**
 * 掐掉 Voidscape 的捐赠者名单请求，并保证没有任何线程会阻塞在 join() 上。
 *
 * <p>原始字节码（1.21.1-1.9.588）：
 * <pre>
 *   private List&lt;UUID&gt; run() {                       // 真正联网的方法
 *       LOGGER.info("Loading donor data");
 *       ... URI_DONATORS.toURL().openConnection().getInputStream() ...  // 卡在这里
 *       catch (...) { LOGGER.error(...); return Collections.emptyList(); }
 *   }
 *   public List&lt;UUID&gt; getDonators() { return this.donators.join(); }     // 阻塞点
 *   public boolean isDonator(UUID id) { return this.donators.join().contains(id); }  // 阻塞点
 * </pre>
 *
 * <p>{@code run()} 的异常分支本来就返回空列表，所以"直接返回空列表"与
 * "请求失败"在语义上完全一致 —— 只是这次连请求都不发，且立刻返回。
 */
@Mixin(DonatorHandler.class)
public abstract class DonatorHandlerMixin {

    /**
     * 不再发起 HTTP 请求。
     *
     * <p>返回空列表让 {@code CompletableFuture} 立即正常完成，
     * 而不是永远 pending（后者会让 join() 死等）。
     */
    @Inject(
            method = "run()Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void voidscapefix$blockDonatorRequest(CallbackInfoReturnable<List<UUID>> cir) {
        cir.setReturnValue(Collections.emptyList());
    }

    /**
     * 双保险：任何调用者都直接拿到空名单，永远不会执行 join()。
     *
     * <p>这样即使将来 {@code run()} 的实现变了，渲染线程也不会再被阻塞。
     */
    @Inject(
            method = "getDonators()Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void voidscapefix$neverJoin(CallbackInfoReturnable<List<UUID>> cir) {
        cir.setReturnValue(Collections.emptyList());
    }
}
