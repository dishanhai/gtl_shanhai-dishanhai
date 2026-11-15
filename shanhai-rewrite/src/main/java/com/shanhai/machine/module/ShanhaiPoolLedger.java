package com.shanhai.machine.module;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 🔴 <b>无线池「我们自己这一侧」的入池台账 —— 只记账，一个字节都不参与任何数值判断。</b>
 *
 * <h2>为什么要有它（2026-11-18 用户报「池审计差额报红」的根因）</h2>
 * 池审计（{@code PrimordialModuleMachine#shanhai$auditPool}）本来想回答一个问题：
 * <pre>
 *   Δ池 − 我们入池了多少 = 别人（同队伍其它机器/终端）的净额
 * </pre>
 * 而它原来的「我们入池了多少」只有一个来源：<b>那台机器自己的</b>
 * {@code shanhai$auditMine}（{@code PrimordialModuleMachine.java:2639}）。
 * 但池子是按<b>队伍/玩家 uuid 共享</b>的，同一池子里<b>我们自己的另一台机器也在写</b>：
 * 归墟视界枢核的零点能发电（{@code GuixuVisionCoreMachine#shanhai$depositGeneration}，
 * 实测 {@code 乘积=40564819207303340707157014216704} per tick，比发电模块自己的
 * {@code 19807040619342710626364425} per tick 大 6 个数量级）。
 *
 * <p>⇒ 那一台写进池子的量<b>全部</b>被算进了「别人的收支」⇒ 差额 = 2.4e32 级 ⇒ 每 5 秒一条 WARN。
 * 这不是池子漏，也不是 Σ 少算了某个因子；是<b>口径</b>：把"我们"写成了"这一台"。
 *
 * <h2>本类做什么</h2>
 * 所有「我们自己的」入池路径在同一处登记（{@link #note}）：
 * <ul>
 *   <li>发电模块 {@code PrimordialModuleMachine#shanhai$depositGenerationToWirelessPool}；</li>
 *   <li>归墟视界枢核 {@code GuixuVisionCoreMachine#shanhai$depositGeneration}。</li>
 * </ul>
 * 审计侧用 {@link #sum} 取「这一池子里我们自己一共入池了多少 / 几笔 / 最近一笔多少」，
 * 于是 {@code Δ池 − Σ(我们全部)} 才真的是"读不到名字的第三方写入"。
 *
 * <h2>🔴 边界（写死，别扩）</h2>
 * <ol>
 *   <li><b>只增不改</b>：本类不改变任何一笔入池的金额、不拦/不放任何写入、不影响配方与并行；</li>
 *   <li><b>只读快照</b>：审计只看累计值之差（{@code now − baseline}），不改调用方的窗口语义；</li>
 *   <li>异常一律吞掉（记账坏了不许连坐机器）——调用点的 try/catch 就是最后一层。</li>
 *   <li>线程：<b>全部方法 synchronized</b>。入池都在服务端主线程，但审计/自检可能在别的时机读。</li>
 * </ol>
 *
 * <h2>键</h2>
 * 键 = 我们传给 {@code WirelessEnergyManager.addEUToGlobalEnergyMap(uuid, …)} 的那个 {@code uuid}。
 * 为了让"同一队伍、不同归属 uuid"的两台机器也并到同一本账上，{@link #note} 会<b>同时</b>记一份
 * {@code TeamUtil.getTeamUUID(uuid)}（取不到就只记原始键）；{@link #sum} 取用时<b>按键去重</b>再相加。
 */
public final class ShanhaiPoolLedger {

    private ShanhaiPoolLedger() {
    }

    /**
     * 一个键的累计读数（不可变快照）。
     *
     * <p>{@code longClampedTotal} 是"若走 long 重载，池子实际会收到多少"（= Σ min(xᵢ, Long.MAX)），
     * 与 {@code PrimordialModuleMachine#shanhai$auditMineLongClamped} 同一个口径，
     * 只是现在覆盖<b>我们全部</b>入池路径。
     */
    public static final class Snapshot {

        /** 累计入池量（BigInteger 精确相加）。 */
        public final BigInteger total;
        /** 累计"被 long 夹之后"的量（只用于 long 重载的逐位预报）。 */
        public final BigInteger longClampedTotal;
        /** 累计入池笔数。 */
        public final long count;
        /** 最近一笔入池量（用户口径里的「本次」= 乘积）。 */
        public final BigInteger lastAmount;
        /** 最近一笔是谁写的（人读用，例如 {@code 发电模块{pos}}）。 */
        public final String lastWho;

        Snapshot(@NotNull BigInteger total, @NotNull BigInteger longClampedTotal, long count,
                 @NotNull BigInteger lastAmount, @NotNull String lastWho) {
            this.total = total;
            this.longClampedTotal = longClampedTotal;
            this.count = count;
            this.lastAmount = lastAmount;
            this.lastWho = lastWho;
        }

        /** 空账（键不存在 / 还没人写过）。 */
        public static final Snapshot EMPTY =
                new Snapshot(BigInteger.ZERO, BigInteger.ZERO, 0L, BigInteger.ZERO, "（还没有任何一笔）");

        /**
         * 把一个余额口径的 key 变成"这一池子全部我们自己的入池"（{@code a} 与 {@code b} 相同则只取一次）。
         */
        static Snapshot cancel(@Nullable Snapshot a, @Nullable Snapshot b) {
            final Snapshot x = a == null ? EMPTY : a;
            final Snapshot y = b == null ? EMPTY : b;
            return new Snapshot(x.total.add(y.total), x.longClampedTotal.add(y.longClampedTotal),
                    x.count + y.count,
                    y.count > 0L ? y.lastAmount : x.lastAmount,
                    y.count > 0L ? y.lastWho : x.lastWho);
        }
    }

    /** 一个键的可变累计。 */
    private static final class Entry {
        BigInteger total = BigInteger.ZERO;
        BigInteger longClampedTotal = BigInteger.ZERO;
        long count;
        BigInteger lastAmount = BigInteger.ZERO;
        String lastWho = "?";
    }

    /** 账本本体（键 = 池子 uuid / 队伍 uuid）。 */
    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    /**
     * 记一笔<b>我们自己的</b>入池。
     *
     * @param poolKey 传给 {@code addEUToGlobalEnergyMap} 的那个 uuid（{@code null} ⇒ 什么都不记）
     * @param amount  这一笔的量（{@code null} / {@code <= 0} ⇒ 什么都不记）
     * @param who     人读的来源（只会出现在日志里；不要放金额）
     */
    public static synchronized void note(@Nullable UUID poolKey, @Nullable BigInteger amount,
                                         @Nullable String who) {
        if (poolKey == null || amount == null || amount.signum() <= 0) {
            return;
        }
        addTo(poolKey, amount, who);
        final UUID teamKey = teamOf(poolKey);
        if (teamKey != null && !teamKey.equals(poolKey)) {
            addTo(teamKey, amount, who);
        }
    }

    /**
     * 取"这一个池子里我们自己的全部入池"（原始键 + 队伍键，去重相加）。
     *
     * @param poolKey 与 {@link #note} 同一个键（{@code null} ⇒ {@link Snapshot#EMPTY}）
     */
    @NotNull
    public static synchronized Snapshot sum(@Nullable UUID poolKey) {
        if (poolKey == null) {
            return Snapshot.EMPTY;
        }
        final Snapshot raw = snapshotOf(poolKey);
        final UUID teamKey = teamOf(poolKey);
        if (teamKey == null || teamKey.equals(poolKey)) {
            return raw;
        }
        return Snapshot.cancel(raw, snapshotOf(teamKey));
    }

    /** 只取某一个键（不含队伍合并）—— 自检与日志排查用。 */
    @NotNull
    public static synchronized Snapshot snapshotOf(@Nullable UUID key) {
        if (key == null) {
            return Snapshot.EMPTY;
        }
        final Entry e = ENTRIES.get(key);
        if (e == null) {
            return Snapshot.EMPTY;
        }
        return new Snapshot(e.total, e.longClampedTotal, e.count, e.lastAmount, e.lastWho);
    }

    /** 清空（<b>只给自检/单测用</b>；生产路径没有任何调用点）。 */
    public static synchronized void clear() {
        ENTRIES.clear();
    }

    private static void addTo(@NotNull UUID key, @NotNull BigInteger amount, @Nullable String who) {
        final Entry e = ENTRIES.computeIfAbsent(key, k -> new Entry());
        e.total = e.total.add(amount);
        e.longClampedTotal = e.longClampedTotal.add(PrimordialPoolBand.longClamped(amount));
        e.count++;
        e.lastAmount = amount;
        e.lastWho = who == null || who.isEmpty() ? "?" : who;
    }

    /** 队伍键（取不到就 {@code null} —— 记不上不是错误）。 */
    @Nullable
    private static UUID teamOf(@Nullable UUID key) {
        if (key == null) {
            return null;
        }
        try {
            return com.hepdd.gtmthings.utils.TeamUtil.getTeamUUID(key);
        } catch (Throwable t) {
            return null;
        }
    }
}
