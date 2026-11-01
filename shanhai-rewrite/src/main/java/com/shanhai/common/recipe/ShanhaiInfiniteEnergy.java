package com.shanhai.common.recipe;

import com.shanhai.common.log.ShanhaiLogThrottle;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 🔴 <b>「电」这一格的纯核：{@code 每份耗电 × 并行 × 跨配方线程} 装不装得进 {@code long} 的判定、
 * 夹并行、以及两条可 grep 的证据行。</b>
 *
 * <h2>为什么需要它（问题陈述，一句话）</h2>
 * 并行份数早就是 {@code long}（见 {@code ShanhaiLongParallelMultiply} 那一刀的「数量」通道），
 * 但<b>电是另一个量</b>：{@code EUt × p × T} 一旦超过 {@code Long.MAX}，
 * ① 任何 {@code long} 乘法都只能饱和（回绕成负数更糟），
 * ② GTCEu 的扣电通道是 {@code List<Long>}（{@code EURecipeCapability} 的 {@code Content} 里装的是 {@code Long}），
 * <b>结构上就表达不了更大的数</b>。⇒ 超过必须在<b>另一条通道</b>上走。
 *
 * <h2>上游现成的那条通道（本类只做判定，不做通道）</h2>
 * <pre>
 *   ① {@code WirelessGTRecipe(GTRecipe base, BigInteger euPerTick)}
 *        —— 上游现成构造器，把普通成品原样包一层，并挂上 {@code BigInteger} 的每 tick 耗电；
 *   ② {@code MutableRecipesLogic#handleMultipleRecipeWorking()}（我们的父类，字节码实读）：
 *        {@code lastRecipe instanceof IWirelessGTRecipe ? handleWirelessTickInput(w)
 *                                                      : handleTickRecipe(lastRecipe).isSuccess()}
 *        ⇒ <b>两支互斥</b>：走无线支时 {@code handleTickRecipe} 一次都不调 ⇒ 能源仓一次都不扣
 *          （这就是「不许双扣」的结构性保证，不是靠我们记得）；
 *   ③ 扣失败 ⇒ 上游原样 {@code setWaiting(FAIL_NO_ENOUGH_EU_IN.reason())} + {@code doDamping()}
 *        ⇒ <b>「缺电」语义一个字节都没新造</b>。
 * </pre>
 *
 * <h2>本类<b>不</b>做什么</h2>
 * <ul>
 *   <li>不碰 Minecraft / Forge / GT 的任何类（<b>纯 JDK</b>）⇒ 可以脱离游戏离线跑断言，
 *       见 {@code temp/infpar/InfParEnergyCheck.java}；</li>
 *   <li>不打日志（只<b>造行文本</b>）⇒ 级别与落盘由调用方（{@code ShanhaiMod.LOGGER}）决定；</li>
 *   <li>不做任何"猜测"：所有判定都是 {@link BigInteger} 精确比较，没有一处 {@code double} 参与判定
 *       （{@link #applyFactor} 是「复刻 long 路的取整」用的，且只在<b>已经进了 bigint 档</b>时调，
 *       它<b>不参与</b>"装不装得下"这个判定）。</li>
 * </ul>
 *
 * <h2>🔴 口径（本类最容易读错的一格）</h2>
 * 生产侧喂进来的 {@code euPerCopy} 有两种来源，两种都合法，且判定量始终是同一个：
 * <pre>
 *   ① 三因子形式：total = euPerCopy × parallels × threads
 *      （T = 1 时的模块路径、以及纯核自证的 8 组读数，都是这一形式，与用户口径逐字一致）
 *   ② 直接给精确总量：{@link #decide(BigInteger, long, int, boolean)}
 *      （T &gt; 1 时各 origin 的每份耗电/份数可以不等，此时"三因子"是拆不出来的，
 *        生产侧直接给 Σ_i EUt_i × p_i 这个精确量；T = 1 时它与 ① 逐位相等）
 * </pre>
 * <b>两种来源用的是同一个谓词 {@link #fitsLong(BigInteger)}</b> ⇒ 自证覆盖的就是生产的那一个判定。
 */
public final class ShanhaiInfiniteEnergy {

    private ShanhaiInfiniteEnergy() {
    }

    /** 证据行前缀（进游戏后 {@code grep SHANHAI-INFPAR} 就能把这两族行全捞出来）。 */
    public static final String TAG = "[SHANHAI-INFPAR]";

    /** 同一形态的复述间隔：<b>5 秒 1 行</b>（父代理任务书的限流口径）。 */
    public static final long MIN_INTERVAL_MILLIS = 5_000L;

    /** {@code long} 能装下的最大值（写成常量，免得每处 {@code Long.MAX_VALUE} 与 {@code BigInteger} 互转时手滑）。 */
    public static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** 判定结果的三档。 */
    public enum Mode {
        /** 装得下 ⇒ <b>走完全原样的老路径</b>（能源仓扣电，一个字节都不改）。 */
        LONG,
        /** 装不下 + 无线 handler 可用 ⇒ 包 {@code WirelessGTRecipe}，走 {@code consumeEnergy(BigInteger)}。 */
        BIGINT,
        /** 装不下 + 没有 handler ⇒ <b>降级</b>：把并行夹到 {@code Long.MAX ÷ (每份耗电 × 线程)} 并 WARN。 */
        CLAMPED
    }

    /**
     * 一次判定的完整读数（纯数据，不含日志、不含副作用）。
     *
     * @param mode            三档之一
     * @param euPerTick       喂进来的「每份耗电」（{@code BigInteger}，可以有 100 多位）
     * @param parallels       并行份数（判定时的<b>原值</b>，不是夹后的）
     * @param threads         跨配方线程数 {@code T}（{@code <1} 一律按 1 处理）
     * @param total           精确总量（{@code BigInteger}，零损失）
     * @param clampedParallel 降级后的并行；非 {@code CLAMPED} 档为 {@code -1}
     */
    public record Decision(Mode mode, BigInteger euPerTick, long parallels, int threads,
                           BigInteger total, long clampedParallel) {

        /** 降级档的实际并行（夹后仍至少 1：机器不允许"跑 0 份"这种状态）。 */
        public long effectiveParallels() {
            return mode == Mode.CLAMPED ? clampedParallel : parallels;
        }
    }

    // ═══════════════════════════ 1 · 精确总量 ═══════════════════════════

    /** {@code (long) euPerCopy × parallels × threads}，<b>全程 BigInteger，零溢出</b>。 */
    public static BigInteger total(long euPerCopy, long parallels, int threads) {
        return total(BigInteger.valueOf(euPerCopy), parallels, threads);
    }

    /** {@code euPerCopy × parallels × threads}（{@code BigInteger} 版；{@code threads < 1} 按 1 算）。 */
    public static BigInteger total(BigInteger euPerCopy, long parallels, int threads) {
        final BigInteger per = euPerCopy == null ? BigInteger.ZERO : euPerCopy;
        final long p = Math.max(0L, parallels);
        final int t = Math.max(1, threads);
        return per.multiply(BigInteger.valueOf(p)).multiply(BigInteger.valueOf(t));
    }

    /**
     * 装得下吗？—— {@code 0 <= v <= Long.MAX}。
     *
     * <p>🔴 <b>这是全仓唯一一处"电装不装得下"的判据</b>（生产两条路 + 离线自证都调它）。
     * <p>负数一律判"装不下"：耗电出现负值只可能来自回绕/哨兵，把它当 long 收下就是静默错数。
     */
    public static boolean fitsLong(BigInteger v) {
        if (v == null) {
            return true;
        }
        return v.signum() >= 0 && v.compareTo(LONG_MAX) <= 0;
    }

    // ═══════════════════════════ 2 · 判定 ═══════════════════════════

    /** 三因子形式的判定（{@code total = euPerCopy × parallels × threads}），见类注释「口径」。 */
    public static Decision decide(long euPerCopy, long parallels, int threads, boolean handlerAvailable) {
        return decide(BigInteger.valueOf(euPerCopy), parallels, threads, handlerAvailable);
    }

    /**
     * 三因子形式的判定（{@code BigInteger} 每份耗电）。
     *
     * @param handlerAvailable 本机成型后缓存到的无线 handler 是否可用（{@code online}）
     * @return 三档之一；{@code CLAMPED} 档的 {@code clampedParallel} 至少为 1
     */
    public static Decision decide(BigInteger euPerCopy, long parallels, int threads, boolean handlerAvailable) {
        final BigInteger total = total(euPerCopy, parallels, threads);
        if (fitsLong(total)) {
            return new Decision(Mode.LONG, euPerCopy, parallels, Math.max(1, threads), total, -1L);
        }
        if (handlerAvailable) {
            return new Decision(Mode.BIGINT, euPerCopy, parallels, Math.max(1, threads), total, -1L);
        }
        final long clamped = atLeastOne(clampParallel(euPerCopy, threads));
        return new Decision(Mode.CLAMPED, euPerCopy, parallels, Math.max(1, threads), total, clamped);
    }

    /**
     * <b>直接给精确总量的判定</b>（生产侧 T &gt; 1、各 origin 份数不等时用这一支）。
     *
     * <p>{@code parallels} 只用于读数与 {@code CLAMPED} 档的分母，<b>不参与</b>判定。
     */
    public static Decision decideExact(BigInteger exactTotal, BigInteger euPerCopy, long parallels,
                                       int threads, boolean handlerAvailable) {
        final BigInteger total = exactTotal == null ? BigInteger.ZERO : exactTotal;
        if (fitsLong(total)) {
            return new Decision(Mode.LONG, euPerCopy, parallels, Math.max(1, threads), total, -1L);
        }
        if (handlerAvailable) {
            return new Decision(Mode.BIGINT, euPerCopy, parallels, Math.max(1, threads), total, -1L);
        }
        final long clamped = atLeastOne(clampParallel(euPerCopy, threads));
        return new Decision(Mode.CLAMPED, euPerCopy, parallels, Math.max(1, threads), total, clamped);
    }

    // ═══════════════════════════ 3 · 降级：夹并行 ═══════════════════════════

    /**
     * <b>夹并行</b>：{@code p_clamped = Long.MAX ÷ (每份耗电 × 线程)}（整数除法，<b>逐位</b>）。
     *
     * <p>与任务书的公式逐字一致。分母用 {@code BigInteger} 算 ⇒ {@code 每份耗电 × 线程}
     * 自身溢出 {@code long} 时结论仍然正确（此时商为 <b>0</b>，由 {@link #atLeastOne} 在调用处抬到 1）。
     *
     * @return 字面商（可能为 0）；调用方若要"至少跑 1 份"用 {@link #atLeastOne}
     */
    public static long clampParallel(long euPerCopy, int threads) {
        return clampParallel(BigInteger.valueOf(euPerCopy), threads);
    }

    /** {@link #clampParallel(long, int)} 的 {@code BigInteger} 版。 */
    public static long clampParallel(BigInteger euPerCopy, int threads) {
        final BigInteger per = euPerCopy == null ? BigInteger.ZERO : euPerCopy;
        final int t = Math.max(1, threads);
        final BigInteger denominator = per.multiply(BigInteger.valueOf(t));
        if (denominator.signum() <= 0) {
            // 每份耗电 <= 0：不是耗电机器（或数据异常）⇒ 不夹（保持原值语义）。
            return Long.MAX_VALUE;
        }
        final BigInteger quotient = LONG_MAX.divide(denominator);
        if (quotient.signum() <= 0) {
            return 0L;
        }
        return quotient.min(LONG_MAX).longValue();
    }

    /** 把 {@code p} 抬到至少 1（机器不允许 0 份这种状态；夹到 0 时会在证据行里带 {@code why=need-over-long}）。 */
    public static long atLeastOne(long p) {
        return Math.max(1L, p);
    }

    // ═══════════════════════════ 4 · 取整复刻（只在已进 bigint 档时用） ═══════════════════════════

    /**
     * 乘一个正系数并按 {@code HALF_UP} 取整 —— <b>语义上复刻 {@code Math.round(x * f)}</b>（long 路那一句），
     * 但全程不经过 {@code double}，所以 {@code BigInteger} 量级也不丢位。
     *
     * <p>⚠️ 它<b>不参与</b>"装不装得下"的判定（判定永远是 {@link #fitsLong(BigInteger)}）；
     * 它只用来把「N5 耗能减免系数 f」乘到已经超 {@code long} 的量上。
     * ⚠️ 诚实的边界：{@code f} 本身是 {@code double}（上游 {@code reductionFactor} 的返回类型），
     * 所以<b>系数</b>那一位的精度与 long 路完全同一份 —— 本方法只消除<b>量级</b>上的溢出，不假装消除系数精度。
     */
    public static BigInteger applyFactor(BigInteger value, double factor) {
        if (value == null) {
            return BigInteger.ZERO;
        }
        if (!(factor > 0.0D) || factor == 1.0D) {
            return value;
        }
        return new BigDecimal(value).multiply(BigDecimal.valueOf(factor))
                .setScale(0, RoundingMode.HALF_UP).toBigInteger();
    }

    // ═══════════════════════════ 5 · 两条证据行（纯文本，不含日志） ═══════════════════════════

    /**
     * ③ 号证据行（<b>bigint 档</b>，限流 5 秒 1 行）：
     * <pre>
     *   [SHANHAI-INFPAR] energy=bigint euPerTick=&lt;BigInteger&gt; p=&lt;并行&gt; threads=&lt;T&gt; total=&lt;EU/t 总量&gt; handler=online|offline consumed=ok|fail
     * </pre>
     *
     * <h2>🔴 2026-11 修：读不到的量一律打 {@code ?}，<u>绝不许</u>打 {@code -1}</h2>
     * 旧实现把"读不到"的占位哨兵 {@code -1} 直接印成了读数（实机日志里那条
     * {@code energy=bigint … p=-1 total=euPerTick} 的废行就是这么来的）：
     * 玩家看到 {@code p=-1} 只会得出"并行是负的"这种错结论，而 {@code total} 又与
     * {@code euPerTick} 是同一个数 ⇒ <b>两个字段同时撒谎</b>。
     * ⇒ 现在：{@code < 0} 一律打 {@code ?}（{@link ShanhaiParallelDiag#text(long)}），
     * 真的拿不到就是 {@code p=? total=?}。<b>这条是本任务的验收判据之一。</b>
     *
     * @param decision {@link Mode#BIGINT} 档的判定（别的档也允许调，只用于打印）
     * @param consumed 本 tick 的无线扣电是否成功（{@code null} = 还没扣，打印 {@code consumed=pending}）
     */
    public static String bigintLine(Decision decision, boolean handlerOnline, Boolean consumed) {
        final Decision d = decision;
        if (d == null) {
            // 缓存丢了（读档 / 本轮没走路由）⇒ 三个读数全都没有：打 `?`，不打 -1。
            return bigintLine(null, -1L, -1, null, handlerOnline, consumed);
        }
        return bigintLine(d.euPerTick(), d.parallels(), d.threads(), d.total(), handlerOnline, consumed);
    }

    /** {@link #bigintLine(Decision, boolean, Boolean)} 的<b>显式三因子</b>形态（读档后暂存丢了时用）。 */
    public static String bigintLine(BigInteger euPerTick, long parallels, int threads, BigInteger total,
                                    boolean handlerOnline, Boolean consumed) {
        final String consumedText = consumed == null ? "pending" : (consumed ? "ok" : "fail");
        return TAG + " energy=bigint"
                + " euPerTick=" + (euPerTick == null ? "?" : euPerTick.toString())
                + " p=" + ShanhaiParallelDiag.text(parallels)
                + " threads=" + (threads < 0 ? "?" : Integer.toString(threads))
                + " total=" + (total == null ? "?" : total.toString())
                + " handler=" + (handlerOnline ? "online" : "offline")
                + " consumed=" + consumedText;
    }

    /**
     * ② 号证据行（<b>降级档</b>，无 handler 且溢出，限流 5 秒 1 行）：
     * <pre>
     *   [SHANHAI-INFPAR] energy=long-only p_clamped=&lt;夹后&gt; need=&lt;原需求&gt; why=no-wireless handler=none
     * </pre>
     * <p>⚠️ 2026-11：拿不到判定时 {@code p_clamped} 打 {@code ?}（原来打 {@code -1} —— 同上一条的理由）。
     */
    public static String clampLine(Decision decision) {
        final Decision d = decision;
        return TAG + " energy=long-only"
                + " p_clamped=" + (d == null ? "?" : ShanhaiParallelDiag.text(d.clampedParallel()))
                + " need=" + (d == null ? "?" : d.total().toString())
                + " why=no-wireless"
                + " handler=none";
    }

    /**
     * 限流闸门的统一入口：<b>同一形态每 5 秒最多落 1 行</b>。
     *
     * <p>复用现有的 {@link ShanhaiLogThrottle.Gate}（{@code urgent=true} ⇒
     * 同一形态首次必落、之后按 {@link #MIN_INTERVAL_MILLIS} 复述），
     * 不新造第二套限流（本项目已有三套限流，再多一套就是漂移源）。
     */
    public static boolean shouldLog(ShanhaiLogThrottle.Gate gate, String shape, long nowMillis) {
        if (gate == null || shape == null) {
            return false;
        }
        return gate.changed(shape, true, nowMillis, MIN_INTERVAL_MILLIS);
    }

    // ═══════════════════════════ 6 · ∞ 电源的安全夹（不许溢出成负数） ═══════════════════════════

    /**
     * ⛔ <b>∞ 电源分支的安全夹</b>：{@code p_clamped = Long.MAX ÷ (每份耗电 × T)}。
     *
     * <h2>为什么 ∞ 档也需要夹</h2>
     * 「电上限 = ∞」（创造能源仓 / 无线电网输入终端 / 本机豁免）时，并行 <b>base</b> 直接取<b>机器并行表值</b>：
     * 物质创造 {@code 4.6e18}、现实锚点 {@code 6.9e18}、创始现实修改 {@code Long.MAX}。
     * 而走 <b>long 电路</b>时，下一层的总耗电 = {@code base × 每份耗电 × T}，
     * {@code Long.MAX × Long.MAX} 用 {@code long} 数只能<b>回绕成负数</b>
     * （本项目最忌讳的形态：负耗电 = 永动机，或"几乎免费"）。
     * <p>⇒ ∞ 只表示<b>池子没有上限</b>，<b>不</b>表示"每 tick 能搬超过 {@code Long.MAX} 的电"。
     * 所以 ∞ 档仍要按 {@code long} 的物理天花板夹一道；<b>夹后 base 变小，不是把 ∞ 变成有限</b>。
     *
     * <h2>什么时候<b>不</b>夹</h2>
     * 走 BigInteger 路（无线 handler 在线）时不夹 —— 那条路的容量就是 {@code BigInteger}。
     *
     * @return 夹后的 per-thread 并行（≥1）；{@code 不夹}时原样返回 {@code base}
     */
    public static long guardInfiniteCap(long base, BigInteger euPerCopy, int threads) {
        if (base <= 0L || euPerCopy == null || euPerCopy.signum() <= 0) {
            return base;
        }
        final long capacity = clampParallel(euPerCopy, threads);
        if (base <= capacity) {
            return base;
        }
        return atLeastOne(capacity);
    }

    /**
     * ① 号证据行（<b>∞ 电源的安全夹</b>）：
     * <pre>
     *   [SHANHAI-INFPAR] energy=infinite p_clamped=&lt;夹后&gt; base=&lt;原&gt; why=overflow-guard
     * </pre>
     */
    public static String infiniteGuardLine(long pClamped, long base) {
        return TAG + " energy=infinite"
                + " p_clamped=" + pClamped
                + " base=" + base
                + " why=overflow-guard";
    }

    // ═══════════════════════════ 7 · 无线电网终端按【池子余额】算并行 ═══════════════════════════

    /**
     * 把「每并行耗电」从<b>毫 EU/t</b>（{@code ParallelOverrideMachine.getPerParallelMilliCost()} 的口径）
     * 换成每份耗电：<b>向上取整</b>到整数 EU/t。
     *
     * <p>向上取整是<b>刻意保守</b>：{@code ceil} 让 {@code p_pool} 只会更小，绝不会"多派份数"
     * ⇒ 不可能因为取整把池子抽成负数。
     *
     * @param milliEut {@code ≤ 0}（没算出 / 没候选 / 开关关着）⇒ 返回 {@code null}（= 本道夹子不参与）
     */
    public static BigInteger perCopyFromMilli(long milliEut) {
        if (milliEut <= 0L) {
            return null;
        }
        return BigInteger.valueOf(milliEut).add(BigInteger.valueOf(999L))
                .divide(BigInteger.valueOf(1000L));
    }

    /**
     * 🔴 <b>无线电网终端不是无限，只是"池子里有多少就能拉多少"</b>：
     * {@code p_pool = floor(余额 ÷ (每份耗电 × T))}（<b>全程 BigInteger 除法</b>，不经过 double）。
     *
     * <p>创造能源仓<b>不</b>参与这一条（它走 {@link #guardInfiniteCap} 的 ∞ 安全夹）——
     * 判据是调用方只把"真的来自无线电网池子"的余额喂进来，创造仓的余额根本不进这条。
     *
     * @param available 池子余额（{@code BigInteger}，可为 {@code null} = 不是无线路径 ⇒ 不夹）
     * @param euPerCopy 每份耗电
     * @param threads   跨配方线程数
     * @return 池子能养的份数（可能为 0 = 连 1 份都养不起）；{@code available == null} ⇒ 返回 {@code null}（不夹）
     */
    public static BigInteger poolParallel(BigInteger available, BigInteger euPerCopy, int threads) {
        if (available == null) {
            return null;
        }
        final BigInteger per = euPerCopy == null ? BigInteger.ZERO : euPerCopy;
        final BigInteger denominator = per.multiply(BigInteger.valueOf(Math.max(1, threads)));
        if (denominator.signum() <= 0) {
            return null;
        }
        if (available.signum() <= 0) {
            return BigInteger.ZERO;
        }
        return available.divide(denominator);
    }

    /**
     * ② 号证据行（<b>按池子余额算并行</b>）：
     * <pre>
     *   [SHANHAI-INFPAR] energy=pool p_pool=&lt;BigInteger 商&gt; avail=&lt;余额&gt; k=&lt;每份耗电×T&gt;
     * </pre>
     */
    public static String poolLine(BigInteger pPool, BigInteger available, BigInteger euPerCopy, int threads) {
        final BigInteger per = euPerCopy == null ? BigInteger.ZERO : euPerCopy;
        final BigInteger k = per.multiply(BigInteger.valueOf(Math.max(1, threads)));
        return TAG + " energy=pool"
                + " p_pool=" + (pPool == null ? "?" : pPool.toString())
                + " avail=" + (available == null ? "?" : available.toString())
                + " k=" + k;
    }

    // ═══════════════════════════ 8 · 纯核自检用语 ═══════════════════════════

    /** 纯核自检用的最短说明（离线工具与报告读同一段措辞，防止两边口径分叉）。 */
    public static String describe(Mode mode) {
        return switch (mode) {
            case LONG -> "装得下 ⇒ 老路径原样（能源仓扣电，handleTickRecipe 一次）";
            case BIGINT -> "装不下 + handler 在线 ⇒ WirelessGTRecipe + consumeEnergy(BigInteger)（能源仓 0 次）";
            case CLAMPED -> "装不下 + 无 handler ⇒ 夹并行 + WARN（行为不变，只是不许派超出 long 的份数）";
        };
    }
}
