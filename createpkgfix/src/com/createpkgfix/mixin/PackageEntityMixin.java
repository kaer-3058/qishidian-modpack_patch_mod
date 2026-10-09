package com.createpkgfix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.logistics.box.PackageEntity;

import net.minecraft.world.damagesource.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 给 Create 的 {@code PackageEntity.hurt(DamageSource, float)} 加「同一实体不可重入」保护。
 *
 * <p>Create 官方代码在这条方法体内调用了
 * {@code CommonHooks.onPlayerAttackTarget(player, this)}（Create 6.0.10 的 PackageEntity.java
 * 第 303 行附近）。这个 hook 会依次触发：
 * <ol>
 *   <li>{@code AttackEntityEvent} 事件</li>
 *   <li>{@code ItemStack.getItem().onLeftClickEntity(stack, player, target)}</li>
 * </ol>
 *
 * <p>匠魂的 {@code ModifiableItem.onLeftClickEntity} 会自己结算这次攻击
 * （{@code EntityInteractionModifierHook#leftClickEntity} →
 * {@code ToolAttackUtil.attackEntity} → {@code performAttack} → {@code target.hurt(...)}），
 * 于是 hurt 里的那次 hook 调用又绕回 hurt，形成无限递归。原版物品的
 * {@code onLeftClickEntity} 默认返回 false 且不造成伤害，所以不会踩中。
 *
 * <p>本 mixin 用一个「本实体正在执行 hurt」的标记打断递归：
 * <ul>
 *   <li>最外层那次 hurt 正常执行（伤害照常结算一次）；</li>
 *   <li>递归进来的那次 hurt 直接返回 false（不结算伤害）—— 它本来就是匠魂被
 *       Create 的 hook 重复触发出来的多余攻击；</li>
 *   <li>用 try/finally 保证标记一定会被清掉，即使 hurt 抛异常也不会残留。</li>
 * </ul>
 *
 * <p>目标类是 Create 的类（mod 类，运行时即官方名，无需 refmap）。
 */
@Mixin(PackageEntity.class)
public abstract class PackageEntityMixin {

    /** 本实体当前是否正在执行 hurt。 */
    @Unique
    private boolean createpkgfix$inHurt;

    @WrapMethod(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            require = 1
    )
    private boolean createpkgfix$guardReentry(DamageSource source, float amount, Operation<Boolean> original) {
        if (this.createpkgfix$inHurt) {
            // 同一包裹的重入伤害：丢弃，打断递归。
            return false;
        }
        this.createpkgfix$inHurt = true;
        try {
            return original.call(source, amount);
        } finally {
            this.createpkgfix$inHurt = false;
        }
    }
}
