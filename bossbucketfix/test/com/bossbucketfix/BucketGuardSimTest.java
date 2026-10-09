package com.bossbucketfix;

/**
 * 离线仿真：把灾变 {@code LLibrary_Boss_Monster.hurt} 里那段单次伤害桶记账
 * （偏移 127..209）和 GokiSkills 的"取消 + 重放"一起建模，验证：
 *
 * <ol>
 *   <li>修复前会出现"面板越高伤害越低"，并且在面板 ≥ damage_cap 时退化成 0.1；</li>
 *   <li>修复后 == "只结算一遍、用技能加成后的数值" 的理论值（这是修复的定义）；</li>
 *   <li>没有重放（bonus=0）时，修复前后完全一致（不能有副作用）；</li>
 *   <li>连击残留（桶里有旧值）时依然尊重 damage_cap。</li>
 * </ol>
 *
 * <p>编译/运行见项目 README。退出码 0 = 全部通过。
 */
public final class BucketGuardSimTest {

    /** 与 config/cataclysm-common.toml 的 damage_cap 一致。 */
    private static final float CAP = 22.0F;

    private static int failures = 0;

    private BucketGuardSimTest() {
    }

    /** 复刻 hurt 的 27..209 段：先夹 cap，再做桶记账，返回真正交给 super.hurt 的数值。 */
    private static final class Boss {
        final float bucketStart;
        float bucket;

        Boss(float bucketStart) {
            this.bucketStart = bucketStart;
            this.bucket = bucketStart;
        }

        float hurt(float amountIn) {
            float amount = Math.min(CAP, amountIn);
            float newBucket = this.bucket + amount;
            if (newBucket > CAP) {
                float delta = CAP - this.bucket;
                if (delta > 0) {
                    amount = delta;
                    this.bucket = CAP;
                } else {
                    amount = 0.1F;
                }
            } else {
                this.bucket += amount;
            }
            return amount;
        }
    }

    /**
     * 一次挥砍：外层 hurt（被 GokiSkills 取消）→ GokiSkills 改数值并重放 → 重放那次才真正结算。
     *
     * @param bonus 技能加成（0 表示数值没被改动，也就没有重放）
     * @param fix   是否启用 BucketGuard（= 本 mod 装上/没装上）
     */
    private static float swing(Boss boss, float panel, float bonus, boolean fix) {
        boolean replayed = bonus > 0.0F;

        if (fix) {
            BucketGuard.enter(boss, boss.bucket);
        }
        float outerAmount = boss.hurt(panel);       // 外层：临时记账，随后整次调用被取消
        float applied = outerAmount;

        if (replayed) {
            float replayedAmount = outerAmount * (1.0F + bonus);   // GokiSkills: amount *= (1+bonus)
            if (fix && BucketGuard.enter(boss, boss.bucket)) {
                boss.bucket = BucketGuard.savedBucket(boss, boss.bucket);   // 撤销外层临时记账
            }
            applied = boss.hurt(replayedAmount);    // 重放：真正结算
            if (fix) {
                BucketGuard.exit(boss);
            }
        }
        if (fix) {
            BucketGuard.exit(boss);
        }
        return applied;
    }

    /** 理论值：只结算一遍、用"面板夹取后 × (1+bonus)"的数值。 */
    private static float[] ideal(float bucketStart, float panel, float bonus) {
        Boss boss = new Boss(bucketStart);
        float amount = Math.min(CAP, panel) * (1.0F + bonus);
        float applied = boss.hurt(amount);
        return new float[] {applied, boss.bucket};
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  !! FAIL: " + what);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    public static void main(String[] args) {
        float[] bonuses = {0.375F, 0.65F};

        for (float bonus : bonuses) {
            section("有重放（剑术加成 " + bonus + "，空桶，damage_cap=" + CAP + "）");
            System.out.println("  panel |  修复前   |  修复后   | 理论值(单遍) | 桶(修后)");
            float prev = -1.0F;
            for (float panel : new float[] {4.0F, 6.0F, 8.0F, 11.0F, 13.0F, 16.0F, 22.0F, 24.0F, 40.0F}) {
                Boss broken = new Boss(0.0F);
                Boss fixed = new Boss(0.0F);
                float before = swing(broken, panel, bonus, false);
                float after = swing(fixed, panel, bonus, true);
                float[] want = ideal(0.0F, panel, bonus);
                System.out.printf("  %5.1f | %8.3f | %9.3f | %12.3f | %8.3f%n",
                        panel, before, after, want[0], fixed.bucket);

                // (2) 修复后 == 理论值
                check(Math.abs(after - want[0]) < 1.0E-3F,
                        "panel=" + panel + " 修复后 " + after + " != 理论值 " + want[0]);
                check(Math.abs(fixed.bucket - want[1]) < 1.0E-3F,
                        "panel=" + panel + " 桶 " + fixed.bucket + " != 理论桶 " + want[1]);
                // (3) 单调不降 + 不超上限
                check(after >= prev - 1.0E-4F, "panel=" + panel + " 修复后不单调");
                check(after <= CAP + 1.0E-4F, "panel=" + panel + " 修复后超过 damage_cap");
                prev = after;
            }
        }

        section("复现用户实测（剑术加成 0.375；桶残留 = 上一次挥砍没散完的部分）");
        // 用户报告：白板 8 -> 11，锋利5 11 -> 8（当时桶里还剩 3），13 -> 4（桶里还剩 5），24 -> 0
        float[][] reported = {
                {0.0F, 8.0F, 11.0F},
                {3.0F, 11.0F, 8.0F},
                {5.0F, 13.0F, 4.0F},
                {0.0F, 24.0F, 0.1F},
        };
        for (float[] r : reported) {
            Boss boss = new Boss(r[0]);
            float applied = swing(boss, r[1], 0.375F, false);
            System.out.printf("  桶残留 %4.1f, panel %5.1f -> 修复前实际 %6.1f (用户报告 %4.1f)%n",
                    r[0], r[1], applied, r[2]);
            check(Math.abs(applied - r[2]) < 0.05F,
                    "重放模型没能复现 panel=" + r[1] + " bucket0=" + r[0]);
        }
        System.out.println("  （同一组输入在修复后）");
        for (float[] r : reported) {
            Boss boss = new Boss(r[0]);
            float applied = swing(boss, r[1], 0.375F, true);
            float[] want = ideal(r[0], r[1], 0.375F);
            System.out.printf("  桶残留 %4.1f, panel %5.1f -> 修复后实际 %6.1f (理论 %6.1f)%n",
                    r[0], r[1], applied, want[0]);
            check(Math.abs(applied - want[0]) < 1.0E-3F, "修复后与理论值不符 panel=" + r[1]);
        }

        section("没有重放（bonus=0）——修复前后必须逐位一致");
        for (float bucketStart : new float[] {0.0F, 5.0F, 14.0F, 21.0F, 22.0F}) {
            for (float panel : new float[] {3.0F, 8.0F, 22.0F, 30.0F}) {
                Boss a = new Boss(bucketStart);
                Boss b = new Boss(bucketStart);
                float before = swing(a, panel, 0.0F, false);
                float after = swing(b, panel, 0.0F, true);
                boolean same = Math.abs(before - after) < 1.0E-4F && Math.abs(a.bucket - b.bucket) < 1.0E-4F;
                System.out.printf("  桶起始 %5.1f, panel %5.1f -> 前 %.3f / 后 %.3f (桶 %.3f / %.3f) %s%n",
                        bucketStart, panel, before, after, a.bucket, b.bucket, same ? "一致" : "不一致");
                check(same, "无重放时行为被改变了 bucket0=" + bucketStart + " panel=" + panel);
            }
        }

        section("连击残留：修复后依然尊重 damage_cap（不会白送伤害）");
        for (float bucketStart : new float[] {3.0F, 10.0F, 18.0F, 21.0F, 22.0F}) {
            Boss fixed = new Boss(bucketStart);
            float after = swing(fixed, 11.0F, 0.375F, true);
            float allowanceBefore = CAP - bucketStart;
            System.out.printf("  桶起始 %5.1f, panel 11 -> 修复后实际 %6.3f (挥砍前剩余额度 %.1f), 桶 %6.3f%n",
                    bucketStart, after, allowanceBefore, fixed.bucket);
            // 额度用尽时灾变原版会给 0.1f 的固定伤害（修复前后都一样），下限按 0.1 算
            float ceiling = Math.max(allowanceBefore, 0.1F);
            check(after <= ceiling + 1.0E-3F, "超出挥砍前剩余额度 bucket0=" + bucketStart);
            check(fixed.bucket <= CAP + 1.0E-4F, "桶超过上限 bucket0=" + bucketStart);
        }

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : ("FAILURES: " + failures));
        if (failures != 0) {
            System.exit(1);
        }
    }
}
