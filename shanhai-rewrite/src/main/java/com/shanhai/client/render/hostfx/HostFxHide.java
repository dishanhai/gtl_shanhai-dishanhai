package com.shanhai.client.render.hostfx;

import com.shanhai.ShanhaiMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归墟视界枢核 · <b>藏真块 / 恢复</b>（客户端，每帧 + 每秒对账两条驱动）。
 *
 * <h2>为什么要有它（"藏真块 + 画转块"）</h2>
 * 造型里内环带与 8 艘飞船是<b>真方块</b>；而我们要画的是"转起来的内环"和"沿环飞的飞船"。
 * 不藏起来就会看到"静止的真块 + 叠在上面的动块"两层。所以按原始终焉引擎那套做法：
 * <b>成型期间把那些格子在客户端写成 AIR，未成型时按台账写回原方块</b>。
 *
 * <h2>与 {@code GtlAddClientCompat.hideRingsAt} 的关系（照搬做法，不是自造）</h2>
 * 同一套不变式，逐条对齐：
 * <ol>
 *   <li><b>先记账再写</b>（{@code putIfAbsent}）：<b>绝不</b>把"上次被我们写成 AIR"的格子记成 AIR
 *       （那是旧实现的死亡路径：一旦记错就永远救不回来）；</li>
 *   <li><b>恢复要去抖</b>：{@code isFormed()} 会抖（部件区块卸载 ⇒ 每 4 tick 异步重查），
 *       连续未成型 {@link HostFxHidePolicy#RESTORE_DEBOUNCE_TICKS} tick（2 秒）才真的写回；</li>
 *   <li><b>台账与维度同生同死</b>：换维度整表清空（<b>清之前先按台账写回</b>，见
 *       {@link #clearAll(Level)}）；</li>
 *   <li><b>每帧 O(1)</b>：已藏且扫全 ⇒ 直接短路，不重复遍历（只在"首次/未扫全"时扫一遍）。</li>
 * </ol>
 *
 * <h2>🔴 2026-10-24 傍晚：为"结构被打掉后世界缺块"新增的三件事</h2>
 * 用户原话：「<b>破坏主机结构之后，被隐藏的方块没有重新渲染回实体</b>」（他标为最严重）。
 * <ol>
 *   <li><b>心跳</b>：{@link #noteFormed} 每次"确认成型并且这一帧真的算过它"就记一次时间；
 *       {@link #reconcile} 每秒跑一次，超过
 *       {@link HostFxHidePolicy#STALE_HEARTBEAT_MS} 没心跳 ⇒ <b>立刻按台账写回 + WARN</b>；</li>
 *   <li><b>不依赖 BER</b>：{@code reconcile} 由 {@link HostFxHideTicker}（client tick）驱动，
 *       <b>哪怕控制器的方块实体已经不存在、区块已卸载、玩家走远</b>，台账也一定会被处理掉；</li>
 *   <li><b>换维度先还原</b>：{@link #clearAll(Level)} 现在会先把旧维度里的格子写回，
 *       再丢表；{@link #restoreAll(Level, String)} 是"全还"的显式入口（退出世界用）。</li>
 * </ol>
 * 判定规则本体在 {@link HostFxHidePolicy}（纯逻辑、可离线跑真值表）。
 *
 * <h2>降级</h2>
 * 任何异常 ⇒ {@link HostFxFallback#degrade} 到"不藏块"，宁愿看到两层也不要留下幽灵方块。
 */
public final class HostFxHide {

    private HostFxHide() {}

    /** 连续未成型多久才恢复（tick）。与老机器同一口径（5 秒）⇒ 🔴 2026-10-24 傍晚改 2 秒。 */
    public static final int RESTORE_DEBOUNCE_TICKS = HostFxHidePolicy.RESTORE_DEBOUNCE_TICKS;

    /** 主机位置 → (被藏的格子 → 写 AIR 之前的状态)。 */
    private static final Map<Long, Map<Long, BlockState>> HIDDEN = new HashMap<>();
    /** 主机位置 → 本轮是否扫全（有未加载区块就置位，下帧重扫）。 */
    private static final Set<Long> INCOMPLETE = new HashSet<>();
    /** 主机位置 → 连续未成型的起点（毫秒墙钟，暂停时也走）。 */
    private static final Map<Long, Long> UNFORMED_SINCE = new HashMap<>();
    /** 主机位置 → 上一次扫表的墙钟毫秒（用于"每 2 秒补扫一次"，兜住区块重载把真块送回来的情况）。 */
    private static final Map<Long, Long> LAST_SCAN_MS = new HashMap<>();
    /** 主机位置 → 上一次<b>确认成型</b>的墙钟毫秒（心跳；{@link HostFxHidePolicy#STALE_HEARTBEAT_MS}）。 */
    private static final Map<Long, Long> LAST_FORMED_MS = new HashMap<>();
    /** 补扫间隔（毫秒）：1704 格 × 每 2 秒 ≈ 850 次 getBlockState/秒，可以忽略。 */
    public static final long RESCAN_INTERVAL_MS = 2000L;
    /** 台账属于哪个维度（换维度整表清）。 */
    private static Object ledgerDimension;

    /** 上一秒对账的读数（日志/报告用）。 */
    private static int lastReconcileCells;
    private static String lastReconcileWhy = "none";
    private static long lastReconcileMs;

    /**
     * 台账版本号：<b>只要台账内容真的变了就自增</b>（新格子被藏 / 恢复 / 换维度清表）。
     *
     * <p>{@link HostFxRingGroups} 用它判断"要不要重新烘焙"——"藏一份、烘一份"必须同一份，
     * 这个版本号就是那份台账的变更通知，比"每帧比 map 内容"便宜得多。
     */
    private static long stamp = 1L;

    /** 台账版本号（只读）。 */
    public static long stamp() {
        return stamp;
    }

    /**
     * 取某台主机的台账（世界坐标 → <b>写 AIR 之前的方块状态</b>），<b>只读</b>。
     *
     * <p>烘焙器 {@link HostFxBaked#cells} 的输入就是它 ⇒ "藏了哪些格、就烘哪些格"，
     * 不会出现"藏 A 画 B"的错位（伪神那边靠图案重算，我们靠记账，更保险）。
     *
     * @return 没有台账 / 台账为空时返回 {@code null}
     */
    public static Map<Long, BlockState> ledger(BlockPos controllerPos) {
        final Map<Long, BlockState> mine = HIDDEN.get(controllerPos.asLong());
        if (mine == null || mine.isEmpty()) {
            return null;
        }
        return java.util.Collections.unmodifiableMap(mine);
    }

    /** 当前被藏起来的格子总数（给报告/调试用，只读）。 */
    public static int hiddenCount() {
        int n = 0;
        for (Map<Long, BlockState> m : HIDDEN.values()) {
            n += m.size();
        }
        return n;
    }

    /** 当前有台账的主机数（只读）。 */
    public static int hostCount() {
        return HIDDEN.size();
    }

    /** 有台账的主机键（{@code BlockPos.asLong()}）。对账器要遍历它。 */
    public static Set<Long> hosts() {
        return new HashSet<>(HIDDEN.keySet());
    }

    /** 上一次对账的读数（一格都没藏时也能回答"对账跑过了"）。 */
    public static String lastReconcile() {
        return "cells=" + lastReconcileCells + " why=" + lastReconcileWhy
                + " age=" + (lastReconcileMs == 0L ? "never" : (System.currentTimeMillis() - lastReconcileMs) + "ms");
    }

    /**
     * <b>心跳</b>：确认这台主机这一帧成型、并且我们真的算过它。
     *
     * <p>它<b>与"要不要藏"无关</b>（{@code host_fx_hide_real_blocks=false} 时也照打）——
     * 因为它回答的是"我们还在看着这台主机吗"，而不是"我们在藏它吗"。
     * 少打一次心跳的后果是 2 秒后台账被还原（安全，但会白跑一次烘焙）。
     */
    public static void noteFormed(BlockPos controllerPos) {
        if (controllerPos == null) {
            return;
        }
        LAST_FORMED_MS.put(controllerPos.asLong(), System.currentTimeMillis());
    }

    /** 换维度 / 换 Level 时把维度标记对齐（不清表：清表走 {@link #clearAll(Level)}）。 */
    private static void bindDimension(Level level) {
        if (ledgerDimension != level.dimension()) {
            // ⚠️ 走到这里说明"换维度"是<b>本方法</b>先发现的（渲染器那条路还没跑）。
            //    此时我们手里没有<b>旧</b> Level 对象，写不回去 ⇒ 只能丢表 + 明确 WARN。
            //    正常路径是 GuixuHostFxRenderer 先发现换 Level 并调 clearAll(oldLevel) 逐格写回。
            int dropped = 0;
            for (Map<Long, BlockState> m : HIDDEN.values()) {
                dropped += m.size();
            }
            if (dropped > 0) {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] ledger_dimension_drop cells={} from={} to={} "
                                + "（换维度先由 HostFxHide 发现：旧维度已拿不到，无法逐格写回；"
                                + "正常情况下由渲染器的 clearAll(oldLevel) 先接手）",
                        dropped, ledgerDimension, level.dimension());
            }
            HIDDEN.clear();
            INCOMPLETE.clear();
            UNFORMED_SINCE.clear();
            LAST_SCAN_MS.clear();
            LAST_FORMED_MS.clear();
            ledgerDimension = level.dimension();
            stamp++;
        }
    }

    /**
     * 成型期间每帧调用：把本机该藏的格子写成 AIR。
     *
     * <p>短路条件：已有台账且上一轮扫全 ⇒ 直接返回（O(1)），与老实现一致。
     */
    public static void hide(Level level, BlockPos controllerPos, Direction facing) {
        if (level == null) {
            return;
        }
        bindDimension(level);
        final long hostKey = controllerPos.asLong();
        noteFormed(controllerPos);                 // 心跳：走得到这里 = 结构是成型的
        UNFORMED_SINCE.remove(hostKey);            // 进入 hide 路径 = 撤销恢复去抖

        final long nowMs = System.currentTimeMillis();
        final Map<Long, BlockState> mine = HIDDEN.get(hostKey);
        final Long lastScan = LAST_SCAN_MS.get(hostKey);
        // 短路：已藏过、上一轮扫全、且距上次扫表不足 RESCAN_INTERVAL_MS ⇒ O(1) 返回。
        // （补扫是为了兜住"区块重载后客户端又收到真方块" ⇒ 那几格会重新露出来，最多露 2 秒。）
        if (mine != null && !mine.isEmpty() && !INCOMPLETE.contains(hostKey)
                && lastScan != null && nowMs - lastScan < RESCAN_INTERVAL_MS) {
            return;
        }
        final Map<Long, BlockState> ledger = (mine != null) ? mine : new HashMap<>();
        HIDDEN.put(hostKey, ledger);
        LAST_SCAN_MS.put(hostKey, nowMs);

        boolean scannedAll = true;
        boolean changed = false;
        final List<BlockPos> targets = HostFxGeometry.hideTargets();
        for (BlockPos local : targets) {
            // 🔴 2026-10-24 修：原来写的是 controllerPos.offset(rotate(facing, local)) ——
            //    local 是"生成器坐标"（环心 = (0, RING_Y, 0)、控制器 = (72, 21, 0)），
            //    直接当"控制器相对偏移"用就<b>少减了一次控制器坐标</b>，整批格子偏移 (-72,-11,0)，
            //    结果是"真环没被藏、别处被挖洞"。统一走 worldFromGenerator（藏块/烘焙/探针同一份换算）。
            final BlockPos world = HostFxGeometry.worldFromGenerator(controllerPos, facing,
                    local.getX(), local.getY(), local.getZ());
            if (!level.isLoaded(world)) {
                scannedAll = false;
                continue;
            }
            final BlockState current = level.getBlockState(world);
            if (current.isAir()) {
                continue;                    // 本来就空 / 已被我们写过（已在台账里）
            }
            ledger.putIfAbsent(world.asLong(), current);
            level.setBlock(world, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
            changed = true;
        }
        if (changed) {
            stamp++;                         // 台账变了 ⇒ 烘焙器要重来（HostFxRingGroups 看这个）
        }
        if (scannedAll) {
            INCOMPLETE.remove(hostKey);
        } else {
            INCOMPLETE.add(hostKey);
        }
    }

    /**
     * 未成型期间每帧调用：连续未成型够久就按台账写回（这条是"看得见主机"时的快路径；
     * 看不见时的保险是 {@link #reconcile}）。
     *
     * @param nowMs 墙钟毫秒（由调用方给，便于测试与"暂停也计时"）
     */
    public static void restore(Level level, BlockPos controllerPos, long nowMs) {
        if (level == null) {
            return;
        }
        final long hostKey = controllerPos.asLong();
        final Map<Long, BlockState> ledger = HIDDEN.get(hostKey);
        if (ledger == null || ledger.isEmpty()) {
            return;
        }
        // 注意：这里<b>不</b>更新心跳 —— 未成型就是没有心跳
        final Long since = UNFORMED_SINCE.computeIfAbsent(hostKey, k -> nowMs);
        if (!HostFxHidePolicy.needsRestore(true, LAST_FORMED_MS.get(hostKey), since, nowMs)) {
            return;
        }
        restoreOne(level, hostKey, ledger, "unformed_"
                + (nowMs - since) + "ms");
    }

    /**
     * 🔴 <b>运行时对账（每秒一次，由 {@link HostFxHideTicker} 驱动，不依赖 BER）</b>。
     *
     * <p>对每一台还有台账的主机：判定"结构是不是已经不该被藏了"，是 ⇒
     * <b>立刻按台账把每一格写回，并打一行 WARN</b>。用户口径：
     * 「每 N 秒检查'台账里记着要藏的格，当前是否仍是我们藏的那个状态；
     * 结构已失型却没还原 ⇒ 立即还原并打一行 WARN'」。
     *
     * @return 这一次对账还原了多少格
     */
    public static int reconcile(Level level, long nowMs) {
        if (level == null || HIDDEN.isEmpty()) {
            lastReconcileCells = 0;
            lastReconcileWhy = HIDDEN.isEmpty() ? "ledger_empty" : "no_level";
            lastReconcileMs = nowMs;
            return 0;
        }
        bindDimension(level);
        int restoredCells = 0;
        for (Long hostKey : new ArrayList<>(HIDDEN.keySet())) {
            final Map<Long, BlockState> ledger = HIDDEN.get(hostKey);
            if (ledger == null || ledger.isEmpty()) {
                HIDDEN.remove(hostKey);
                continue;
            }
            final Long lastFormed = LAST_FORMED_MS.get(hostKey);
            final Long unformedSince = UNFORMED_SINCE.get(hostKey);
            lastReconcileCells = ledger.size();
            lastReconcileWhy = HostFxHidePolicy.why(true, lastFormed, unformedSince, nowMs);
            if (!HostFxHidePolicy.needsRestore(true, lastFormed, unformedSince, nowMs)) {
                // 逐格对账（用户明确要求"当前是否仍是我们藏的那个状态"）：
                // 已经不是空气 ⇒ 别人动过 ⇒ 从台账摘掉，还原时不再覆盖它。
                dropTouched(level, ledger);
                continue;
            }
            restoredCells += restoreOne(level, hostKey, ledger,
                    lastReconcileWhy + "_reconcile");
        }
        lastReconcileMs = nowMs;
        return restoredCells;
    }

    /**
     * 把台账里"已经被别人改过"的格子摘掉（我们藏的时候写的是 AIR ⇒ 现在的判据是"还不是空气"）。
     *
     * @return 摘掉了多少格
     */
    private static int dropTouched(Level level, Map<Long, BlockState> ledger) {
        int dropped = 0;
        final java.util.Iterator<Map.Entry<Long, BlockState>> it = ledger.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Long, BlockState> e = it.next();
            final BlockPos pos = BlockPos.of(e.getKey());
            if (!level.isLoaded(pos)) {
                continue;                        // 未加载：状态未知，别动（下次再说）
            }
            if (!HostFxHidePolicy.keepInLedger(level.getBlockState(pos).isAir())) {
                it.remove();
                dropped++;
            }
        }
        if (dropped > 0) {
            stamp++;
        }
        return dropped;
    }

    /** 真还原：逐格写回 + 清台账 + 一行 WARN。 */
    private static int restoreOne(Level level, long hostKey, Map<Long, BlockState> ledger, String why) {
        int written = 0;
        int skipped = 0;
        for (Map.Entry<Long, BlockState> e : ledger.entrySet()) {
            final BlockPos pos = BlockPos.of(e.getKey());
            if (!level.isLoaded(pos)) {
                skipped++;
                continue;
            }
            level.setBlock(pos, e.getValue(), 2 | 16 | 32);
            written++;
        }
        final int cells = ledger.size();
        HIDDEN.remove(hostKey);
        INCOMPLETE.remove(hostKey);
        UNFORMED_SINCE.remove(hostKey);
        LAST_SCAN_MS.remove(hostKey);
        LAST_FORMED_MS.remove(hostKey);
        stamp++;
        lastReconcileCells = 0;
        ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] restore host={} cells={} written={} notLoaded={} "
                        + "why={} （按台账写回原方块）",
                BlockPos.of(hostKey).toShortString(), cells, written, skipped, why);
        return written;
    }

    /** 换维度 / 离开世界：<b>先把格子还回去</b>，再整表丢弃。 */
    public static void clearAll(Level level) {
        if (level != null) {
            for (Long hostKey : new ArrayList<>(HIDDEN.keySet())) {
                final Map<Long, BlockState> ledger = HIDDEN.get(hostKey);
                if (ledger != null && !ledger.isEmpty()) {
                    restoreOne(level, hostKey, ledger, "level_change");
                }
            }
        }
        HIDDEN.clear();
        INCOMPLETE.clear();
        UNFORMED_SINCE.clear();
        LAST_SCAN_MS.clear();
        LAST_FORMED_MS.clear();
        ledgerDimension = null;
        stamp++;
    }

    /** 兼容旧调用（没有 Level 可写时只能丢表；新代码请用 {@link #clearAll(Level)}）。 */
    public static void clearAll() {
        clearAll(null);
    }

    /** 退出世界前显式全还（一道保险；正常路径由 {@link #reconcile} 兜住）。 */
    public static int restoreAll(Level level, String why) {
        if (level == null) {
            return 0;
        }
        int n = 0;
        for (Long hostKey : new ArrayList<>(HIDDEN.keySet())) {
            final Map<Long, BlockState> ledger = HIDDEN.get(hostKey);
            if (ledger != null && !ledger.isEmpty()) {
                n += restoreOne(level, hostKey, ledger, why);
            }
        }
        return n;
    }

    /** 主机被移除/换维度时立刻按台账写回（不等去抖、不等心跳）。 */
    public static void restoreNow(Level level, BlockPos controllerPos) {
        if (level == null || controllerPos == null) {
            return;
        }
        final long hostKey = controllerPos.asLong();
        final Map<Long, BlockState> ledger = HIDDEN.get(hostKey);
        if (ledger == null || ledger.isEmpty()) {
            HIDDEN.remove(hostKey);
            INCOMPLETE.remove(hostKey);
            UNFORMED_SINCE.remove(hostKey);
            LAST_FORMED_MS.remove(hostKey);
            return;
        }
        restoreOne(level, hostKey, ledger, "restore_now");
    }
}
