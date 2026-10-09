package com.bossbucketfix;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每次挥砍的伤害桶状态。
 *
 * <p>背景：GokiSkills 的 {@code LivingEntityMixin.hurt} 会在 HEAD 处改完伤害数值后，
 * <b>取消原版 {@code hurt} 并重新调用一次 {@code entity.hurt(source, 新数值)}</b>。
 * 这个"重放"调用依然会走一遍灾变最外层的 {@code hurt} 覆写，于是
 * {@code LLibrary_Boss_Monster.hurt} / {@code IABoss_monster.hurt} 里那段
 * 单次伤害桶（{@code damageBucket}）记账就被执行了两次，而
 * {@code dps_cap}/恢复速度只有一份：
 *
 * <pre>
 *   第一遍（随后被取消）：bucket += A
 *   第二遍（真正结算）  ：newBucket = A + A &gt; damage_cap
 *                          -&gt; amount = damage_cap - A      // 反比例！
 *                          -&gt; A &gt;= damage_cap 时 amount = 0.1（血条纹丝不动）
 * </pre>
 *
 * <p>本类记录"本次挥砍开始前的桶值"，让重入的那一遍把外层那次临时记账撤销掉，
 * 使一次挥砍只记账一次、且用的是最终（已被技能加成过的）数值。
 *
 * <p>没有重放发生时（绝大多数怪物、以及玩家技能没改数值时）行为与原来完全一致：
 * 外层那遍的记账保留，因为没人会调用 {@link #savedBucket} 去覆盖它。
 */
public final class BucketGuard {

    /** 最多在日志里报告几次重入，避免刷屏（只在确认修复是否生效时有意义）。 */
    private static final int MAX_REPORTS = 5;

    private static final AtomicInteger REPORTS = new AtomicInteger();

    /** 线程 -> (Boss 实例 -> 当前 hurt 帧状态)。服务端伤害结算都在主线程，实体按 identity 区分。 */
    private static final ThreadLocal<Map<Object, Frame>> STATE = ThreadLocal.withInitial(IdentityHashMap::new);

    private BucketGuard() {
    }

    /** 单个 Boss 实例在"当前挥砍"里的帧状态。 */
    public static final class Frame {
        float savedBucket;
        int depth;
    }

    /**
     * 进入 {@code hurt} 时调用。
     *
     * @param self         被伤害的 Boss 实例
     * @param bucketBefore 此刻还没被本帧改动过的 {@code damageBucket}
     * @return true == 这是一次"重入"（同一次挥砍内部嵌进来的第二遍 hurt），调用方应当把桶恢复
     */
    public static boolean enter(Object self, float bucketBefore) {
        Map<Object, Frame> frames = STATE.get();
        Frame frame = frames.get(self);
        if (frame == null) {
            frame = new Frame();
            frame.savedBucket = bucketBefore;
            frame.depth = 1;
            frames.put(self, frame);
            return false;
        }
        frame.depth++;
        return true;
    }

    /** 重入时取回本次挥砍开始前的桶值，用来撤销外层那遍的临时记账。 */
    public static float savedBucket(Object self, float fallback) {
        Frame frame = STATE.get().get(self);
        return frame == null ? fallback : frame.savedBucket;
    }

    /** 离开 {@code hurt}（任意一个 return 点）时调用，防止状态泄漏。 */
    public static void exit(Object self) {
        Map<Object, Frame> frames = STATE.get();
        Frame frame = frames.get(self);
        if (frame != null && --frame.depth <= 0) {
            frames.remove(self);
        }
    }

    /** 打前几次重入报告，给"修复到底有没有生效"提供一个日志证据。 */
    public static void noteReplay(String subject, float bucketNow, float restored, float amount) {
        int n = REPORTS.incrementAndGet();
        if (n > MAX_REPORTS) {
            return;
        }
        BossBucketFix.LOGGER.info(
                "[bossbucketfix] replay detected #{} on {}: bucket {} -> {} (outer charge discarded), this pass amount={}{}",
                n, subject, bucketNow, restored, amount,
                n == MAX_REPORTS ? " (further reports suppressed)" : "");
    }
}
