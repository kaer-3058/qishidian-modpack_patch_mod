package com.sablefix.mixin;

import com.sablefix.SableFix;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 Sable 2.0.6 的"在子层级(plot)里被卸载区块就必断线"。
 *
 * 【崩溃现象】（disconnect-2026-10-14_11.53.03 / 11.53.42，两份同一异常）
 *
 *   Description: Packet handling error
 *   java.lang.UnsupportedOperationException: Cannot drop chunks in plot
 *     at ClientChunkCache.handler$jkn000$sable$drop(ClientChunkCache.java:4088)
 *     at ClientChunkCache.drop(ClientChunkCache.java)
 *     at ClientPacketListener.handleForgetLevelChunk(ClientPacketListener.java:781)
 *     at ClientboundForgetLevelChunkPacket.handle(ClientboundForgetLevelChunkPacket.java:27)
 *
 * 【根因】反编译 sable-neoforge-1.21.1-2.0.6.jar 的
 *   dev/ryanhcode/sable/mixin/plot/ClientChunkCacheMixin.drop：
 *
 *       SubLevelContainer c = sable$getPlotContainer();      // 客户端当前所在的子层级
 *       if (c.inBounds(pos)) {
 *           ci.cancel();                                     // ① 已经取消了这次丢弃
 *           throw new UnsupportedOperationException("Cannot drop chunks in plot");   // ② 又抛异常
 *       }
 *
 *   ① 已经保证"这个区块不会被丢掉"了，② 纯属多余。而这个异常抛在**网络包处理线程**里，
 *   于是被主循环当成包处理失败（Packet handling error）→ 直接断开连接。
 *
 *   触发条件：人待在 Sable 子层级（plot）内部时，服务端发来"忘记区块"包
 *   （ClientboundForgetLevelChunkPacket），且该区块位置落在 plot 边界内。
 *   所以进出 Sable 载具 / 站在船上时最容易中招。2.0.6 是 1.21.1 上的最新版，
 *   上游还没修（Modrinth 版本接口核对过），只能自己打补丁。
 *
 * 【修法】只包住 handleForgetLevelChunk 里对 ClientChunkCache.drop 的那一次调用：
 *   吞掉 message 正好是 "Cannot drop chunks in plot" 的 UnsupportedOperationException，
 *   其它异常原样抛出。效果等于 Sable 想要的"别丢这个区块"，但不会断线。
 *
 *   只动这一个调用点，其它地方（ClientLevel 卸载、视距变化等）的 drop 调用完全不受影响。
 *
 * 【为什么用 require = 0】将来 Sable 修了 / Minecraft 改了签名，这里只会记一条警告，
 *   不会把游戏搞崩；代价是修复静默失效 —— 那时回来看这个文件即可。
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    /** Sable 那句异常的字面量，必须一字不差。 */
    private static final String SABLE_PLOT_DROP_MESSAGE = "Cannot drop chunks in plot";

    @Redirect(
            method = "handleForgetLevelChunk(Lnet/minecraft/network/protocol/game/ClientboundForgetLevelChunkPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;drop(Lnet/minecraft/world/level/ChunkPos;)V"
            ),
            require = 0
    )
    private void sablefix$dropChunkSafely(ClientChunkCache cache, ChunkPos pos) {
        try {
            cache.drop(pos);
        } catch (UnsupportedOperationException e) {
            if (!SABLE_PLOT_DROP_MESSAGE.equals(e.getMessage())) {
                throw e;
            }
            // Sable 已经 cancel 过这次丢弃了，这里什么都不用做。
            SableFix.reportPlotDropOnce();
        }
    }
}
