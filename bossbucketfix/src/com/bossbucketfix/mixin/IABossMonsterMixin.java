package com.bossbucketfix.mixin;

import com.bossbucketfix.BucketGuard;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.IABoss_monster;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 与 {@link LLibraryBossMonsterMixin} 完全同因同修，只是灾变有两条 Boss 基类分支，
 * 各自持有一份私有的 {@code damageBucket}（下界合金巨兽、斯库拉、玛勒迪克图斯、上古残魂走这条）。
 *
 * <p>@see LLibraryBossMonsterMixin
 */
@Mixin(IABoss_monster.class)
public abstract class IABossMonsterMixin {

    @Shadow
    private float damageBucket;

    @Inject(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"),
            require = 1
    )
    private void bossbucketfix$beginHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        float bucketBefore = this.damageBucket;
        if (BucketGuard.enter(this, bucketBefore)) {
            float restored = BucketGuard.savedBucket(this, bucketBefore);
            this.damageBucket = restored;
            BucketGuard.noteReplay("IABoss_monster", bucketBefore, restored, amount);
        }
    }

    @Inject(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("RETURN"),
            require = 1
    )
    private void bossbucketfix$endHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        BucketGuard.exit(this);
    }
}
