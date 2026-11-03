package com.shanhai.common.recipe;

import com.shanhai.common.log.ShanhaiLogThrottle;
import com.shanhai.common.thread.ShanhaiParallelBudget;

import java.math.BigInteger;

/**
 * 🔴 <b>并行诊断的两条纯判定（零 Minecraft / 零 GT 依赖，可被裸 {@code javac} 单独编译并离线驱动）。</b>
 *
 * <h2>本类承载的两件事（都是"判据"，不是"日志"）</h2>
 * <ol>
 *   <li><b>① 三分限流（{@code in / out / budget}）</b> —— 一台机器这一轮的并行到底被<b>谁</b>压住：
 *       输入量（{@code IParallelLogic.getMaxParallel}）、输出空间（{@code getMinParallel}）、
 *       还是本次传进去的预算（{@code budget}）。<b>三者取 min = pick，最小值那一项 = {@code why}</b>。</li>
 *   <li><b>② 每配方并行预算（"照伪神"口径）</b> —— gtladditions「伪神之锻炉」
 *       {@code ForgeOfTheAntichristLogic#calculateParallels} 的写法是
 *       {@code limit = 9223372036854775807L; p = getMaxParallel(recipe, limit);}
 *       —— <b>每配方直接吃 {@code Long.MAX}，且【不乘】跨配方线程数</b>。
 *       本方法把那条口径落成纯算术（{@link #perRecipeBudget}），生产侧
 *       {@code ParallelOverrideMachine#perRecipeParallelBudget()} 只负责取机器上的四个读数。</li>
 * </ol>
 *
 * <h2>🔴 为什么判定必须住在"纯 JDK"类里（本工程的既有纪律）</h2>
 * 「凡我自己写的检查脚本报出的结果（PASS 或 FAIL），采信前必须先证明脚本自己是对的」
 * ⇒ 若判定写在带 Minecraft 依赖的类里，离线自证只能<b>照抄一遍</b>判定
 * ⇒ "抄错的到底是脚本还是生产代码"永远分不清（本工程 2026-09-20 同族撞过 7 次）。
 * ⇒ <b>游戏里跑的是本类的同一份字节码</b>，离线判据驱动的也是它，不存在第二份判定。
 *
 * <h2>⚠️ 本类<b>不</b>做的事</h2>
 * <ul>
 *   <li>不打日志（只造行文本）—— 级别与落盘由调用方（{@code ShanhaiMod.LOGGER}）决定；</li>
 *   <li>不碰 {@code Gate} 的限流状态（闸门由调用方持有，本类只给形态串）；</li>
 *   <li>不改任何数值：{@link #pick} 只是读数，{@link #perRecipeBudget} 只是"预算"，</li>
 * </ul>
 */
public final class ShanhaiParallelDiag {

    private ShanhaiParallelDiag() {}

    /** 与 {@link ShanhaiInfiniteEnergy#TAG} 同一个前缀（一次 {@code grep SHANHAI-INFPAR} 把这几族行全捞出来）。 */
    public static final String PREFIX = "[SHANHAI-INFPAR]";

    /** 三分诊断那一行的限流窗（毫秒）：同一形态最多 5 秒复述一次。 */
    public static final long PICK_MIN_INTERVAL_MILLIS = 5_000L;

    // ═══════════════════════ ① 三分限流：in / out / budget ═══════════════════════

    /** 并行被谁压住。<b>顺序即"平局时的优先级"</b>（in → out → budget）。 */
    public enum Why {

        /** 输入量（{@code getInputItemParallel} / {@code getInputFluidParallel} 取 min 之后的结果）。 */
        IN("in"),
        /** 输出空间（{@code IParallelLogic.getMinParallel}）。 */
        OUT("out"),
        /** 本次传进去的预算（{@code budget}，主机 = {@link #perRecipeBudget}，模块 = 并行表 × T）。 */
        BUDGET("budget");

        /** 写进日志的那个 token（{@code why=in|out|budget}）。 */
        public final String token;

        Why(String token) {
            this.token = token;
        }
    }

    /**
     * 一次三分诊断的读数（纯数据）。
     *
     * @param in     输入量上限（原值，可能 &lt; 0 = 读不到）
     * @param out    输出空间上限（原值，可能 &lt; 0 = 没算这一步）
     * @param budget 本次预算（原值，可能 &lt; 0 = 没有预算）
     * @param pick   三者取 min（&lt; 0 = 三项全都读不到）
     * @param why    三者里最小的那一项（平局按 in → out → budget；三者全读不到 = {@code null}）
     */
    public record Pick(long in, long out, long budget, long pick, Why why) {

        /** 日志里那一段可读读数（不含前缀与机器名）。 */
        public String describe() {
            return "in=" + text(in) + " out=" + text(out) + " budget=" + text(budget)
                    + " pick=" + text(pick) + " why=" + (why == null ? "?" : why.token);
        }
    }

    /**
     * <b>三分诊断的唯一算式</b>：{@code pick = min(in, out, budget)}，
     * {@code why} = 三者里最小的那一项（平局按 {@code in → out → budget}）。
     *
     * <p>负数（哨兵 / "这一项没算"）<b>不参与比较</b>，而不是当成 0 ——
     * 因为"读不到"绝不能当成"上限很小"（那会让 {@code why} 指错人），
     * 也绝不能当成"上限很大"（那会让 {@code why} 漏掉真凶）。诊断撒谎比没有诊断更糟。
     *
     * @param byInput  {@code IParallelLogic.getMaxParallel(...)}；&lt; 0 = 没取到
     * @param byOutput {@code IParallelLogic.getMinParallel(...)}；&lt; 0 = 没取到（例如输入量为 0 时不算这一步）
     * @param budget   本次预算；&lt; 0 = 没有预算这个概念
     */
    public static Pick pick(long byInput, long byOutput, long budget) {
        long best = Long.MAX_VALUE;
        Why why = null;
        if (byInput >= 0L) {
            best = byInput;
            why = Why.IN;
        }
        if (byOutput >= 0L && byOutput < best) {
            best = byOutput;
            why = Why.OUT;
        }
        if (budget >= 0L && budget < best) {
            best = budget;
            why = Why.BUDGET;
        }
        return new Pick(byInput, byOutput, budget, why == null ? -1L : best, why);
    }

    /**
     * <b>该不该打这一行</b>：只在这台机器的 {@code pick} <b>明显小于</b> {@code budget} 时才打。
     *
     * <p>「明显小于」的口径 = <b>{@code pick <= budget / 2}</b>（连一半都不到）：
     * <ul>
     *   <li>{@code pick == budget}（预算就是瓶颈，机器正在吃满它给的额度）⇒ <b>不打</b>
     *       —— 这属于"机器正常在跑"，打了就是刷屏；</li>
     *   <li>{@code pick} 只比 {@code budget} 小一点点 ⇒ 也不打（同样的理由）；</li>
     *   <li>⇒ 落盘的只剩"被输入量 / 输出空间摁住"的那一档，正是要找的真相。</li>
     * </ul>
     * ⚠️ <b>纯算术，无符号陷阱</b>：{@code budget <= 0} 时直接返回 {@code false}（没有预算就没有"小于"）。
     */
    public static boolean obviousBelowBudget(Pick p) {
        if (p == null || p.budget() <= 0L || p.pick() < 0L) {
            return false;
        }
        return p.pick() <= p.budget() / 2L;
    }

    /**
     * 三分诊断那一行的<b>形态串</b>（供 {@link ShanhaiLogThrottle.Gate} 去重 / 限流）。
     *
     * <h2>🔴 为什么形态里要把数值压成「2 的幂次桶」</h2>
     * 主机一轮的候选配方可能<b>几百条</b>，每条都会来一次 {@code calculateParallel}。
     * 若形态里放<b>精确</b>数值，几百条候选 = 几百个形态 ⇒ 闸门要么全放（刷屏）、要么全吞（看不见）。
     * 压成桶之后，"同一个瓶颈、同一个量级"的候选合并成一族 ⇒ <b>一台机器只有几个形态</b>，
     * 而<b>真正落盘的那一行里仍然是精确数字</b>（形态只用于去重，不用于显示）。
     *
     * <p>桶 = {@code floor(log2(v))}（{@code 0} / 负数单独一桶）⇒ 覆盖 1 … 2^62 共 63 档。
     * 数值跨越量级（例如料从 1e6 变成 1e12）时桶会变 ⇒ <b>状态变化照旧看得见</b>。
     */
    public static String pickShape(String machineId, Pick p) {
        if (p == null) {
            return "pmax|x=" + machineId + "|?";
        }
        return "pmax|x=" + machineId
                + "|why=" + (p.why() == null ? "?" : p.why().token)
                + "|in~" + bucket(p.in()) + "|out~" + bucket(p.out()) + "|budget~" + bucket(p.budget());
    }

    /** {@code floor(log2(v))} 的桶名（{@code v <= 0} ⇒ {@code "0"}）。只给形态串用，不参与判定。 */
    static String bucket(long v) {
        return v <= 0L ? "0" : Long.toString(63L - Long.numberOfLeadingZeros(v));
    }

    /**
     * 三分诊断那一行的<b>正文</b>（任务书给死的形状，一个字段都不许少）：
     * <pre>
     *   [SHANHAI-INFPAR] pmax machine=&lt;id&gt; in=&lt;byInput&gt; out=&lt;byOutput&gt; budget=&lt;limit&gt; pick=&lt;min&gt; why=&lt;in|out|budget&gt; inItem=&lt;…&gt; inFluid=&lt;…&gt;
     * </pre>
     * <p>后半截 {@code inItem / inFluid} 是<b>刻意追加</b>的：用户 AE 里可能有 &gt;9.2e 的料，
     * 而 {@code getContents()} 报出来的未必是这个量 —— 这两格就是用来照出真相的
     * （{@code getInputItemParallel} 与 {@code getInputFluidParallel} 各自的值）。
     * @param inItem  {@code IParallelLogic.getInputItemParallel(...)}；&lt; 0 = 没取到 ⇒ 打 {@code ?}
     * @param inFluid {@code IParallelLogic.getInputFluidParallel(...)}；&lt; 0 = 没取到 ⇒ 打 {@code ?}
     */
    public static String pickLine(String machineId, Pick p, long inItem, long inFluid) {
        return PREFIX + " pmax machine=" + (machineId == null ? "?" : machineId)
                + ' ' + (p == null ? "in=? out=? budget=? pick=? why=?" : p.describe())
                + " inItem=" + text(inItem) + " inFluid=" + text(inFluid);
    }

    // ═══════════════════════ ①-b 输出空间钳制：走 int 还是走 long 孪生 ═══════════════════════

    /**
     * 🔴 <b>「这一步要不要换 long 孪生」的唯一判据。</b>
     *
     * <pre>
     *   inputLimited ≤ Integer.MAX_VALUE (2^31−1) ⇒ <b>不换</b>：原样调用上游 int 版
     *        （同一处 invoke、同一份字节码 ⇒ 老路径【逐位不变】，这是硬约束）
     *   inputLimited &gt;  Integer.MAX_VALUE        ⇒ <b>换</b>：调
     *        {@code PrimordialRecipeEffects#limitByOutputMergingLong}
     * </pre>
     *
     * <h2>为什么要换（病根）</h2>
     * 上游 {@code ParallelLogic#limitByOutputMerging(GTRecipe, IRecipeCapabilityHolder, <b>int</b>,
     * Predicate) : <b>int</b>}（gtlcore 用 {@code ParallelLogicMixin} @Overwrite 了实现，<b>签名没改</b>）
     * ⇒ 主机这条 long 预算的路只要经过它，并行就被<b>结构性地</b>压回 2147483647
     * （实机日志 {@code p=2147483647} 就是它）。
     *
     * @param inputLimited 输入量上限（{@code IParallelLogic.getMaxParallel} 的 long 返回值）
     */
    public static boolean needsLongOutputClamp(long inputLimited) {
        return inputLimited > ShanhaiLogThrottle.INT_CEILING;
    }

    /**
     * <b>「输入上限」与「输出上限」合一</b>：{@code max(0, min(in, out))} ——
     * 与上游 {@code Math.max(0L, Math.min(inputLimited, (long) outputLimited))} 逐位同形
     * （主机 {@code calculateParallel} 那一句原来的写法，这里只是把类型从 int 抬成 long）。
     *
     * <p>离线判据用它证明"p = 5e9 这种值在我们这一层不会被打回 int"
     * （见 {@code temp/infinity2/check/BudgetMatrix.java} 的 ⑤ 段）。
     */
    public static long combineInOut(long inputLimited, long outputLimited) {
        return Math.max(0L, Math.min(inputLimited, outputLimited));
    }

    // ═══════════════════════ ② 每配方并行预算（照伪神） ═══════════════════════

    /**
     * 🔴 <b>「每配方并行预算」的唯一一份算术 —— 照伪神（FOTC）的写法。</b>
     *
     * <h2>伪神原文（字节码，取证见 {@code temp/infinity2/取证.md} F2）</h2>
     * <pre>
     *   ForgeOfTheAntichrist$Companion$ForgeOfTheAntichristLogic#calculateParallels
     *     170: ldc2_w #162   // long 9223372036854775807l
     *     173: lstore 18
     *     194: invokestatic access$getMaxParallel(this, recipe, Long.MAX_VALUE)
     * </pre>
     * ⇒ <b>每配方预算直接 {@code Long.MAX}，线程数完全不进这条计算</b>
     * （它绕开了 {@code (long) getMaxParallel() * getMultipleThreads()} 这条乘法，
     *  所以伪神那边<b>根本没有"并行 × 线程"溢出这件事</b>）。
     *
     * <h2>本方法的四条（顺序写死）</h2>
     * <pre>
     *   ① 基座      = Long.MAX_VALUE                      ← 伪神 F2；【不乘 T】
     *   ② 玩家覆盖值（{@code 0} = 自动 ⇒ 本段不参与）：
     *        budget = saturatedMultiply(min(覆盖值, 自动值), T)
     *        —— 只对【有限的玩家值】乘，且走饱和乘法（乘出来超 long ⇒ 夹成 Long.MAX，绝不回绕成负数）。
     *           Long.MAX 本身<b>一次都不进</b>这条乘法（硬约束：不许把 Long.MAX 预算再乘线程）。
     *   ③ 电上限（【总】口径：k × 预算 ≤ P）     ⇒ budget = min(budget, 电上限)
     *   ④ long 溢出安全夹（没有 BigInteger 通道时）：budget = min(budget, Long.MAX ÷ 每份耗电)
     *        —— 与 {@code ParallelOverrideMachine#applyEnergyCap} 里那道夹是<b>同一个纯函数</b>
     *           （{@code ShanhaiInfiniteEnergy.guardInfiniteCap}），口径为 T = 1（总预算不除以线程）。
     * </pre>
     *
     * <h2>为什么③④必须留着（不是"顺手夹一下"）</h2>
     * <ul>
     *   <li>③ 是「电力自动」那条已验收功能的落点：去掉它 ⇒ 机器按输入量吃满并行 ⇒
     *       总耗电 &gt; 能源仓总功率 ⇒ <b>上游原样 {@code FAIL_NO_ENOUGH_EU_IN} + doDamping</b>
     *       ⇒ 表现为"机器不转"（比"并行小"更糟）。</li>
     *   <li>④ 是「∞ 电源也不许把 long 撑爆」那条红线的落点：{@code p × 每份耗电} 用 long 只能饱和/回绕。</li>
     * </ul>
     *
     * <h2>不变式（离线判据逐条断言，见 {@code temp/infinity2/check/BudgetMatrix.java}）</h2>
     * <pre>
     *   (自动 = Long.MAX, 覆盖 = 0, T = 128) ⇒ Long.MAX     ← T 不参与
     *   (自动 = Long.MAX, 覆盖 = 0, T =   1) ⇒ Long.MAX
     *   (覆盖 = 2^40,      T = 128)          ⇒ 2^47          ← 该乘的地方照常乘
     *   (覆盖 = Long.MAX, T = 128)           ⇒ Long.MAX     ← 饱和，不是 -128
     * </pre>
     *
     * @param autoParallel          机器自带的并行（未覆盖时的值；主机 = {@code Long.MAX}）
     * @param overrideValue         玩家手填的覆盖值（{@link ParallelOverrideMachine#PARALLEL_AUTO} = 0 = 自动）
     * @param threads               跨配方线程数 T（≤ 0 按 1 算；<b>只乘"玩家覆盖值"那一路</b>）
     * @param energyCapTotal        本轮电力上限（<b>总</b>口径）；≤ 0 = 无限/没算出
     * @param euPerCopy             每份耗电（{@code BigInteger}）；{@code null} / ≤ 0 = 不夹
     * @param bigintChannelUsable   true = 有 BigInteger 扣电通道（无线 handler 在线）⇒ ④ 不参与
     * @return 预算（恒 ≥ 1）
     */
    public static long perRecipeBudget(long autoParallel, long overrideValue, int threads,
                                       long energyCapTotal, BigInteger euPerCopy,
                                       boolean bigintChannelUsable) {
        // ① 基座：伪神 F2 —— 每配方预算 = Long.MAX，T 不参与。
        long budget = Long.MAX_VALUE;
        // ② 玩家手填的覆盖值（只有"电力自动关着"时才会 > 0，判据在 getEffectiveOverride()）。
        if (overrideValue > 0L) {
            final long perMachine = Math.min(overrideValue, Math.max(1L, autoParallel));
            budget = ShanhaiParallelBudget.saturatedMultiply(perMachine, Math.max(1, threads));
        }
        // ③ 电上限：总口径（总耗电 = k × 预算 ≤ P）。
        if (energyCapTotal > 0L) {
            budget = Math.min(budget, energyCapTotal);
        }
        // ④ long 溢出安全夹（总口径 ⇒ T = 1；有 BigInteger 通道时不夹）。
        if (!bigintChannelUsable && euPerCopy != null && euPerCopy.signum() > 0) {
            budget = ShanhaiInfiniteEnergy.guardInfiniteCap(budget, euPerCopy, 1);
        }
        return Math.max(1L, budget);
    }

    // ═══════════════════════ ③ 发电撞顶：按【乘积】判档 ═══════════════════════

    /** 这一档是不是"乘积已经越过 long"。 */
    public enum WallTier {

        /** 乘积装得进 long（老路径）—— 这一档还撞顶就是<b>预期外</b>。 */
        INT("int"),
        /** 乘积溢出 long（long / BIGINT 档）—— 撞顶是<b>预期内</b>（用户已拍板接受）。 */
        LONG("long");

        /** 写进日志 / 形态串的 token。 */
        public final String token;

        WallTier(String token) {
            this.token = token;
        }
    }

    /**
     * 🔴 <b>撞顶判档：按"这一乘会不会溢出 long"判，<u>不</u>按 {@code p} 的数值档位判。</b>
     *
     * <h2>它修的是什么（2026-11 用户实机日志原文）</h2>
     * <pre>
     *   [SHANHAI-GEN-LIMIT] 🔴 出乎预期的撞顶：p=2147483647 未进 long 档（≤ 2147483647）
     *   却仍读到 tickOutputs.eu=9223372036854775807（乘前 2748779069…）
     * </pre>
     * 旧判据是 {@code longScale = parallel > Integer.MAX_VALUE} ⇒
     * {@code p == Integer.MAX_VALUE} 这一档被判成"未进 long 档"，
     * 但<b>它的乘积早就越过了 long</b>（{@code 2.1e9 × 2.7e12 ≈ 5.9e21}）⇒ 撞顶完全正常 ⇒ 假警报。
     * <p>新判据只看一件事：<b>{@code p × 乘前} 会不会溢出 long</b>
     * （{@code p > Long.MAX / 乘前}）。{@code p} 是 int 还是 long 与它无关。
     *
     * @param parallel 本次并行数 p
     * @param beforeEu 乘之前的每条 tickOutputs 电量（&lt; 0 = 读不到）
     * @return {@link WallTier#LONG} = 乘积溢出 long（撞顶属预期）；{@link WallTier#INT} = 装得下
     */
    public static WallTier wallTier(long parallel, long beforeEu) {
        if (parallel <= 0L || beforeEu <= 0L) {
            // 读不到乘前值 ⇒ 退回既有的"按 p 的数值档位"判（此时没有乘积可算，只能这样）。
            return parallel > ShanhaiLogThrottle.INT_CEILING ? WallTier.LONG : WallTier.INT;
        }
        return parallel > Long.MAX_VALUE / beforeEu ? WallTier.LONG : WallTier.INT;
    }

    /** 撞顶 + 落在 {@link WallTier#INT} ⇒ <b>预期外</b>（该 WARN）；其余都不是异常。 */
    public static boolean wallIsUnexpected(long afterEu, WallTier tier) {
        return afterEu == Long.MAX_VALUE && tier == WallTier.INT;
    }

    /** {@code p × beforeEu} 的精确值（只用于日志措辞；溢出由 {@code BigInteger} 表达，零回绕）。 */
    public static BigInteger wallProduct(long parallel, long beforeEu) {
        if (parallel <= 0L || beforeEu <= 0L) {
            return BigInteger.ZERO;
        }
        return BigInteger.valueOf(parallel).multiply(BigInteger.valueOf(beforeEu));
    }

    // ═══════════════════════ 小工具 ═══════════════════════

    /** 负数（哨兵 / 读不到）打成 {@code ?} —— <b>绝不许把 -1 这种会误导人的值印出去</b>。 */
    public static String text(long value) {
        return value < 0L ? "?" : Long.toString(value);
    }
}
