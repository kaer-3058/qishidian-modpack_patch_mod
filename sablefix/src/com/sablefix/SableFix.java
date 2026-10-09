package com.sablefix;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge 要求每个 mod 至少有一个 @Mod 类，实际修复逻辑在 mixin 里。
 */
@Mod("sablefix")
public class SableFix {

    private static volatile boolean reported = false;

    public SableFix() {
    }

    /**
     * 修复真正生效时打一次日志，方便在 latest.log 里确认。
     * 只打一次，避免刷屏。
     */
    public static void reportOnce() {
        if (!reported) {
            synchronized (SableFix.class) {
                if (!reported) {
                    reported = true;
                    System.out.println("[sablefix] 拦下 Sable 的 getTrackingSubLevel(null)"
                            + " —— 退世界不再崩溃，修复已生效。");
                }
            }
        }
    }
}
