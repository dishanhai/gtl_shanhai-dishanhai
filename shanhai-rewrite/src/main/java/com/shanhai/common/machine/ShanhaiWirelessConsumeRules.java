package com.shanhai.common.machine;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.UUID;

/**
 * 🔴 <b>「自持无线电网 handler 扣电」的纯判定核</b>——<b>零 Minecraft / 零 GT 依赖</b>
 * ⇒ 离线单测驱动的是<b>生产那一份</b>字节码，不是它的复制品。
 *
 * <h2>为什么把判定单独拆出来（本工程的老纪律）</h2>
 * 上一次"判定写在 {@code MachineTrait} 里"的代价是：离线只能验一个<b>替身</b>，
 * 替身与生产各写一遍算式 ⇒ 两边迟早分叉，而分叉时<b>两边都自检通过</b>。
 * ⇒ 算式全在这里（只有 4 个纯静态方法），生产侧 {@link ShanhaiSelfWirelessEnergyHandler}
 * 只负责"读池子余额 / 调上游写池子 / 打日志"这三件必须碰游戏的活。
 *
 * <h2>符号口径 = 上游原文（不是自造约定）</h2>
 * <pre>
 *   WirelessEnergyManager.addEUToGlobalEnergyMap(uuid, EU, machine)   ← 反编译原文（gtmthings 1.3.5.b）
 *     :  totalEU = max(0, GlobalEnergy.getOrDefault(teamUUID, 0))
 *     :  totalEU = totalEU.add(EU)
 *     :  if (totalEU.signum() &gt;= 0) { put(teamUUID, totalEU); return true; }
 *     :  else                        { return false; }        ← 余额会变负时【一个字节都不写】
 *   ⇒ <b>负 = 扣电</b>；池子不够时余额原样不动、返回 false。
 *   ⇒ 调用方（{@code ShanhaiWirelessWrap#consume}）传的正是负值
 *      （{@code WirelessGTRecipe} 的那个数由上游 {@code buildWirelessRecipe} 取过 {@code negate()}）。
 * </pre>
 *
 * <h2>🔴 为什么正数一律拒绝（{@code NOT_CONSUME}）</h2>
 * 这是「consume」：它只许减，不许加。上游 {@code addEUToGlobalEnergyMap} 对正数是<b>加</b>，
 * 若这里照抄"透传"，任何一个把正数误传给 consume 的调用点都会变成<b>凭空造电</b>（静默、且越用越多）。
 * ⇒ 本核在写法上把它堵死：正数一进来就 {@code false}（机器按"缺电"走既有语义），并让生产侧喊一次 WARN。
 *
 * <h2>与本核无关的那一半</h2>
 * 余额为 0 / uuid 为空时<b>不写池子</b>——那两种情况上游 {@code addEUToGlobalEnergyMap} 自己也会返回
 * false，但多走一次写入会白白 {@code markDirty}（存档变脏）⇒ 这里先短路。
 */
public final class ShanhaiWirelessConsumeRules {

    private ShanhaiWirelessConsumeRules() {
    }

    /**
     * 池子的<b>写入</b>操作（生产 = {@code WirelessEnergyManager.addEUToGlobalEnergyMap}；离线 = 替身）。
     *
     * <p>⚠️ 替身必须复刻上游那两条语义（否则离线读数没有意义）：负值扣电、<b>余额会变负时不写</b>。
     */
    public interface Pool {

        /** @return {@code true} = 这一笔被池子抄走了（余额已变）；{@code false} = 池子拒绝（余额一个字节没动） */
        boolean write(@NotNull UUID key, @NotNull BigInteger delta);
    }

    /** 一次"扣电"的五种去向（<b>离线判据读的就是它</b>，不是日志里的措辞）。 */
    public enum Verdict {

        /** 没有池子键（uuid 为空）⇒ 一个字节不碰。 */
        NO_KEY,
        /** 数额为空 ⇒ 一个字节不碰（上游不会这样调；防 NPE）。 */
        NO_AMOUNT,
        /** 数额 &gt; 0 ⇒ 不是"耗电"，<b>拒绝</b>（见类注释：透传就会凭空造电）。 */
        NOT_CONSUME,
        /** 数额 = 0 ⇒ 无事可做，等价于成功（不碰池子、不 markDirty）。 */
        NO_OP,
        /** 池子抄走了这一笔（余额已按 {@code delta} 变化）。 */
        WRITTEN,
        /** 池子拒绝（余额不足 / 上游异常）⇒ <b>余额一个字节没动</b>，调用方走既有"缺电"语义。 */
        REFUSED;

        /** {@code true} = 这一次扣电对调用方算"成功"（{@code WRITTEN} 与"无事可做"的 {@code NO_OP}）。 */
        public boolean accepted() {
            return this == WRITTEN || this == NO_OP;
        }
    }

    /**
     * 纯判定（不碰池子）。
     *
     * @param key    池子键（gtmthings 内部再按 {@code TeamUtil.getTeamUUID(key)} 折成"队伍池"）
     * @param amount <b>负数 = 扣电</b>；0 = 无事可做；正数 = 拒绝
     */
    public static @NotNull Verdict decide(@Nullable UUID key, @Nullable BigInteger amount) {
        if (key == null) {
            return Verdict.NO_KEY;
        }
        if (amount == null) {
            return Verdict.NO_AMOUNT;
        }
        final int sign = amount.signum();
        if (sign > 0) {
            return Verdict.NOT_CONSUME;
        }
        if (sign == 0) {
            return Verdict.NO_OP;
        }
        return Verdict.WRITTEN; // 只是"该写"，真正的结果由 consume 换成 WRITTEN/REFUSED
    }

    /**
     * 唯一的扣电入口：判定 → 该写才写。
     *
     * <p>{@code decide} 返回 {@code WRITTEN} 时这里才调 {@link Pool#write}，并按池子的回答把它换成
     * {@code WRITTEN}（写了）或 {@code REFUSED}（池子不够 ⇒ 余额一个字节没动）。
     */
    public static @NotNull Verdict consume(@NotNull Pool pool,
                                           @Nullable UUID key,
                                           @Nullable BigInteger amount) {
        final Verdict decision = decide(key, amount);
        if (decision != Verdict.WRITTEN) {
            return decision;
        }
        return pool.write(key, amount) ? Verdict.WRITTEN : Verdict.REFUSED;
    }

    /**
     * 在线判据（= 伪神 {@code SelfWirelessNetworkHandler#isOnline} 的口径，逐条同源）：
     * <b>有键 ∧ 余额 &gt; 0</b>。
     *
     * <p>⚠️ 余额为 {@code null} 一律当 0（上游 {@code getUserEU} 现在<b>不会</b>返回 null，
     * 这一层只是不让"上游哪天改了"变成 NPE）。
     */
    public static boolean isOnline(@Nullable UUID key, @Nullable BigInteger balance) {
        return key != null && balance != null && balance.signum() > 0;
    }

    /** 可拉取量（= 伪神 {@code maxAvailableEnergy} 的口径）：没有键 / 读不到 ⇒ {@code ZERO}。 */
    public static @NotNull BigInteger available(@Nullable BigInteger balance) {
        return balance == null ? BigInteger.ZERO : balance;
    }
}
