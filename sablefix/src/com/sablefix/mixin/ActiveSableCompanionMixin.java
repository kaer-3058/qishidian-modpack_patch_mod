package com.sablefix.mixin;

import com.sablefix.SableFix;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 Sable 2.0.5 在退出世界时必崩的 NullPointerException。
 *
 * 【bug 现象】
 *   java.lang.NullPointerException: Cannot invoke
 *     "...EntityMovementExtension.sable$getTrackingSubLevel()" because "entity" is null
 *       at dev.ryanhcode.sable.ActiveSableCompanion.getTrackingSubLevel(ActiveSableCompanion.java:435)
 *       at net.minecraft.client.renderer.GameRenderer.handler$iph000$sable$renderLevel(GameRenderer.java:24319)
 *
 * 【根因】
 *   Sable 的 mixin "entities_turn_with_sub_levels/GameRendererMixin" 在 renderLevel 的 HEAD 处：
 *
 *       LocalPlayer player = this.minecraft.player;
 *       SubLevel standingSubLevel = Sable.HELPER.getTrackingSubLevel(player);   // ← player 没判空
 *       if (standingSubLevel != null && player.getVehicle() == null && ...)     // ← 跑不到这里就炸了
 *
 *   而这里的方法体第一件事就是拿参数去强转：
 *
 *       ((EntityMovementExtension) entity).sable$getTrackingSubLevel()
 *
 *   player == null 时直接 NPE。触发时机是"退出世界"：FastQuit 把客户端从世界断开后
 *   minecraft.player 变成 null，但渲染线程还会再跑一帧 renderLevel。
 *
 * 【修法】
 *   在方法入口拦下 null 并返回 null。调用方自己的 `if (standingSubLevel != null ...)`
 *   会走到 else 分支（只把 sable$lastSubLevel 置空），行为完全无害。
 *
 *   注意：ActiveSableCompanion 里有两个同名同参、只有返回类型不同的方法，
 *   返回 SubLevelAccess 的那个是编译器生成的 bridge，字节码里直接转发给下面这个方法，
 *   所以只需要补这一个。
 *
 *   Sable.HELPER 的静态类型就是 ActiveSableCompanion，调用点命中的正是这个方法。
 *
 * 【为什么用 require = 0】
 *   将来 Sable 改了签名或换了实现，mixin 只会记一条警告而不是把游戏搞崩。
 *   代价是修复会静默失效 —— 那时回来看这个文件即可。
 */
@Mixin(targets = "dev.ryanhcode.sable.ActiveSableCompanion")
public abstract class ActiveSableCompanionMixin {

    @Inject(
            method = "getTrackingSubLevel(Lnet/minecraft/world/entity/Entity;)Ldev/ryanhcode/sable/sublevel/SubLevel;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void sablefix$guardNullEntity(Entity entity, CallbackInfoReturnable<SubLevel> cir) {
        if (entity == null) {
            SableFix.reportOnce();
            cir.setReturnValue(null);
        }
    }
}
