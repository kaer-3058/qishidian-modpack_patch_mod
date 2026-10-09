package com.bossbucketfix;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * bossbucketfix —— 修复「灾变 Boss 的伤害桶被二次记账」。
 *
 * <p>修复逻辑全在两个 mixin 里，这个类只负责让 FML 认识这个 mod 并打一行加载日志。
 */
@Mod(BossBucketFix.MOD_ID)
public class BossBucketFix {

    public static final String MOD_ID = "bossbucketfix";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public BossBucketFix() {
        LOGGER.info("[bossbucketfix] loaded: Cataclysm boss damage-bucket reentrancy fix is active "
                + "(LLibrary_Boss_Monster + IABoss_monster).");
    }
}
