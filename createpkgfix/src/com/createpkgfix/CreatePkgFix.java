package com.createpkgfix;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 修复「匠魂武器左键打机械动力快递包裹导致栈溢出崩溃」。
 *
 * <p>崩溃现象（服务端 crash-2026-10-06_13.06.56-server.txt）：
 * <pre>
 * java.lang.StackOverflowError: Exception in server tick loop
 *   at CommonHooks.onPlayerAttackTarget
 *   at PackageEntity.hurt                (Create)
 *   at ToolAttackUtil.performAttack      (Tinkers' Construct)
 *   at ToolAttackUtil.attackEntity
 *   at EntityInteractionModifierHook.leftClickEntity
 *   at ModifiableItem.onLeftClickEntity
 *   at CommonHooks.onPlayerAttackTarget  (回到起点)
 *   ...（递归 170 层后爆栈）
 * </pre>
 *
 * <p>根因：Create 的 {@code PackageEntity.hurt()} 内部又调用了一次 NeoForge 的
 * {@code CommonHooks.onPlayerAttackTarget(player, this)}，而该 hook 会回调
 * {@code Item.onLeftClickEntity}。匠魂的可改造武器正是在
 * {@code onLeftClickEntity} 里自己结算伤害（{@code ToolAttackUtil.performAttack}
 * → {@code target.hurt}），于是：
 *
 * <pre>
 * attack → onLeftClickEntity(匠魂) → hurt → onPlayerAttackTarget → onLeftClickEntity(匠魂) → hurt → …
 * </pre>
 *
 * <p>普通原版物品的 {@code onLeftClickEntity} 不会造成伤害，所以只有匠魂（以及同类
 * "在 onLeftClickEntity 里结算伤害"的 mod 武器）才会踩中这个死循环。
 *
 * <p>修复：给 {@code PackageEntity.hurt} 加一层「同一实体不可重入」的保护
 * （见 {@code PackageEntityMixin}）。重入的那次 hurt 直接丢弃，递归被打断，
 * 包裹最终只承受一次伤害。不改动任何伤害数值或 Create 的其他逻辑。
 */
@Mod("createpkgfix")
public class CreatePkgFix {

    public static final String MOD_ID = "createpkgfix";
    public static final Logger LOGGER = LoggerFactory.getLogger("CreatePkgFix");

    public CreatePkgFix() {
        LOGGER.info("[createpkgfix] 已给 Create 的 PackageEntity.hurt 加上重入保护：匠魂武器左键打包裹不会再因递归而栈溢出。");
    }
}
