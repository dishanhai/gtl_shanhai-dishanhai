package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归墟视界枢核 · <b>藏块台账的对账器</b>（判据②：台账格数 == 要转的格数、漏藏 = 0）。
 *
 * <h2>为什么要有它（用户反馈「有些方块还没完全隐藏」）</h2>
 * {@link HostFxHide} 的台账是"藏了哪些格"的流水账；而"<b>要转的格</b>"是造型定义的一批集合。
 * 两者一旦不一致，用户看到的就是"环在转，但有一圈真方块原地不动、穿在转动环里"。
 * 所以这里做一次<b>逐格对账</b>，并把漏藏的坐标<b>按图案分组打印</b>。
 *
 * <h2>两种对账，缺一不可</h2>
 * <ol>
 *   <li><b>台账 vs 定义</b>（{@link #reconcile}）：{@code hideTargets()} 展开成世界坐标后
 *       去重计数，并按"环带 / 辐条 / 飞船"分桶 —— 这就是"要转的格数"；</li>
 *   <li><b>世界里的真方块 vs 定义</b>（{@link #audit}）：逐格 {@code getBlockState}，
 *       <b>还在的非空气格 = 漏藏</b>。这一条是真正意义上的"漏没漏"，谁也糊弄不了
 *       （台账里有没有不重要 —— 世界里还看得见才叫漏）。</li>
 * </ol>
 *
 * <h2>负对照（证明这个检查器确实能报错）</h2>
 * 2026-10-25 改前：环带容差 0.9（生成器是 1.1）+ 4 条辐条整批没进台账
 * ⇒ 审计必须报非零。改后必须报 0。两次读数都记在报告里。
 *
 * <h2>成本</h2>
 * {@link #hideTargets()} 约 2300 格、{@link #audit} 每格一次 {@code getBlockState}；
 * 只由渲染器在<b>日志节流（5 秒）</b>时调一次，且结果缓存 {@value #CACHE_MS} ms。
 */
public final class HostFxHideAudit {

    private HostFxHideAudit() {}

    /** 结果缓存时长（毫秒）：与渲染器的日志节流同量级。 */
    public static final long CACHE_MS = 5_000L;

    private static String cachedLine;
    private static long cachedAtMs;

    /** 一次对账结果。 */
    public record Result(int expectedCells, int ring, int arms, int ships,
                         int ledgerCells, List<BlockPos> leaks, int unloaded) {
        /** 漏藏格数。 */
        public int leakCount() {
            return leaks.size();
        }

        /** 判据②（定义部分）：台账必须覆盖全部"要转的格"。 */
        public boolean ledgerCoversAll() {
            return ledgerCells >= expectedCells;
        }

        /** 判据②（世界部分）：世界里不剩一个"该藏没藏"的真方块。 */
        public boolean clean() {
            return leaks.isEmpty() && unloaded == 0;
        }
    }

    /**
     * 把 {@link HostFxGeometry#hideTargets()} 展开成世界坐标、去重、按桶计数。
     *
     * @return {@code {合计, 环带, 辐条, 飞船}}
     */
    public static int[] reconcile(BlockPos controllerPos, Direction facing) {
        final Set<Long> seen = new LinkedHashSet<>(4096);
        int ring = 0;
        int arms = 0;
        int ships = 0;
        final Map<Long, String> bucket = batchIndex();
        for (BlockPos gen : HostFxGeometry.hideTargets()) {
            final BlockPos world = HostFxGeometry.worldFromGenerator(controllerPos, facing,
                    gen.getX(), gen.getY(), gen.getZ());
            if (!seen.add(world.asLong())) {
                continue;
            }
            final String b = bucket.get(gen.asLong());
            if ("辐条".equals(b)) {
                arms++;
            } else if ("飞船".equals(b)) {
                ships++;
            } else {
                ring++;
            }
        }
        return new int[]{ring + arms + ships, ring, arms, ships};
    }

    /**
     * 逐格看世界：还<b>不是空气</b>的"要转的格" = 漏藏。
     *
     * @param ledgerCells 当前台账格数（{@link HostFxHide#ledger} 的大小；null 记 0）
     */
    public static Result audit(Level level, BlockPos controllerPos, Direction facing) {
        final int[] counts = reconcile(controllerPos, facing);
        final Set<Long> seen = new LinkedHashSet<>(4096);
        final Map<Long, String> bucket = batchIndex();
        final List<BlockPos> leaks = new ArrayList<>();
        int unloaded = 0;
        int ledger = 0;
        final Map<Long, ?> led = HostFxHide.ledger(controllerPos);
        if (led != null) {
            ledger = led.size();
        }
        for (BlockPos gen : HostFxGeometry.hideTargets()) {
            final BlockPos world = HostFxGeometry.worldFromGenerator(controllerPos, facing,
                    gen.getX(), gen.getY(), gen.getZ());
            if (!seen.add(world.asLong())) {
                continue;
            }
            if (level == null || !level.isLoaded(world)) {
                unloaded++;
                continue;
            }
            final BlockState state = level.getBlockState(world);
            if (!state.isAir()) {
                leaks.add(world.immutable());
            }
        }
        // 泄了哪些桶（按坐标反查 gen 桶：用"该格属于哪一批"的名字打出来）
        return new Result(counts[0], counts[1], counts[2], counts[3], ledger, leaks, unloaded);
    }

    /**
     * 一行摘要（渲染器日志用，{@value #CACHE_MS} ms 内复用）。
     *
     * <p>格式固定成两个可 grep 的读数：{@code 台账=…/…} 与 {@code 漏藏=…}。
     */
    public static String report(Level level, BlockPos controllerPos, Direction facing) {
        final long now = System.currentTimeMillis();
        if (cachedLine != null && now - cachedAtMs < CACHE_MS) {
            return cachedLine;
        }
        cachedAtMs = now;
        final Result r = audit(level, controllerPos, facing);
        final StringBuilder sb = new StringBuilder(128);
        sb.append("台账=").append(r.ledgerCells()).append('/').append(r.expectedCells())
                .append(" 漏藏=").append(r.leakCount())
                .append("（要转 ").append(r.expectedCells()).append(" 格 = 环 ")
                .append(r.ring()).append(" / 辐条 ").append(r.arms())
                .append(" / 舰 ").append(r.ships()).append('）');
        if (r.unloaded() > 0) {
            sb.append(" 未加载=").append(r.unloaded());
        }
        if (!r.leaks().isEmpty()) {
            sb.append(" 🔴 漏藏清单前 12 个：");
            final List<BlockPos> ls = r.leaks();
            for (int i = 0; i < Math.min(12, ls.size()); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append('(').append(ls.get(i).getX()).append(',')
                        .append(ls.get(i).getY()).append(',')
                        .append(ls.get(i).getZ()).append(')');
            }
            if (ls.size() > 12) {
                sb.append(" …共 ").append(ls.size()).append(" 格");
            }
        }
        cachedLine = sb.toString();
        return cachedLine;
    }

    /** 清缓存（换维度/换主机时调用）。 */
    public static void reset() {
        cachedLine = null;
        cachedAtMs = 0L;
    }

    /**
     * 生成器坐标 → 批次名（"环带"/"辐条"/"飞船"）。
     *
     * <p>按<b>造型规则</b>判定，不按"谁先被加进 list"：辐条优先（它与环带在臂根处重叠），
     * 其次环带（{@code |hypot − 48| ≤ 1.1} 或它的 ±1 发光肋），剩下的是飞船。
     */
    public static Map<Long, String> batchIndex() {
        final Map<Long, String> m = new LinkedHashMap<>(4096);
        for (BlockPos p : HostFxGeometry.armCells()) {
            m.put(p.asLong(), "辐条");
        }
        for (BlockPos p : HostFxGeometry.hideTargets()) {
            if (m.containsKey(p.asLong())) {
                continue;
            }
            final int x = p.getX();
            final int z = p.getZ();
            final boolean onRing = Math.abs(Math.hypot(x, z) - HostFxGeometry.INNER_R)
                    <= HostFxGeometry.INNER_TOLERANCE;
            m.put(p.asLong(), onRing ? "环带" : "飞船");
        }
        return m;
    }

    /** 判据②的"结论"日志（一次性 INFO，给人工判读用）。 */
    public static void logConclusion(String line) {
        GTDishanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] 藏块审计 {}", line);
    }
}
