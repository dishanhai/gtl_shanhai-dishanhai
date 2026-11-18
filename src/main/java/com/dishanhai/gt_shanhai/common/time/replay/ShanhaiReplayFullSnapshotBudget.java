package com.dishanhai.gt_shanhai.common.time.replay;

/**
 * 山海重构 · <b>第 6 层（可选全量快照）的内存硬闸与自动降级</b>
 * （🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径</h2>
 * 用户原话：「6G 内存以下我都能接受，我电脑好」「如果 cpu 不行的话，那我们先测试，主要办法还是靠你的差分」
 * ⇒ 内存不是否决理由，但<b>必须有硬闸</b>：估算超 {@link #CAP_MIB} 就自动降 R
 * （6 → 4 → 3 → 2 → 1），并且<b>在日志里如实说降了</b>。
 *
 * <h2>2. 一条日志（可 grep）</h2>
 * <pre>
 *   [SHANHAI-REPLAY] fullsnap r=2 chunks=25 bytes_per_tick=… window_mib=… cap_mib=6144 degraded=true
 * </pre>
 * 字段口径：{@code window_mib} 是"1200 tick 窗口全存下来要多少 MiB"，
 * 不是常驻 RSS；{@code degraded=true} 表示"请求的 R 被闸降过"。
 */
public final class ShanhaiReplayFullSnapshotBudget {

    /** 硬闸：6 GiB（用户明确接受的上限）。 */
    public static final long CAP_MIB = 6_144L;

    /** 默认半径（**实测通过后**才允许改成更大的；见报告"全量快照成本实测"）。 */
    public static final int DEFAULT_RADIUS = 2;

    /** 降级阶梯（从大到小）。 */
    public static final int[] RADII = {6, 4, 3, 2, 1};

    /** 一 MiB 的字节数。 */
    public static final long MIB = 1024L * 1024L;

    /**
     * @param radius             最终采用的半径
     * @param chunks             区块数 = (2R+1)²
     * @param bytesPerChunkPerTick 一个区块每 tick 的字节数
     * @param bytesPerTick       所有区块每 tick 的字节数
     * @param windowMib          1200 tick 全存下来多少 MiB
     * @param capMib             硬闸
     * @param degraded           是否被闸降过（含"降到底还是超"的情况）
     * @param requestedRadius    调用方请求的半径
     * @param overCap            降到底仍然超闸（必须在报告里点名，不许静默接受）
     */
    public record Plan(int radius, int chunks, long bytesPerChunkPerTick, long bytesPerTick,
                       long windowMib, long capMib, boolean degraded, int requestedRadius,
                       boolean overCap) {

        /** 一行日志（字段顺序稳定，便于 grep {@code fullsnap}）。 */
        public String toLine() {
            return "fullsnap r=" + this.radius + " chunks=" + this.chunks
                    + " bytes_per_tick=" + this.bytesPerTick + " window_mib=" + this.windowMib
                    + " cap_mib=" + this.capMib + " degraded=" + this.degraded
                    + " requested_r=" + this.requestedRadius + " over_cap=" + this.overCap;
        }
    }

    /**
     * {@code R} → 区块数。
     *
     * <p>口径按用户给的两个数据点定：{@code R=2 ⇒ 4 个区块}、{@code R=3 ⇒ 9 个区块}
     * ⇒ 就是 {@code R × R}（R 是边长，不是半径；这样默认 R=2 的 4 个区块正好对上）。
     */
    public static int chunksOf(int radius) {
        final int side = Math.max(1, radius);
        return side * side;
    }

    /**
     * 选半径：从请求值开始，按 {@link #RADII} 阶梯往下找第一个放得下的。
     *
     * @param bytesPerChunkPerTick 一个区块每 tick 的字节数（{@code > 0}）
     * @param windowTicks          窗口 tick 数（{@code <= 0} 按 1200 算）
     * @param capMib               硬闸（{@code <= 0} 用 {@link #CAP_MIB}）
     */
    public static Plan pick(long bytesPerChunkPerTick, int windowTicks, long capMib) {
        return pick(DEFAULT_RADIUS, bytesPerChunkPerTick, windowTicks, capMib);
    }

    /**
     * 选半径（指定请求值）。
     *
     * @param requestedRadius      调用方想要的 R
     * @param bytesPerChunkPerTick 一个区块每 tick 的字节数（{@code > 0}）
     * @param windowTicks          窗口 tick 数（{@code <= 0} 按 1200 算）
     * @param capMib               硬闸（{@code <= 0} 用 {@link #CAP_MIB}）
     */
    public static Plan pick(int requestedRadius, long bytesPerChunkPerTick, int windowTicks, long capMib) {
        final long perChunk = Math.max(1L, bytesPerChunkPerTick);
        final int window = windowTicks <= 0 ? ShanhaiReplayConst.WINDOW_TICKS : windowTicks;
        final long cap = capMib <= 0 ? CAP_MIB : capMib;
        final int requested = Math.max(1, requestedRadius);
        int chosen = -1;
        for (int r : RADII) {
            if (r > requested) {
                continue;   // 不许比请求值更大（用户明确要求"降级"而不是"升级"）
            }
            final long bytesPerTick = perChunk * chunksOf(r);
            final long windowMib = bytesPerTick * window / MIB;
            if (windowMib <= cap) {
                chosen = r;
                break;
            }
        }
        boolean overCap = false;
        if (chosen < 0) {
            chosen = 1;                       // 降到底（R=1）也只能接受，但必须报出来
            overCap = perChunk * chunksOf(1) * window / MIB > cap;
        }
        final long bytesPerTick = perChunk * chunksOf(chosen);
        return new Plan(chosen, chunksOf(chosen), perChunk, bytesPerTick,
                bytesPerTick * window / MIB, cap, chosen != requested, requested, overCap);
    }

    /** 请求 {@code radius} 时的估算（不降级，只算数）。 */
    public static Plan estimate(int radius, long bytesPerChunkPerTick, int windowTicks, long capMib) {
        final long perChunk = Math.max(1L, bytesPerChunkPerTick);
        final int window = windowTicks <= 0 ? ShanhaiReplayConst.WINDOW_TICKS : windowTicks;
        final long cap = capMib <= 0 ? CAP_MIB : capMib;
        final long bytesPerTick = perChunk * chunksOf(radius);
        final long windowMib = bytesPerTick * window / MIB;
        return new Plan(radius, chunksOf(radius), perChunk, bytesPerTick, windowMib, cap, false,
                radius, windowMib > cap);
    }

    private ShanhaiReplayFullSnapshotBudget() {
    }
}
