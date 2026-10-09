package com.bossbucketfix.mixin;

import com.bossbucketfix.BucketGuard;
import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 {@code LLibrary_Boss_Monster.hurt} 里的单次伤害桶记账变成"一次挥砍只记一次"。
 *
 * <p>原始字节码（3.32，偏移量）：
 * <pre>
 *   27:  amount = Math.min(DamageCap(), amount)
 *   ...
 *  127:  oldBucket = this.damageBucket
 *  143:  newBucket = this.damageBucket + amount
 *  151:  cap = DamageCap()
 *  157:  if (newBucket &gt; cap) {
 *  165:      delta = cap - this.damageBucket
 *  174:      if (delta &gt; 0) { amount = delta; this.damageBucket = cap; }
 *  193:      else           { amount = 0.1f; }
 *  199:  } else             { this.damageBucket += amount; }
 *  209:  flag = super.hurt(source, amount)
 *  243:  if (!flag) this.damageBucket = oldBucket   // 回滚
 * </pre>
 *
 * <p>GokiSkills 会在 {@code super.hurt} 内部（也就是 {@code LivingEntity.hurt} 的 HEAD）
 * 取消原版调用并重放一次 {@code entity.hurt(...)}，于是这段记账第二次执行，
 * 桶里已经有第一遍加的同一个数 → 触发 {@code amount = cap - A}（面板越高伤害越低）
 * 或 {@code amount = 0.1f}（面板超过 cap 时血条纹丝不动）。
 *
 * <p>这里在 HEAD 记录"挥砍前的桶值"；检测到重入时先把桶恢复到挥砍前，
 * 于是重入那遍的记账等价于"没被重放过的一次结算"，用的是技能加成后的最终数值。
 * 没有重放时不会走到恢复分支，行为与原来完全一致。
 */
@Mixin(LLibrary_Boss_Monster.class)
public abstract class LLibraryBossMonsterMixin {

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
            BucketGuard.noteReplay("LLibrary_Boss_Monster", bucketBefore, restored, amount);
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
