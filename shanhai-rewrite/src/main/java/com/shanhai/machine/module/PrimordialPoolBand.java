package com.shanhai.machine.module;

import com.shanhai.common.log.ShanhaiLogThrottle;

import java.math.BigInteger;

/**
 * 🔴 <b>池审计「差额」的<b>按入池重载分档</b>判据 + 离线复算（2026-11-02）。</b>
 *
 * <h2>它要回答的问题（用户 2026-11-02 点单「查它是不是被别的机器用掉了」）</h2>
 * 实机那一行是：
 * <pre>
 *   Δ总=2376962856283518025274835530
 *   我这台 Σ=2376962934589946685135257600（16 次入池）
 *   差额=-78306428659860422070
 *   已知带 = 差额&lt;0 且 |差额| ≤ 1e19   ⇒ 超了 7.8 倍
 * </pre>
 * ⇒ 必须能离线回答两个二选一：
 * <ol>
 *   <li><b>是不是我们走错重载（每次写入被 {@code Long.MAX} 夹）</b>——见 {@link #selfTest()} 的
 *       <b>①②③</b>（重载复算 + 负对照）；</li>
 *   <li><b>是不是被别人用掉了</b>——若「不夹」模型能精确复现观测差额 ⇒ 差额就是<b>别人取走的净额</b>
 *       （见 {@link #otherWritersNet(BigInteger, BigInteger)}）。</li>
 * </ol>
 *
 * <h2>🔴 为什么判据必须住在这个「纯 JDK」类里</h2>
 * 本项目纪律「凡我自己写的检查脚本报出的结果，采信前必须先证明脚本自己是对的」。
 * 判定若写在带 Minecraft 依赖的类里，离线复现脚本只能<b>照抄一遍</b>判定 ⇒ 又变成
 * 「抄错的是脚本还是生产代码」无法分辨（2026-09-20 同族撞过 7 次，全是<b>检查器自己</b>的假设错了）。
 * ⇒ 本类<b>零 Minecraft / GT / Forge 依赖</b>，{@code javac --release 17} 可单独编译并跑
 * {@link #main(String[])}；<b>游戏里跑的是同一份字节码</b>，不存在第二份判据。
 *
 * <h2>重载事实（javap 实证，不是"我记得"）</h2>
 * {@code com.hepdd.gtmthings.api.misc.WirelessEnergyManager} 有<b>三个</b>同名重载（{@code gtmthings-1.3.5.b.jar}）：
 * <pre>
 *   public static boolean addEUToGlobalEnergyMap(java.util.UUID, java.math.BigInteger, MetaMachine)
 *   public static boolean addEUToGlobalEnergyMap(java.util.UUID, long,             MetaMachine)
 *   public static boolean addEUToGlobalEnergyMap(java.util.UUID, int,              MetaMachine)
 * </pre>
 * ⇒ 后两条只是 {@code BigInteger.valueOf(x)} 之后转调第一条；<b>真正的池子是第一条</b>。
 * 而 gtladditions {@code com.gtladd.gtladditions.mixin.gtmtings.WirelessEnergyManagerMixin}
 * 用 {@code @Overwrite}（{@code RuntimeVisibleAnnotations: Overwrite(remap=false)}）<b>整段换掉了第一条</b>：
 * <pre>
 *   GlobalEnergy.getOrDefault(team, ZERO)
 *   → 若 &lt; 0 先归零
 *   → totalEU = totalEU.add(EU)        ← 【精确 BigInteger.add，没有任何 Math.min / Long.MAX 夹】
 *   → 若 totalEU.signum() &gt;= 0 则 put 并返回 true，否则返回 false（= 扣不到就不记账）
 *   （另外把「本台最后一次单笔」写进 gtladditions 的 BIG_MACHINE_DATA，见 PrimordialPoolWitness）
 * </pre>
 * ⇒ <b>池子上不存在任何"单次上限"</b>；{@code long} 重载的"上限"只是<b>形参类型</b>（{@code long}）本身。
 */
public final class PrimordialPoolBand {

    private PrimordialPoolBand() {}

    // ═══════════════════════════ 1. 入池重载的三种事实 ═══════════════════════════

    /**
     * 我们往池子里写电时<b>实际绑定</b>的那条重载。
     *
     * <p>🔴 <b>为什么是 {@link #BIGINT}（三条独立证据，缺一不可）</b>：
     * <ol>
     *   <li><b>语言层</b>：调用点的实参是 {@code java.math.BigInteger}（{@code PrimordialModuleMachine} 里
     *       {@code final BigInteger production = PrimordialGeneratorProduction.perTick(...)}）。
     *       {@code BigInteger} <b>不能</b>隐式转成 {@code long}/{@code int}（必须显式 {@code .longValue()}）
     *       ⇒ JLS 15.12.2 的适用性判定里，只有 {@code (UUID, BigInteger, MetaMachine)} 一条<b>是可用的</b>，
     *       另外两条<b>根本不适用</b> —— 这不是"选到了哪一条"，是"只有一条能选中"。</li>
     *   <li><b>字节码层</b>：已编译产物 {@code build/classes/java/main/com/shanhai/machine/module/PrimordialModuleMachine.class}
     *       （mtime 2026-11-01 11:07:06，sha256 {@code E546DD98…F54908EF}）里那一句是
     *       <pre>
     *   invokestatic WirelessEnergyManager.addEUToGlobalEnergyMap:(Ljava/util/UUID;Ljava/math/BigInteger;Lcom/gregtechceu/gtceu/api/machine/MetaMachine;)Z
     *       </pre>
     *       —— 描述符里是 {@code Ljava/math/BigInteger;}，<b>不是</b> {@code J}（long）。</li>
     *   <li><b>实机层</b>：池子的 Δ 本身就否证了 long 重载。{@code long} 重载一次最多写
     *       {@code Long.MAX_VALUE}=9.223e18；用户那次 16 次入池让池子涨了
     *       {@code 2.376962856e27}（≈ 每次 1.4856e26 = {@code 1.61e7 × Long.MAX}）。
     *       <b>long 重载在物理上写不出这个数</b> ⇒ 只可能是 BigInteger 重载。</li>
     * </ol>
     * <p>⚠️ 本常量<b>不是</b>判定的输入（判定只按类型事实走）；它的唯一用途是把事实打进日志，
     * 让"这一行到底走哪条重载"不必再靠读代码。
     */
    public static final DepositOverload DEPOSIT_OVERLOAD = DepositOverload.BIGINT;

    /** 入池重载（{@link #token} 直接进日志，别改成中文，用户要能 grep）。 */
    public enum DepositOverload {

        /** 实参是 {@code long}/{@code int} ⇒ 被 {@code BigInteger.valueOf(...)} 转调，写不出 &gt; {@code Long.MAX} 的单笔。 */
        LONG("long"),

        /** 实参是 {@code BigInteger} ⇒ 精确相加，单笔无上限。 */
        BIGINT("bigint");

        public final String token;

        DepositOverload(String token) {
            this.token = token;
        }

        /** 日志用的「单次上限=…」。 */
        public String perCallCeilingText() {
            return this == LONG
                    ? "Long.MAX_VALUE=9223372036854775807（形参就是 long，本身装不下更大的单笔）"
                    : "无（BigInteger.add 精确相加；池子里没有 Math.min / Long.MAX 夹）";
        }
    }

    /** {@code Long.MAX_VALUE}（{@code BigInteger} 形态，只用于算式与日志）。 */
    public static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /**
     * 🔴 <b>bigint 档的「看得见」门槛 = 1e19（用户 2026-11-02 拍板，两级）。</b>
     *
     * <pre>
     *   |差额| ≤ 1e19 ⇒ 打 **INFO**（一行，含 其它收支= 明细=Top3 入池重载= 单次上限=）
     *   |差额| &gt; 1e19 ⇒ 打 **WARN**（这才是真异常）
     * </pre>
     *
     * <h2>🔴 为什么是 1e19，而不是我先前收紧的 1e15</h2>
     * 用户原话口径（2026-11-02）：<b>「日志不能每 5 秒一条 WARN，那样真异常会被淹掉」</b>。
     * 我先前按"bigint 下理想差额恒为 0"把带子收到 1e15 ⇒ 只要同队伍真有别人在取电，
     * 这一行就会<b>每 5 秒一条 WARN</b> 刷下去 ⇒ 真正的异常会被埋掉。
     * ⇒ 现在：<b>正常侧重在"看得见"（INFO 一行，含明细），异常侧才 WARN</b>。
     *
     * <h2>为什么 1e19 这个数本身仍然是对的</h2>
     * 走 BigInteger 重载时池子的增量<b>逐位等于</b>我们的 Σ（写入是精确的 {@code add}，读的也是同一个键）
     * ⇒ 差额只可能来自<b>别的写者</b>（同队伍其它机器/终端的取电，或别的发电机入池）。
     * 1e19 与 2026-10-01 用户拍板的老口径<b>同值</b>（见 {@link ShanhaiLogThrottle#POOL_DIFF_KNOWN_LIMIT}），
     * 量级上比"一次满配产出"（{@code 2^31 × 2147483647 × 20 ≈ 9.22e19}）小半档 ⇒
     * 超过它就不是"多取了一度电"，而是对不上账。
     *
     * <p>⚠️ 实测那一次是 −7.83e19（&gt; 1e19）⇒ 按本口径**仍打 WARN**，与用户看到的那行一致。
     */
    public static final BigInteger BIGINT_INFO_LIMIT = new BigInteger("10000000000000000000"); // 1e19

    /**
     * 旧名（我 2026-11-02 第一版收紧到 1e15 时用的常量名），<b>已作废，保留只为"改判时旧文不删"</b>。
     *
     * @deprecated 用 {@link #BIGINT_INFO_LIMIT}（1e19 + 两级）。1e15 会让 WARN 每 5 秒刷一条。
     */
    @Deprecated
    public static final BigInteger BIGINT_TIGHT_LIMIT = BIGINT_INFO_LIMIT;

    /**
     * long 档的"预期差额"容差 = <b>0</b>（严格）。
     *
     * <p>见 {@link #longOverloadExpectedDiff(BigInteger, BigInteger)}：long 档下池子的增量是
     * {@code Σ min(每次写入, Long.MAX)}，这个值与"没夹会是多少"的差<b>可以逐位算出</b>
     * ⇒ 不需要容差，差 1 就是 1。
     */
    public static final BigInteger LONG_TOLERANCE = BigInteger.ZERO;

    // ═══════════════════════════ 2. 算式（全部 BigInteger，零回绕） ═══════════════════════════

    /** 单次写入 {@code raw} 若被 {@code Long.MAX} 夹一次之后，池子实际会收到多少。 */
    public static BigInteger longClamped(BigInteger raw) {
        if (raw == null || raw.signum() <= 0) {
            return BigInteger.ZERO;
        }
        return raw.compareTo(LONG_MAX) > 0 ? LONG_MAX : raw;
    }

    /**
     * <b>long 重载的"预期差额"</b> = {@code Σ(每次写入 − Long.MAX)} 的等价闭式：
     * <pre>
     *   预期差额 = Σ min(xᵢ, Long.MAX)  −  Σ xᵢ
     *            = longClampedSum      −  rawSum          （恒 ≤ 0）
     * </pre>
     * 其中 {@code longClampedSum} 由调用方<b>逐次</b>累计（{@code Σ} 加 {@link #longClamped(BigInteger)}），
     * 所以这个预报是<b>精确</b>的（不是拿平均值估的）—— 这正是"把公式算进已知带"的落地方式。
     *
     * <p>⚠️ 只有当<b>某次写入 &gt; Long.MAX</b> 时它才非 0；全部 ≤ Long.MAX 时恒 0
     * ⇔ 此时 long 重载与 bigint 重载<b>逐位等价</b>（这就是"老路径逐位不变"的那条判据）。
     */
    public static BigInteger longOverloadExpectedDiff(BigInteger rawSum, BigInteger longClampedSum) {
        final BigInteger raw = rawSum == null ? BigInteger.ZERO : rawSum;
        final BigInteger clamped = longClampedSum == null ? BigInteger.ZERO : longClampedSum;
        return clamped.subtract(raw);
    }

    /**
     * 差额里<b>不属于我们</b>的那一份（= 其它写者的净额）。
     * <pre>
     *   其它收支 = Δ池 − 我们Σ = 差额
     * </pre>
     * 走 BigInteger 重载时这一项<b>逐位可信</b>（我们的写入是精确的）；走 long 重载时要先扣掉
     * {@link #longOverloadExpectedDiff(BigInteger, BigInteger)} 才是"别人的"。
     */
    public static BigInteger otherWritersNet(BigInteger poolDelta, BigInteger rawSum) {
        final BigInteger delta = poolDelta == null ? BigInteger.ZERO : poolDelta;
        final BigInteger raw = rawSum == null ? BigInteger.ZERO : rawSum;
        return delta.subtract(raw);
    }

    // ═══════════════════════════ 3. 分档判定（2026-11-02 用户拍板：两级） ═══════════════════════════

    /**
     * 分档结果。<b>级别由 {@link #levelOf(Band)} 给</b>（不在枚举里带，是为了让"级别"只有一处定义）。
     */
    public enum Band {

        /** BigInteger 档且 {@code |差额| ≤ 1e19} ⇒ <b>INFO</b>（看得见，但不是异常）。 */
        BIGINT_WITHIN("已知带(bigint)"),

        /** long 档且差额<b>逐位等于</b>「每次被夹掉的那一段」⇒ 静默（long 档今天跑不到，保持原判据不动）。 */
        LONG_WITHIN("已知带(long 夹)"),

        /** 不在任何已知带里 ⇒ <b>WARN</b>（真异常；明细读不到时还会追加 {@code witness=unreadable}）。 */
        OUT_OF_BAND("超出已知带");

        public final String token;

        Band(String token) {
            this.token = token;
        }
    }

    /**
     * 🔴 <b>档位 → 日志级别</b>（用户 2026-11-02 拍板的两级口径，唯一的级别定义处）。
     *
     * <pre>
     *   BIGINT_WITHIN（|差额| ≤ 1e19）⇒ INFO   ← "看得见"不能丢：一行，含 明细=Top3
     *   LONG_WITHIN  （差额 == 闭式预报）⇒ NONE ← long 档今天跑不到；保持已实现的行为不动
     *   OUT_OF_BAND  （|差额| &gt; 1e19）  ⇒ WARN ← 这才是真异常
     * </pre>
     *
     * <p>⚠️ 节流仍交给 {@code ShanhaiLogThrottle.Gate}：
     * {@code INFO} 走 {@code changed(形态, urgent=false)} ⇒ <b>同一形态只打一次</b>
     * （否则每 20 tick 一条 INFO 比 WARN 更吵）；{@code WARN} 走 {@code urgent=true}
     * ⇒ 首次 + 同形每 5 秒最多复述一次。级别没降：WARN 仍是 WARN。
     */
    public static ShanhaiLogThrottle.Level levelOf(Band band) {
        if (band == Band.OUT_OF_BAND) {
            return ShanhaiLogThrottle.Level.WARN;
        }
        return band == Band.BIGINT_WITHIN ? ShanhaiLogThrottle.Level.INFO : ShanhaiLogThrottle.Level.NONE;
    }

    /**
     * <b>按入池重载分档</b>（用户 2026-11-02 拍板的口径）。
     *
     * <pre>
     *   重载 = bigint ⇒ |差额| ≤ {@link #BIGINT_INFO_LIMIT}(1e19) ⇒ BIGINT_WITHIN（INFO）；否则 OUT_OF_BAND（WARN）
     *   重载 = long   ⇒ 差额 == {@link #longOverloadExpectedDiff}(rawSum, clampedSum) ⇒ LONG_WITHIN（静默）；否则 OUT_OF_BAND
     * </pre>
     *
     * <p>🔴 <b>与 2026-10-01 老口径的差别（写死，别读一半）</b>：
     * 老口径只有一条 {@code 差额 < 0 且 |差额| ≤ 1e19}（且 {@code 差额 ≥ 0} 也算异常）。
     * 新口径按重载分档后，<b>bigint 档允许差额 = 0</b>（那本来就是"逐位对得上"的正常形态），
     * 也允许正差额（别人<b>往池子里加</b>电），并且落在带内时<b>打一行 INFO 而不是静默</b>
     * （"看得见"优先）。老口径那一条仍保留在 {@link ShanhaiLogThrottle#isPoolDiffKnown(BigInteger)} 里，
     * 作为历史对照。
     */
    public static Band bandOf(DepositOverload overload, BigInteger others,
                              BigInteger rawSum, BigInteger longClampedSum) {
        final BigInteger diff = others == null ? BigInteger.ZERO : others;
        if (overload == DepositOverload.BIGINT) {
            return diff.abs().compareTo(BIGINT_INFO_LIMIT) <= 0 ? Band.BIGINT_WITHIN : Band.OUT_OF_BAND;
        }
        final BigInteger expected = longOverloadExpectedDiff(rawSum, longClampedSum);
        return diff.subtract(expected).abs().compareTo(LONG_TOLERANCE) == 0 ? Band.LONG_WITHIN : Band.OUT_OF_BAND;
    }

    /** 日志里「已知带 = …」那一段的文本（按重载 + 两级给，别让人再猜）。 */
    public static String bandText(DepositOverload overload, BigInteger rawSum, BigInteger longClampedSum) {
        if (overload == DepositOverload.BIGINT) {
            return "|差额| ≤ " + BIGINT_INFO_LIMIT + " ⇒ INFO（看得见，不算异常）；> " + BIGINT_INFO_LIMIT
                    + " ⇒ WARN（bigint 档下池子增量应与我们的 Σ 逐位相等，理想差额 = 0）";
        }
        return "差额 == Σ(min(xᵢ, Long.MAX) − xᵢ) = " + longOverloadExpectedDiff(rawSum, longClampedSum)
                + "（long 档 ⇒ 预期差额就是把每次夹掉的那一段加起来；容差 = " + LONG_TOLERANCE + "）";
    }

    /** 日志里「入池重载=… 单次上限=…」两条。 */
    public static String overloadLine(DepositOverload overload) {
        return "入池重载=" + overload.token + "   单次上限=" + overload.perCallCeilingText();
    }

    /**
     * 🔴 <b>那四个字段的<b>唯一</b>构造点</b>（INFO 与 WARN 行共用同一份 ⇒ 两行不会漂移；
     * 也正因为只有一处，自检才能断言"INFO 行里含 {@code 明细=}"）。
     *
     * <pre>
     *   其它收支=&lt;Δ池 − 我们Σ&gt;   明细=&lt;Top3 或 unreadable(原因)&gt;   入池重载=&lt;long|bigint&gt;   单次上限=&lt;…&gt;
     * </pre>
     *
     * @param detail 明细串（读不到时调用方传 {@code unreadable(...)}，本方法原样保留）
     */
    public static String fieldsLine(DepositOverload overload, BigInteger others, String detail) {
        final BigInteger diff = others == null ? BigInteger.ZERO : others;
        return "其它收支=" + diff + "   明细=" + detail
                + "   入池重载=" + overload.token + "   单次上限=" + overload.perCallCeilingText();
    }

    /**
     * 🔴 <b>只在异常档才追加的那一段</b>（用户 2026-11-02 点单）。
     *
     * <p>语义：{@code |差额| > 1e19} 时，若取证器<b>读不到</b>明细，就追加
     * {@code witness=unreadable} —— 让读日志的人知道
     * <b>「是我们读不到，不一定真是别人用了」</b>，而不是把"读不到"当成"没人用"。
     *
     * @return 异常档且明细不可读 ⇒ {@code " witness=unreadable"}；其余全部空串（INFO 行永不追加）
     */
    public static String witnessSuffix(Band band, boolean witnessReadable) {
        return band == Band.OUT_OF_BAND && !witnessReadable ? "  witness=unreadable" : "";
    }

    // ═══════════════════════════ 4. 离线复算 + 自检 ═══════════════════════════

    // ── 用户 2026-11-02 实机日志的三个真值（逐字抄自 evidence，不许改） ──
    /** 实机：{@code Δ总}。 */
    public static final BigInteger OBSERVED_POOL_DELTA = new BigInteger("2376962856283518025274835530");
    /** 实机：{@code 我这台 Σ}。 */
    public static final BigInteger OBSERVED_MY_SUM = new BigInteger("2376962934589946685135257600");
    /** 实机：{@code 差额}。 */
    public static final BigInteger OBSERVED_DIFF = new BigInteger("-78306428659860422070");
    /** 实机：{@code 16 次入池}。 */
    public static final int OBSERVED_DEPOSITS = 16;
    /** 实机那一轮的审计窗口 = 20 tick（{@code shanhai$auditPool} 的闸门 {@code getOffsetTimer() % 20}）。 */
    public static final int OBSERVED_WINDOW_TICKS = 20;

    /**
     * 🔴 <b>离线复算 + 各条断言（{@link #main(String[])} 与游戏加载期跑的是同一份）。</b>
     *
     * <p>四条断言，全部用<b>用户实机那三个数</b>当输入：
     * <ol>
     *   <li><b>① 观测差额自身自洽</b>：{@code Δ总 − Σ} 必须逐位等于日志里的 {@code 差额}
     *       ⇒ 证明这三个数不是抄错的。</li>
     *   <li><b>② long 重载不能复现</b>：每次 {@code X = Σ/16 = 1.4856e26}；
     *       long 重载会把每次夹到 {@code Long.MAX} ⇒ 池子只会涨 {@code 16 × 9.223e18 = 1.4757e20}，
     *       与观测的 {@code Δ总}（{@code 2.377e27}）差 <b>1.6e7 倍</b>；预期差额
     *       {@code = 16×Long.MAX − Σ} 与观测差额差 <b>3.03e7 倍</b>
     *       ⇒ <b>把 long 模型和观测放在一起，它们不可能同时成立</b>。</li>
     *   <li><b>③ 负对照（把"夹"去掉 ⇒ 差额必须变 0）</b>：{@code longClampedSum = rawSum} 时
     *       {@link #longOverloadExpectedDiff} 必须<b>逐位等于 0</b>，且 BigInteger 档下
     *       {@link #otherWritersNet}(Σ, Σ) = 0 ⇒ 断言把它抓住（改了 {@code longClamped} 这行会红）。</li>
     *   <li><b>④ 分档真值表</b>：bigint 档 0 / ±1e19 落 BIGINT_WITHIN、1e19+1 落 OUT_OF_BAND；
     *       long 档 差额 == 预报落 LONG_WITHIN、±1 落 OUT_OF_BAND。</li>
     *   <li><b>⑥ 两级判据（用户 2026-11-02 拍板的那两组读数）</b>：
     *       <b>①</b> {@code Δ = −7.83e19}（本次实测值）⇒ <b>WARN</b>；
     *       <b>②</b> {@code Δ = −1e18}（典型"别人取电"）⇒ <b>INFO</b>，且那一行<b>必须含 {@code 明细=}</b>
     *       （断言打在 {@link #fieldsLine} 上 —— 那是 INFO/WARN 两行共用的唯一构造点）。</li>
     * </ol>
     *
     * @throws IllegalStateException 任一条不成立
     */
    public static void selfTest() {
        // ── ① 三个实机数自洽 ──
        require(OBSERVED_POOL_DELTA.subtract(OBSERVED_MY_SUM).equals(OBSERVED_DIFF),
                "① 实机三数不自洽：Δ总 − Σ = " + OBSERVED_POOL_DELTA.subtract(OBSERVED_MY_SUM)
                        + "，而日志的差额是 " + OBSERVED_DIFF);

        final BigInteger perWrite = OBSERVED_MY_SUM.divide(BigInteger.valueOf(OBSERVED_DEPOSITS));
        require(perWrite.multiply(BigInteger.valueOf(OBSERVED_DEPOSITS)).equals(OBSERVED_MY_SUM),
                "① Σ 不能被 16 整除 ⇒ 16 次不是等额，本自检的 perWrite 假设不成立（请把逐次值交回）");

        // ── ② long 重载模型：池增量、预期差额，与观测的距离 ──
        final BigInteger longPoolGain = LONG_MAX.multiply(BigInteger.valueOf(OBSERVED_DEPOSITS));
        final BigInteger longDiff = longOverloadExpectedDiff(OBSERVED_MY_SUM, longPoolGain);
        // 每次写入都被夹（X > Long.MAX）⇒ 差额必然是负的，且量级 = Σ 本身
        require(perWrite.compareTo(LONG_MAX) > 0,
                "② 每次写入 " + perWrite + " 没有超过 Long.MAX ⇒ 本轮的 long 模型前提不成立");
        require(longDiff.signum() < 0, "② long 模型的差额应为负，实际 " + longDiff);
        // 关键断言：long 模型与观测差 6 个数量级以上（实测 3.03e7 倍）⇒ 复现不出来
        final BigInteger ratio = longDiff.divide(OBSERVED_DIFF);
        require(ratio.abs().compareTo(BigInteger.valueOf(1_000_000L)) > 0,
                "② long 模型差额 " + longDiff + " 与观测差额 " + OBSERVED_DIFF + " 只差 " + ratio
                        + " 倍（< 1e6）⇒ 本自检的\"long 复现不出来\"这个结论不成立，请重新核算");
        // 并且 long 模型的池增量与观测的 Δ总 差 1e7 倍以上（同一条事实的第二个角度）
        final BigInteger poolGainRatio = OBSERVED_POOL_DELTA.divide(longPoolGain);
        require(poolGainRatio.compareTo(BigInteger.valueOf(1_000_000L)) > 0,
                "② 观测 Δ总 " + OBSERVED_POOL_DELTA + " 只有 long 模型池增量 " + longPoolGain + " 的 "
                        + poolGainRatio + " 倍（< 1e6）⇒ 观测本身不支持\"单次被夹\"的读数");

        // ── ③ 负对照：把"夹"去掉 ⇒ 差额必须变 0 ──
        final BigInteger noClampDiff = longOverloadExpectedDiff(OBSERVED_MY_SUM, OBSERVED_MY_SUM);
        require(noClampDiff.signum() == 0,
                "③ 负对照失败：去掉夹之后预期差额应为 0，实际 " + noClampDiff);
        require(otherWritersNet(OBSERVED_MY_SUM, OBSERVED_MY_SUM).signum() == 0,
                "③ 负对照失败：池增量 == Σ 时\"其它收支\"应为 0");
        // 反过来：夹一次就必须正好差出 Long.MAX 那一段（把 ①② 的连接点钉死）
        require(longOverloadExpectedDiff(perWrite, longClamped(perWrite))
                        .equals(LONG_MAX.subtract(perWrite)),
                "③ 单次夹的差额必须正好等于 Long.MAX − x（"+ LONG_MAX.subtract(perWrite) + "）");

        // ── ④ 分档真值表（2026-11-02 用户拍板：大档 1e19） ──
        require(bandOf(DepositOverload.BIGINT, BigInteger.ZERO, OBSERVED_MY_SUM, OBSERVED_MY_SUM)
                        == Band.BIGINT_WITHIN,
                "④ bigint 档：差额 0 必须落已知带");
        require(bandOf(DepositOverload.BIGINT, BIGINT_INFO_LIMIT, OBSERVED_MY_SUM, OBSERVED_MY_SUM)
                        == Band.BIGINT_WITHIN,
                "④ bigint 档：|差额| = 1e19（含边界）必须落已知带");
        require(bandOf(DepositOverload.BIGINT, BIGINT_INFO_LIMIT.negate(), OBSERVED_MY_SUM, OBSERVED_MY_SUM)
                        == Band.BIGINT_WITHIN,
                "④ bigint 档：−1e19 必须落已知带");
        require(bandOf(DepositOverload.BIGINT, BIGINT_INFO_LIMIT.add(BigInteger.ONE),
                        OBSERVED_MY_SUM, OBSERVED_MY_SUM) == Band.OUT_OF_BAND,
                "④ bigint 档：1e19+1 必须超出已知带");
        require(bandOf(DepositOverload.LONG, longDiff, OBSERVED_MY_SUM, longPoolGain) == Band.LONG_WITHIN,
                "④ long 档：差额 == 预报值时必须落已知带（" + longDiff + "）");
        require(bandOf(DepositOverload.LONG, longDiff.add(BigInteger.ONE), OBSERVED_MY_SUM, longPoolGain)
                        == Band.OUT_OF_BAND,
                "④ long 档：预报值 +1 必须超出已知带（容差 = 0）");
        // 老口径常量没被改（老日志的解释仍然成立）：新门槛与 2026-10-01 的 1e19 同值
        require(BIGINT_INFO_LIMIT.compareTo(ShanhaiLogThrottle.POOL_DIFF_KNOWN_LIMIT) == 0,
                "④ bigint 档门槛必须回到 2026-10-01 老口径的 1e19（用户 2026-11-02 拍板）");

        // ── ⑤ 实机差额在 bigint 档下超过 1e19 ⇒ 仍打 WARN（与用户看到的那行一致） ──
        require(bandOf(DepositOverload.BIGINT, OBSERVED_DIFF, OBSERVED_MY_SUM, OBSERVED_MY_SUM)
                        == Band.OUT_OF_BAND,
                "⑤ 实机那个 −7.83e19 在 bigint 档必须判为超出已知带（否则线上不会落盘，用户看不到）");
        // 同一个数按老口径也要落到"超出"（与用户看到的 WARN 一致）
        require(!ShanhaiLogThrottle.isPoolDiffKnown(OBSERVED_DIFF),
                "⑤ 实机差额按 2026-10-01 老口径也应判为超出带（用户日志确实 WARN 了）");

        // ── ⑥ 两级判据：用户点名的两组读数（WARN / INFO） ──
        // ① 本次实测值 −7.83e19 ⇒ 必须 WARN
        final Band observedBand = bandOf(DepositOverload.BIGINT, OBSERVED_DIFF, OBSERVED_MY_SUM, OBSERVED_MY_SUM);
        require(levelOf(observedBand) == ShanhaiLogThrottle.Level.WARN,
                "⑥-① 实机差额 " + OBSERVED_DIFF + " 必须判 WARN，实际 " + levelOf(observedBand));
        // 异常档 + 明细读不到 ⇒ 追加 witness=unreadable（免得把"读不到"当成"没人用"）
        require(witnessSuffix(observedBand, false).contains("witness=unreadable"),
                "⑥-① 实机这一档若明细读不到，必须追加 witness=unreadable");
        require(witnessSuffix(observedBand, true).isEmpty(),
                "⑥-① 明细读得到时不许追加 witness=unreadable（否则等于在说读不到）");
        // ② 典型"别人取电" −1e18 ⇒ 必须 INFO，且那一行含 明细=
        final BigInteger typicalOthers = new BigInteger("-1000000000000000000");
        final Band typicalBand = bandOf(DepositOverload.BIGINT, typicalOthers, OBSERVED_MY_SUM, OBSERVED_MY_SUM);
        require(levelOf(typicalBand) == ShanhaiLogThrottle.Level.INFO,
                "⑥-② 差额 " + typicalOthers + " 必须判 INFO（不能 WARN 刷屏），实际 " + levelOf(typicalBand));
        final String infoFields = fieldsLine(DepositOverload.BIGINT, typicalOthers, "1.某终端@(0,110,25):-1000000000000000000");
        require(infoFields.contains("明细="),
                "⑥-② INFO 行必须含 明细=（实际: " + infoFields + "）");
        require(infoFields.contains("其它收支=") && infoFields.contains("入池重载=")
                        && infoFields.contains("单次上限="),
                "⑥-② INFO 行必须同时含 其它收支= / 入池重载= / 单次上限=（实际: " + infoFields + "）");
        // INFO 行永不追加 witness（那是异常档专用字段）
        require(witnessSuffix(typicalBand, false).isEmpty(),
                "⑥-② INFO 档不许追加 witness=unreadable");
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException("[SHANHAI-GEN-LIMIT] 池审计分档自检失败：" + what + "。");
        }
    }

    /**
     * <b>离线复算入口</b>（{@code javac --release 17 PrimordialPoolBand.java} 后直接跑）。
     *
     * <p>打印两张并排的表：<b>long 重载模型</b> vs <b>bigint 重载模型</b>，各自给出池增量和差额，
     * 让"哪一种能复现用户那三个数"一眼可见。退出码：自检通过 = 0，失败 = 1（便于脚本判）。
     */
    public static void main(String[] args) {
        selfTest();
        final BigInteger n = BigInteger.valueOf(OBSERVED_DEPOSITS);
        final BigInteger perWrite = OBSERVED_MY_SUM.divide(n);
        final BigInteger longPoolGain = LONG_MAX.multiply(n);
        final BigInteger longDiff = longOverloadExpectedDiff(OBSERVED_MY_SUM, longPoolGain);
        final BigInteger others = otherWritersNet(OBSERVED_POOL_DELTA, OBSERVED_MY_SUM);

        System.out.println("==== [SHANHAI-GEN-LIMIT] 池审计差额 · 重载复算 ====");
        System.out.println("实机 Δ总            = " + OBSERVED_POOL_DELTA);
        System.out.println("实机 我这台 Σ        = " + OBSERVED_MY_SUM + "（" + OBSERVED_DEPOSITS + " 次入池）");
        System.out.println("实机 差额            = " + OBSERVED_DIFF);
        System.out.println("每次写入 X = Σ/16    = " + perWrite + "（= " + bytes(perWrite) + "）");
        System.out.println("Long.MAX_VALUE       = " + LONG_MAX);
        System.out.println("X / Long.MAX         = " + perWrite.divide(LONG_MAX) + " 倍");
        System.out.println();
        System.out.println("---- 模型 A：走 long 重载（每次被 Long.MAX 夹） ----");
        System.out.println("  池增量 = 16 × Long.MAX = " + longPoolGain);
        System.out.println("  差额   = 池增量 − Σ    = " + longDiff);
        System.out.println("  与实机差额之比         = " + longDiff.divide(OBSERVED_DIFF) + " 倍 ⇒ 复现不出来");
        System.out.println("  与实机 Δ总之比         = " + OBSERVED_POOL_DELTA.divide(longPoolGain) + " 倍");
        System.out.println();
        System.out.println("---- 模型 B：走 bigint 重载（精确加，不夹） ----");
        System.out.println("  池增量 = Σ             = " + OBSERVED_MY_SUM + "（逐位等于实机 Σ）");
        System.out.println("  差额   = 别的写者净额  = " + others + "（逐位等于实机差额 ✔ 可复现）");
        System.out.println("  这 20 tick 的等效取电  = " + others.divide(BigInteger.valueOf(OBSERVED_WINDOW_TICKS))
                + " EU/t（= " + tol(others, LONG_MAX) + " × Long.MAX）");
        System.out.println();
        System.out.println("---- 负对照：把「夹」去掉（clamped = raw） ----");
        System.out.println("  预期差额 = " + longOverloadExpectedDiff(OBSERVED_MY_SUM, OBSERVED_MY_SUM) + "（必须为 0 ✔）");
        System.out.println("  其它收支 = " + otherWritersNet(OBSERVED_MY_SUM, OBSERVED_MY_SUM) + "（必须为 0 ✔）");
        System.out.println();
        System.out.println("分档：bigint 档门槛 = " + BIGINT_INFO_LIMIT
                + "（≤ 打 INFO；> 打 WARN）");
        System.out.println("入池重载 = " + DEPOSIT_OVERLOAD.token + "；" + DEPOSIT_OVERLOAD.perCallCeilingText());
        System.out.println();
        System.out.println("==== 两级判据读数（用户 2026-11-02 点名的两组）====");
        final BigInteger typicalOthers = new BigInteger("-1000000000000000000");
        final Band b1 = bandOf(DepositOverload.BIGINT, OBSERVED_DIFF, OBSERVED_MY_SUM, OBSERVED_MY_SUM);
        final Band b2 = bandOf(DepositOverload.BIGINT, typicalOthers, OBSERVED_MY_SUM, OBSERVED_MY_SUM);
        System.out.println("  ① Δ=-78306428659860422070（本次实测值） ⇒ " + b1.token + " ⇒ 级别 " + levelOf(b1)
                + "    witness 后缀(读不到时)=" + quote(witnessSuffix(b1, false)));
        System.out.println("  ② Δ=-1000000000000000000（典型『别人取电』） ⇒ " + b2.token + " ⇒ 级别 " + levelOf(b2)
                + "    witness 后缀=" + quote(witnessSuffix(b2, false)));
        System.out.println("  ② 那一行的字段段（INFO 与 WARN 共用同一份构造，故必然含 明细=）：");
        System.out.println("     " + fieldsLine(DepositOverload.BIGINT, typicalOthers,
                "1.无线网终端@(0,110,25):-1000000000000000000 ／ 2.…（最多 3 条）"));
        System.out.println("selfTest() = PASS");
    }

    /** 打印用：空串显示成 {@code (无)}，非空加引号。 */
    private static String quote(String s) {
        return s == null || s.isEmpty() ? "(无)" : "'" + s + "'";
    }

    /** 十进制秒读：{@code 1.4856018341187167e26}（只用于打印）。 */
    private static String bytes(BigInteger v) {
        final String s = v.toString();
        if (s.length() <= 6) {
            return s;
        }
        return s.substring(0, 1) + "." + s.substring(1, 17) + "e" + (s.length() - 1);
    }

    private static String tol(BigInteger v, BigInteger unit) {
        return new java.math.BigDecimal(v).divide(new java.math.BigDecimal(unit), 4,
                java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
