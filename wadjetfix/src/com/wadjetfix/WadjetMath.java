package com.wadjetfix;

/**
 * 角度处理逻辑放在我们自己的类里，避免往 lionfishapi 的类里合并新方法/新字段。
 */
public final class WadjetMath {

    private WadjetMath() {
    }

    private static final double TWO_PI = Math.PI * 2.0;

    /**
     * 超过这个量级的"角度"必然是上游算崩了：
     * 1e6 弧度 ≈ 16 万圈，正常物理不可能到。宁可当垃圾处理。
     */
    private static final double SANE_LIMIT = 1.0E6;

    private static volatile boolean loggedNonFinite = false;
    private static volatile boolean loggedNan = false;
    private static volatile boolean loggedSegments = false;

    /**
     * 把角度收敛到 [-π, π]。
     *
     * 关键：NaN 必须原样透传 —— DynamicChain.updateChain 依赖 Double.isNaN(p[1].x)
     * 来判断"链条炸了，重建它"。把 NaN 换成 0 会让这条尾巴永远不重建。
     */
    public static double wrap(double a) {
        if (Double.isNaN(a)) {
            if (!loggedNan) {
                loggedNan = true;
                log("首个 NaN 角度（原样透传，交给上游重建链条）");
            }
            return Double.NaN;
        }

        // ±Infinity 会让原实现的 while 循环永远转下去（渲染线程死锁）。
        // 超大有限值同理（|a| ≳ 5.6e16 时 a - 2π == a）。
        // 统一按 NaN 处理，走上游本来就有的重建逻辑。
        if (Double.isInfinite(a) || Math.abs(a) > SANE_LIMIT) {
            if (!loggedNonFinite) {
                loggedNonFinite = true;
                log("首个异常角度: " + a + "  -> 按 NaN 处理（若为 Infinity，这就是卡死的元凶）");
            }
            return Double.NaN;
        }

        double r = a % TWO_PI;
        if (r > Math.PI) {
            r -= TWO_PI;
        } else if (r < -Math.PI) {
            r += TWO_PI;
        }
        return r;
    }

    /** 一次性把尾巴骨节长度打出来，用于确认 d[i] 有没有退化成 0（除零 -> Infinity -> 卡死）。 */
    public static void reportSegments(float[] d) {
        if (loggedSegments || d == null) {
            return;
        }
        loggedSegments = true;
        StringBuilder sb = new StringBuilder("尾巴骨节长度 d[] = ");
        for (float f : d) {
            sb.append(f).append("  ");
        }
        log(sb.toString());
    }

    public static void log(String msg) {
        System.out.println("[wadjetfix] " + msg);
    }
}
