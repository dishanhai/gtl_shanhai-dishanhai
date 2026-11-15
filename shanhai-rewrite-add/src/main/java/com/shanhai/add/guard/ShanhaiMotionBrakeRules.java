package com.shanhai.add.guard;

/**
 * 山海 · <b>「写入那一瞬就丢」的动量刹车判据核</b>（<b>纯 JDK</b>：不认 MC、不认 mod、不认类名）。
 *
 * <h2>🔴 它为什么存在（2026-11-08 test2 现场日志给的结论）</h2>
 * <pre>
 *   [SHANHAI-GUARD] 拦截 状态被改写 field=motion target=dgyjh02804
 *     before=(0.000, -0.585, 0.000) after=(0.000, 2.000, 0.000)
 *     source=none_poll approved_from=none restored=true via=attribution note=restored_wait_injection_stack
 *   [SHANHAI-GUARD] knockback_guard 本秒 1 次 ｜ cleared=0 braked=1 ｜ 末次：why=brake ／ 窗口内 ＋ 水平速度 0.6056 > 阈值
 * </pre>
 * 用户报「猫还是能推我」。日志已经把原因写死了：<b>我们看见了、也写回旧速度了（restored=true），但他仍被推</b>。
 * 因为那条路是「<b>下一拍写回</b>」：
 * <pre>
 *   猫写 speed=2.0 → <b>本 tick 服务端已经按 2.0 决定"要不要发速度包"</b> → 下一拍我们才把 2.0 写回旧值
 *   ⇒ 位移/客户端的推动已经发生，写回只修了"字段里的数"，没修"这一拍的动作"
 * </pre>
 * ⇒ 唯一站得住的修法：<b>在写入口（{@code Entity#setDeltaMovement(Vec3)} 的 HEAD）判，判完这一次写入干脆不发生</b>。
 *
 * <h2>🔴 2026-11-08 14:0x 的第二轮现场：刹车本来是有效的，被它自己的保险丝关了</h2>
 * <pre>
 *   motion_brake why=drop reason=foreign_big dv=(2.130, 0.000, -9.863) horiz=10.091 vert=0.000 folded=0
 *   motion_brake why=drop reason=foreign_big dv=(1.168, 0.000,  -9.932) horiz=10.000
 *   motion_brake why=drop reason=foreign_big dv=(0.900, -0.078, -0.008) horiz=0.900 folded=76
 *   brake_disabled reason=sustained drop_ticks=43 gate_off_ticks=1200 now_tick=7893 until_tick=9093
 *   （之后全是）motion_brake why=pass reason=gate_off folded=168
 *   ⇒ 用户原话：「然后我还是会被猫打飞」
 * </pre>
 * 原因：<b>被连续打飞的每一次丢弃都计入了保险丝 ⇒ 43 tick 就把自己关了 1200 tick（60 秒），而那 60 秒正是他被打得最惨的时候</b>。
 * ⇒ 三处修正（本类的全部改动）：
 * <ol>
 *   <li><b>大速度不计入保险丝</b>：只有"模糊带"的丢弃才计入熔断；明显弹飞（{@code horiz > }{@value #HUGE_HORIZ}
 *       或 {@code |Δy| > }{@value #HUGE_VERT}）越多越说明判对了 ⇒ <b>不计入</b>。</li>
 *   <li><b>熔断参数</b>：连续 {@value #SUSTAIN_TICKS} tick（6 秒）才关；关 {@value #GATE_OFF_TICKS} tick（5 秒）后自动恢复。</li>
 *   <li><b>熔断期间仍然丢弃大速度</b>：熔断只意味着"模糊带放行"，明确攻击一律照丢
 *       （{@code reason=foreign_huge}，独立分支，不受 {@code gate_off} 影响）。</li>
 * </ol>
 *
 * <h2>闸门顺序（写死的顺序本身就是规格，改动等于改功能）</h2>
 * <pre>
 *   ⓪ 开关关（momentum_guard=false）                     ⇒ PASS switch_off（静默）
 *   ① self_input（玩家移动包 ／ 我们自己的写回 self_guard）  ⇒ PASS self（静默）
 *   ② 载具 ／ 鞘翅 ／ 旁观 ／ 刚传送                        ⇒ PASS exempt（静默）
 *      b 幅度小（三个分量都 ≤ 0.45：重力/走路/跳跃/击退减速） ⇒ PASS vanilla_small（静默）
 *   ③ 巨大档：horiz &gt; 2.0 或 |Δy| &gt; 1.5                  ⇒ <b>DROP foreign_huge</b>
 *        · 不计入熔断（call 都不 call Gate#noteDrop）
 *        · 不受 gate_off 影响（熔断期间照样丢）
 *   ④ 模糊档：0.6 &lt; horiz ≤ 2.0 或 1.0 &lt; |Δy| ≤ 1.5      ⇒ 若 gate_off ⇒ PASS gate_off（★ 唯一会打 why=pass 的）
 *        · 否则 ⇒ <b>DROP foreign_big</b> 并<b>计入熔断</b>（Gate#noteDrop）
 *   ⑤ 其余（≤0.6 且 ≤1.0 ／ NaN ／ Inf 之外的怪值）        ⇒ PASS other（静默）
 * </pre>
 * ⚠️ 关于 b（{@code vanilla_small}）为什么仍在③④之前：它只影响"每个分量都 ≤0.45、但水平合成略超 0.6"
 * 那一条窄缝（最宽 (0.45,0.45) ⇒ 合成 0.636），而那正是原版走路/跳跃的合成量级。
 * 这是上一轮<b>用户已验收</b>的行为（「宁可漏刹，不许误刹」），本轮不动它 —— 报告里单列「待你确认」。
 *
 * <h2>「丢弃」到底是把什么写下去（y 分量的口径）</h2>
 * <pre>
 *   丢弃 = 原方法被取消（{@code ci.cancel()}）＋ 我们把 (0, keepY, 0) 写下去
 *   keepY = 这次写入想写的 y；<b>除非</b> |Δy| &gt; {@value #DROP_VERT} 且 Δy &gt; 0（那是被弹飞）⇒ keepY = 0
 *   ⚠️ 非有限值（NaN / ±Inf）一律落成 0：绝不把怪值写进字段（那比不刹更糟）
 * </pre>
 * <b>为什么保留 y</b>：只清水平 ⇒ 重力 / 跳跃 / 下落一个都不受影响，玩家不可能因为这条判据"卡死/悬空"。
 * 这与本体 {@code knockback_guard} 的既有口径逐字一致（{@code setDeltaMovement} 的 x/z 清零、y 保留）。
 *
 * <h2>硬闸（防"冻结玩家"复现，用户上次点名的那个事故）＋ 2026-11-08 的参数修正</h2>
 * <pre>
 *   连续 {@value #SUSTAIN_TICKS} tick（6 秒）都在丢<b>模糊带</b> ⇒ 这个玩家自动停手 {@value #GATE_OFF_TICKS} tick（5 秒）
 *   停手期间：模糊带放行（reason=gate_off），<b>巨大档照丢（reason=foreign_huge）</b>
 *   {@value #GATE_OFF_TICKS} tick 之后自动复位，打一行 {@code brake_rearmed dropped_window=… now_tick=…}
 *   任何一次"间隔 &gt; {@value #SUSTAIN_TICKS} tick 才来下一次计入的丢弃"都算新的一轮（不会跨分钟累加）
 *   巨大档的丢弃<b>既不计数也不刷新窗口</b>（它对保险丝完全不可见）
 * </pre>
 * 旧值 40 ／ 1200 的教训写在 {@code temp\guard-brake\保险丝修正.md}：<b>把"判对了的丢弃"当成"可能判错"的证据</b>，
 * 于是 43 tick 就把刹车关掉 60 秒 —— 正好关在用户被打得最惨的时候。
 *
 * <h2>🔴 诚实边界（写死在这里，报告里逐条对应）</h2>
 * <ul>
 *   <li><b>判的是 Δv（这次写入改了多少），不是写入后的绝对值</b>。某个 mod 若分成几拍把速度累加
 *       （每拍 Δ &lt; 阈值），这条判据拦不到 —— 那几拍仍由现有"写回"兜底。</li>
 *   <li><b>纯原版的大幅度推送也会被丢</b>（爆炸 ／ 活塞 ／ 原版击退，只要超阈值）。判据在写入口，
 *       这一刻不取栈（成本），所以分不出"谁推的"。{@code filtered_all}（纯原版栈）这一项因此
 *       只用来解释小幅度那一档，<b>不参与</b> ①~⑤ 的结论（见 {@link #decide} 的注释）。</li>
 *   <li><b>直接写字段</b>（{@code putfield} ／ 反射 ／ 别人的 {@code @Shadow}）绕得开
 *       {@code setDeltaMovement} ⇒ 这条判据<b>根本看不到</b>（与 {@code violation_stack} 同一条边界）。</li>
 *   <li><b>「连续」= 相邻两次计入的丢弃间隔都 ≤ {@value #SUSTAIN_TICKS} tick</b>，streak 累加的是它覆盖的
 *       tick 跨度 —— 稀疏但不停歇的模糊带写入同样会被熔断（口径与上一轮一致，只换了数）。</li>
 *   <li><b>没有在游戏里实测</b>（本轮只做离线自证）：真机上"猫那一击到底是 10.09 格/tick 还是 0.9"
 *       要看下一份日志；本类只保证两种都按规格走。</li>
 * </ul>
 *
 * <h2>为什么判定必须住在这个"纯 JDK"类里</h2>
 * 本项目纪律：凡自己写的检查脚本报出的结果，采信前必须先证明脚本自己是对的。
 * 判定若住在带 MC 依赖的类里，离线自检只能<b>照抄一遍</b> ⇒ 抄错的是脚本还是生产代码就分不清了。
 * ⇒ 判定、硬闸、日志字面量全在这里（{@code javac --release 17} 可单独编译并真跑，
 * 见 {@code temp\guard-brake\MotionBrakeOfflineCheck.java}）；带 MC 的类只负责"取值 / 写 / 打日志"。
 */
public final class ShanhaiMotionBrakeRules {

    /** 与守卫同一套前缀（纯字面量：本类保持零依赖，离线可单独编译）。 */
    public static final String PREFIX = "[SHANHAI-GUARD] ";

    /** 日志词头（用户点名的那一行）。 */
    public static final String TITLE = "motion_brake";

    /** 硬闸踢开时的那一行词头（用户点名：{@code brake_disabled reason=sustained}）。 */
    public static final String DISABLED_TITLE = "brake_disabled";

    /** 硬闸自动复位时的那一行词头（用户点名：{@code brake_rearmed dropped_window=… now_tick=…}）。 */
    public static final String REARMED_TITLE = "brake_rearmed";

    /**
     * 「幅度小」的上限：{@code |Δx|,|Δz|,|Δy|} 都不超过它 ⇒ 一律放行。
     *
     * <p>0.45 的依据：重力一拍 ≈0.078、走路摩擦力 ≈0.02~0.1、跳跃写入 ≈0.42、
     * 击退减速（被推完之后每拍的摩擦衰减）≤0.3 ⇒ 全部落在 0.45 以内。
     */
    public static final double SMALL_LIMIT = 0.45D;

    /**
     * 「模糊档」的水平下限（格/tick），严格大于它才算。
     *
     * <p>与本体 {@code knockback_guard} 同一个数（0.6）：走路 ≈0.2 ／ 冲刺 ≈0.3 ／ 跳跃水平 ≈0.5
     * 都低于它，而"被扔来扔去"通常 1 格/tick 以上。
     */
    public static final double DROP_HORIZ = 0.6D;

    /** 「模糊档」的垂直下限（格/tick，看 {@code |Δy|}），严格大于它才算。 */
    public static final double DROP_VERT = 1.0D;

    /**
     * 「巨大档」的水平下限（格/tick），<b>严格大于</b>它 ⇒ 明确的弹飞。
     *
     * <p>现场那一击是 {@code dv=(2.130, 0, -9.863) horiz=10.091}：<b>10 格/tick</b> 不可能是"玩家自己在走"，
     * 也不可能是判据抖动 ⇒ 它越多越说明<b>判对了</b>，所以它<b>不计入保险丝</b>，也<b>不受保险丝影响</b>。
     */
    public static final double HUGE_HORIZ = 2.0D;

    /**
     * 「巨大档」的垂直下限（格/tick，看 {@code |Δy|}），<b>严格大于</b>它 ⇒ 明确的弹飞。
     *
     * <p>现场的上抛是 {@code Δy = 2.585}（{@code after.y=2.000, before.y=-0.585}）：原版击退最大上抛 0.4~0.5、
     * 跳跃 0.42 ⇒ 1.5 以上只可能是被弹飞。
     */
    public static final double HUGE_VERT = 1.5D;

    /**
     * 连续丢弃这么多 tick（{@value #SUSTAIN_TICKS} tick = 6 秒）⇒ 硬闸踢开。
     *
     * <p>旧值是 40（2 秒）—— 现场 43 tick 就踢开，等于"被打飞 6 秒就把刹车关掉"。现在只有
     * <b>模糊带</b>的连续丢弃才计数，且要连满 6 秒。
     */
    public static final int SUSTAIN_TICKS = 120;

    /**
     * 硬闸踢开之后停手多少个 tick（{@value #GATE_OFF_TICKS} tick = 5 秒），之后自动复位。
     *
     * <p>旧值是 1200（60 秒）—— 正好把用户被打得最惨的那一分钟关掉。
     */
    public static final int GATE_OFF_TICKS = 100;

    /** 「刚传送」豁免的 tick 数（换维度 ／ 复活 ／ {@code isChangingDimension()} 之后）。 */
    public static final int TELEPORT_SKIP_TICKS = 2;

    /** 「从来没有」的哨兵（{@link Long#MIN_VALUE} 直接参与减法会溢出，所以用它的一半）。 */
    public static final long NEVER = Long.MIN_VALUE / 2L;

    private ShanhaiMotionBrakeRules() {}

    // ═══════════════════════════════ 动作词表 / 理由词表 ═══════════════════════════════

    /** 这一次写入的结论（只有两种：让它发生 ／ 丢掉它）。 */
    public enum Action {

        /** 放行（原方法照跑，这次写入照发生）。 */
        PASS,

        /** 丢弃（取消原方法，只把 y 分量写下去）。 */
        DROP
    }

    /**
     * 为什么是这个结论（{@code reason=} 那一格）。
     *
     * <p>{@link #logsPass()} = true 的只有 {@link #GATE_OFF}：用户口径「{@code pass} 只在 {@code reason=gate_off} 时打」，
     * 其余放行一律静默（否则每 tick 都有"玩家自己在走"，必定刷屏）。
     */
    public enum Reason {

        /** ① 玩家自己的操作（{@code self_input}）／ 我们自己的写回（{@code self_guard}）。 */
        SELF("self"),
        /** b. 小幅度的原版写入（重力/走路/跳跃/击退减速）。 */
        VANILLA_SMALL("vanilla_small"),
        /** ④ 模糊档的外部写入，且熔断没开 ⇒ 丢弃。 */
        FOREIGN_BIG("foreign_big"),
        /** ③ 巨大档的外部写入（明确的弹飞）⇒ <b>永远丢弃</b>：不计熔断、不受 gate_off 影响。 */
        FOREIGN_HUGE("foreign_huge"),
        /** ④ 熔断停手期间，模糊档 ⇒ 放行（**唯一**要打 {@code pass} 的理由）。 */
        GATE_OFF("gate_off"),
        /** ② 豁免态（旁观/载具/鞘翅/刚传送）⇒ 放行（静默）。 */
        EXEMPT("exempt"),
        /** ⓪ 开关关 ⇒ 放行（静默）。 */
        SWITCH_OFF("switch_off"),
        /** ⑤ 中间带 / 怪值 ⇒ 放行（静默）。 */
        OTHER("other");

        private final String token;

        Reason(String token) {
            this.token = token;
        }

        /** 进日志的机器名（纯 ASCII）。 */
        public String token() {
            return token;
        }

        /** 放行时要不要打一行 {@code why=pass}（只有硬闸期间打）。 */
        public boolean logsPass() {
            return this == GATE_OFF;
        }
    }

    /** 四种"不许刹车"的豁免（顺序 = 日志/报告里看到的那个顺序，先命中先返回）。 */
    public enum ExemptKind {

        /** 没有豁免 ⇒ 正常判。 */
        NONE("none"),
        /** 旁观者。 */
        SPECTATOR("spectator"),
        /** 鞘翅飞行（自己控制动量）。 */
        FALL_FLYING("fall_flying"),
        /** 在载具里（被载具带着走）。 */
        VEHICLE("vehicle"),
        /** 刚传送（换维度 ／ 复活 ／ {@code isChangingDimension()} 之后 {@value #TELEPORT_SKIP_TICKS} tick）。 */
        JUST_TELEPORTED("just_teleported");

        private final String label;

        ExemptKind(String label) {
            this.label = label;
        }

        /** 进日志的小串（纯 ASCII）。 */
        public String label() {
            return label;
        }

        /** 有豁免 ⇒ 一律不刹。 */
        public boolean exempt() {
            return this != NONE;
        }
    }

    /**
     * 按<b>原版读数</b>挑豁免（先命中先返回；顺序与本体 {@code knockback_guard} 一致）。
     *
     * @param spectator      {@code Player#isSpectator()}
     * @param fallFlying     {@code LivingEntity#isFallFlying()}
     * @param inVehicle      {@code Entity#isPassenger()}（或 {@code getVehicle() != null}）
     * @param justTeleported 换维度 ／ 复活 ／ {@code ServerPlayer#isChangingDimension()} 之后的 {@value #TELEPORT_SKIP_TICKS} tick
     */
    public static ExemptKind exemptKind(boolean spectator, boolean fallFlying, boolean inVehicle,
                                        boolean justTeleported) {
        if (spectator) {
            return ExemptKind.SPECTATOR;
        }
        if (fallFlying) {
            return ExemptKind.FALL_FLYING;
        }
        if (inVehicle) {
            return ExemptKind.VEHICLE;
        }
        if (justTeleported) {
            return ExemptKind.JUST_TELEPORTED;
        }
        return ExemptKind.NONE;
    }

    // ═══════════════════════════════ 判据常数（可注入 ⇒ 负对照） ═══════════════════════════════

    /**
     * 一组可注入的阈值（<b>只为负对照存在</b>：生产只有一个 {@link #production()}）。
     *
     * <p>用户点名的两条负对照就是这样落地的：
     * <ul>
     *   <li>「把'大速度不计熔断'删掉 ⇒ 新增① 必红」= {@code Tuning.production().withHuge(999, 999)}
     *       （巨大档再也命中不了 ⇒ 大速度落进模糊档 ⇒ 计入熔断）；</li>
     *   <li>「把恢复时长改回 1200 ⇒ 新增④ 必红」= {@code Tuning.production().withGateOff(1200)}。</li>
     * </ul>
     * 判据还是同一份代码，只换了数。
     */
    public static final class Tuning {

        /** 「幅度小」上限。 */
        public final double smallLimit;
        /** 「模糊档」水平下限。 */
        public final double dropHoriz;
        /** 「模糊档」垂直下限。 */
        public final double dropVert;
        /** 「巨大档」水平下限（模糊档的上界）。 */
        public final double hugeHoriz;
        /** 「巨大档」垂直下限（模糊档的上界）。 */
        public final double hugeVert;
        /** 连续丢弃多少 tick 后停手。 */
        public final int sustainTicks;
        /** 停手多少个 tick。 */
        public final int gateOffTicks;

        public Tuning(double smallLimit, double dropHoriz, double dropVert,
                      double hugeHoriz, double hugeVert, int sustainTicks, int gateOffTicks) {
            this.smallLimit = smallLimit;
            this.dropHoriz = dropHoriz;
            this.dropVert = dropVert;
            this.hugeHoriz = hugeHoriz;
            this.hugeVert = hugeVert;
            this.sustainTicks = sustainTicks;
            this.gateOffTicks = gateOffTicks;
        }

        /** 生产那一组（唯一一份读数）。 */
        public static Tuning production() {
            return new Tuning(SMALL_LIMIT, DROP_HORIZ, DROP_VERT, HUGE_HORIZ, HUGE_VERT,
                    SUSTAIN_TICKS, GATE_OFF_TICKS);
        }

        /** 换模糊档阈值（负对照：999 ⇒ 该丢的变成放行）。 */
        public Tuning withThresholds(double dropHoriz, double dropVert, double smallLimit) {
            return new Tuning(smallLimit, dropHoriz, dropVert, hugeHoriz, hugeVert, sustainTicks, gateOffTicks);
        }

        /**
         * 换巨大档阈值。
         *
         * <p>{@code withHuge(999, 999)} = <b>把「大速度不计入熔断」这条删掉</b>的等价物：
         * 巨大档再也命中不了 ⇒ 大速度落到模糊档 ⇒ 计入熔断 ⇒ 新增① 必红。
         */
        public Tuning withHuge(double hugeHoriz, double hugeVert) {
            return new Tuning(smallLimit, dropHoriz, dropVert, hugeHoriz, hugeVert, sustainTicks, gateOffTicks);
        }

        /** 换停手时长（负对照：1200 ⇒ 新增④ 必红）。 */
        public Tuning withGateOff(int gateOffTicks) {
            return new Tuning(smallLimit, dropHoriz, dropVert, hugeHoriz, hugeVert, sustainTicks, gateOffTicks);
        }

        /** 换熔断长度（负对照：{@code Integer.MAX_VALUE} ⇒ 一条都不停手）。 */
        public Tuning withSustain(int sustainTicks) {
            return new Tuning(smallLimit, dropHoriz, dropVert, hugeHoriz, hugeVert, sustainTicks, gateOffTicks);
        }

        /** 把硬闸拆掉（{@code sustainTicks} 取到永不可达 ⇒ 一条都不停手）。 */
        public Tuning withoutGate() {
            return new Tuning(smallLimit, dropHoriz, dropVert, hugeHoriz, hugeVert, Integer.MAX_VALUE, gateOffTicks);
        }

        @Override
        public String toString() {
            return "small<=" + smallLimit
                    + " drop_horiz>" + dropHoriz + " drop_vert>" + dropVert
                    + " huge_horiz>" + hugeHoriz + " huge_vert>" + hugeVert
                    + " sustain=" + sustainTicks + " gate_off=" + gateOffTicks;
        }
    }

    // ═══════════════════════════════ 输入 / 结论 ═══════════════════════════════

    /**
     * 一次写入的现场（<b>全是纯数据</b>：谁都不认识，离线自检直接 new）。
     *
     * <p>{@code cur*} = 这一刻字段里<b>已有</b>的动量（HEAD 里读到的就是旧值）；
     * {@code new*} = 这一次要写进去的值（原方法的入参）。Δv = new − cur。
     */
    public static final class In {

        /** 该开关现在开着吗。 */
        public final boolean enabled;
        /** 这次写入是不是"自己的"（{@code self_input} ／ {@code self_guard}）。 */
        public final boolean selfOrigin;
        /** 豁免态（{@link #exemptKind}）。 */
        public final ExemptKind exempt;

        /** 现在字段里的动量。 */
        public final double curX;
        public final double curY;
        public final double curZ;
        /** 这一次要写进去的动量。 */
        public final double newX;
        public final double newY;
        public final double newZ;

        public In(boolean enabled, boolean selfOrigin, ExemptKind exempt,
                  double curX, double curY, double curZ,
                  double newX, double newY, double newZ) {
            this.enabled = enabled;
            this.selfOrigin = selfOrigin;
            this.exempt = exempt == null ? ExemptKind.NONE : exempt;
            this.curX = curX;
            this.curY = curY;
            this.curZ = curZ;
            this.newX = newX;
            this.newY = newY;
            this.newZ = newZ;
        }

        /** 同 {@link #In} 的工厂（写法短一点；{@code exempt} 允许 {@code null} = 无豁免）。 */
        public static In of(boolean enabled, boolean selfOrigin, ExemptKind exempt,
                            double curX, double curY, double curZ,
                            double newX, double newY, double newZ) {
            return new In(enabled, selfOrigin, exempt, curX, curY, curZ, newX, newY, newZ);
        }
    }

    /** 一次判定的结论（不可变；日志/动作都由它带出来）。 */
    public static final class Decision {

        /** 放行还是丢弃。 */
        public final Action action;
        /** 理由（决定日志里 {@code reason=} 与是否打 {@code why=pass}）。 */
        public final Reason reason;
        /** 这一次的豁免（原样带出来，读数用）。 */
        public final ExemptKind exempt;

        /** Δv = 这次写入改了多少（new − cur）。 */
        public final double dvx;
        public final double dvy;
        public final double dvz;
        /** 水平幅度 {@code hypot(Δx, Δz)}（≥0）。 */
        public final double horiz;
        /** 垂直幅度（<b>带符号</b>：上抛为正；判阈值时取绝对值）。 */
        public final double vert;
        /** 丢弃时真正要写的 y（只有 {@code action==DROP} 有意义）。 */
        public final double keepY;
        /** 这一次丢弃刚好把硬闸踢开了（调用方据此打 {@code brake_disabled reason=sustained}）。 */
        public final boolean trippedGate;
        /** 这一次调用刚好让硬闸复位了（调用方据此打一行 {@code brake_rearmed …}，只打一次）。 */
        public final boolean rearmedGate;

        public Decision(Action action, Reason reason, ExemptKind exempt,
                        double dvx, double dvy, double dvz, double horiz, double vert,
                        double keepY, boolean trippedGate, boolean rearmedGate) {
            this.action = action;
            this.reason = reason;
            this.exempt = exempt == null ? ExemptKind.NONE : exempt;
            this.dvx = dvx;
            this.dvy = dvy;
            this.dvz = dvz;
            this.horiz = horiz;
            this.vert = vert;
            this.keepY = keepY;
            this.trippedGate = trippedGate;
            this.rearmedGate = rearmedGate;
        }

        /** 要丢这一次写入吗。 */
        public boolean drop() {
            return action == Action.DROP;
        }

        /** 放行吗。 */
        public boolean pass() {
            return action == Action.PASS;
        }

        /** 这是「巨大档」的丢弃吗（明确的弹飞；不计熔断、不看 gate_off）。 */
        public boolean huge() {
            return reason == Reason.FOREIGN_HUGE;
        }

        /** 打日志吗（丢弃必打；放行只有硬闸那一种要打）。 */
        public boolean shouldLog() {
            return drop() || reason.logsPass();
        }

        /** {@code why=drop|pass}。 */
        public String why() {
            return drop() ? "drop" : "pass";
        }

        /** {@code dv=(…)} 那一段（三位小数、点号；NaN/Inf 原样写出，不假装是数）。 */
        public String dvText() {
            return vec(dvx, dvy, dvz);
        }

        @Override
        public String toString() {
            return action + "/" + reason.token() + " dv=" + dvText()
                    + " horiz=" + num(horiz) + " vert=" + num(vert)
                    + (drop() ? " keep_y=" + num(keepY) : "")
                    + (trippedGate ? " tripped_gate" : "") + (rearmedGate ? " rearmed_gate" : "");
        }
    }

    // ═══════════════════════════════ 判据（唯一共识） ═══════════════════════════════

    /** 生产阈值的那一组（{@link Tuning#production()}）。 */
    public static Decision decide(In in) {
        return decide(in, Tuning.production(), null, 0L);
    }

    /** 同一套判据（可换阈值；{@code gate} 为 {@code null} = 没有硬闸）。 */
    public static Decision decide(In in, Tuning tuning) {
        return decide(in, tuning, null, 0L);
    }

    /**
     * <b>判据本体</b>（顺序即规格；带硬闸与可注入时钟）。
     *
     * <pre>
     *   ⓪ 开关关                          → PASS switch_off（静默；连 gate_off 都不打）
     *   ① self_input                      → PASS self（静默）
     *   ② 载具／鞘翅／旁观／刚传送          → PASS exempt（静默）
     *   b 幅度小（三分量都 ≤ 0.45）        → PASS vanilla_small（静默）
     *   ③ 巨大档（horiz&gt;2.0 或 |Δy|&gt;1.5）→ <b>DROP foreign_huge</b>（不计熔断、不受 gate_off 影响）
     *   ④ 模糊档（0.6&lt;horiz≤2.0 或 1.0&lt;|Δy|≤1.5）
     *         · gate_off 开着               → PASS gate_off（★ 唯一会打 why=pass 的一种）
     *         · 否则                        → <b>DROP foreign_big</b> ＋ 计入熔断（Gate#noteDrop）
     *   ⑤ 其余                            → PASS other（静默；含 NaN 这类怪值：宁可漏刹，不许误刹）
     * </pre>
     *
     * <p>⚠️ <b>关于 {@code filtered_all}（纯原版栈）</b>：规格里它挂在 b（小幅度）那一档。
     * 本站口径是「小幅度本来就放行」⇒ 结论与是否取栈<b>无关</b>；而在写入口<b>每一拍</b>取栈
     * （{@code StackWalker} 几十微秒 × 每玩家每拍 3~6 次）会把 tick 拖慢，与既有纪律
     * 「不在快路径上抓栈」冲突 ⇒ 本类<b>不接收</b>这个入参，栈只在真丢弃时抓一次（1/s 限流）。
     *
     * <p>⚠️ <b>保险丝复位（{@code brake_rearmed}）在任何分支之前采样一次</b>：它跟这一次写入该丢还是该放无关，
     * 只跟"时间走过了 {@value #GATE_OFF_TICKS} tick"有关；{@link Decision#rearmedGate} 只会在真正复位的那一刻
     * 为 true（{@link Gate#pollRearm} 一次性），所以调用方可以放心地"见 true 就打一行"。
     *
     * @param in      现场
     * @param tuning  阈值（生产 = {@link Tuning#production()}）
     * @param gate    这个玩家的硬闸（{@code null} = 不做硬闸）
     * @param nowTick 现在（服务端 {@code level.getGameTime()}；可注入 ⇒ 离线自检能跑 6 秒＋5 秒）
     */
    public static Decision decide(In in, Tuning tuning, Gate gate, long nowTick) {
        final Tuning t = tuning == null ? Tuning.production() : tuning;
        final double dvx = in.newX - in.curX;
        final double dvy = in.newY - in.curY;
        final double dvz = in.newZ - in.curZ;
        final double horiz = horizontal(dvx, dvz);
        final ExemptKind ex = in.exempt == null ? ExemptKind.NONE : in.exempt;

        // 保险丝复位（一次性采样；时间走到 offUntil 之后第一次调用返回 true）
        final boolean rearmed = gate != null && gate.pollRearm(nowTick);

        // ⓪ 开关关 ⇒ 一个字节都不碰（也不打日志）
        if (!in.enabled) {
            return pass(Reason.SWITCH_OFF, ex, dvx, dvy, dvz, horiz, rearmed);
        }
        // ① 玩家自己的操作 / 我们自己的写入
        if (in.selfOrigin) {
            return pass(Reason.SELF, ex, dvx, dvy, dvz, horiz, rearmed);
        }
        // ② 四条豁免
        if (ex.exempt()) {
            return pass(Reason.EXEMPT, ex, dvx, dvy, dvz, horiz, rearmed);
        }
        // b 幅度小（重力 / 走路 / 跳跃 / 击退减速）
        if (small(dvx, dvy, dvz, t.smallLimit)) {
            return pass(Reason.VANILLA_SMALL, ex, dvx, dvy, dvz, horiz, rearmed);
        }
        // ③ 巨大档 ⇒ 永远丢弃：不计熔断、不受 gate_off 影响（保险丝只关"模糊带"）
        if (huge(dvx, dvy, dvz, t)) {
            final double keep = keepY(in.newY, dvy, t.dropVert);
            return new Decision(Action.DROP, Reason.FOREIGN_HUGE, ex,
                    dvx, dvy, dvz, horiz, dvy, keep, false, rearmed);
        }
        // ④ 模糊档 ⇒ 熔断期间放行，否则丢弃并计入熔断
        if (blurred(dvx, dvy, dvz, t)) {
            if (gate != null && gate.off(nowTick)) {
                gate.noteGateSkip();
                return pass(Reason.GATE_OFF, ex, dvx, dvy, dvz, horiz, rearmed);
            }
            final double keep = keepY(in.newY, dvy, t.dropVert);
            final boolean tripped = gate != null && gate.noteDrop(nowTick, t);
            return new Decision(Action.DROP, Reason.FOREIGN_BIG, ex,
                    dvx, dvy, dvz, horiz, dvy, keep, tripped, rearmed);
        }
        // ⑤ 中间带（含 NaN / Inf：comparisons 全 false ⇒ 走到这里 ⇒ 放行）
        return pass(Reason.OTHER, ex, dvx, dvy, dvz, horiz, rearmed);
    }

    /** 放行的一次组装（keepY 无意义 ⇒ 0）。 */
    private static Decision pass(Reason reason, ExemptKind ex,
                                 double dvx, double dvy, double dvz, double horiz, boolean rearmed) {
        return new Decision(Action.PASS, reason, ex, dvx, dvy, dvz, horiz, dvy, 0.0D, false, rearmed);
    }

    /** 幅度小（三个分量都 ≤ 上限；NaN ⇒ false ⇒ 不会因为怪值被判"小"）。 */
    public static boolean small(double dvx, double dvy, double dvz, double limit) {
        return Math.abs(dvx) <= limit && Math.abs(dvz) <= limit && Math.abs(dvy) <= limit;
    }

    /** 超阈值（水平 {@code hypot} 严格大于 {@code dropHoriz}，<b>或</b> {@code |Δy|} 严格大于 {@code dropVert}）。 */
    public static boolean big(double dvx, double dvy, double dvz, double dropHoriz, double dropVert) {
        final double h = horizontal(dvx, dvz);
        return h > dropHoriz || Math.abs(dvy) > dropVert;
    }

    /**
     * 「巨大档」：{@code horiz > hugeHoriz} <b>或</b> {@code |Δy| > hugeVert} ⇒ 明确的弹飞。
     *
     * <p>NaN 一律 false（怪值不许被判成"弹飞"）；±Inf 一律 true（那是怪值里最该丢的一种，keepY 会落成 0）。
     */
    public static boolean huge(double dvx, double dvy, double dvz, Tuning tuning) {
        final Tuning t = tuning == null ? Tuning.production() : tuning;
        final double h = horizontal(dvx, dvz);
        return h > t.hugeHoriz || Math.abs(dvy) > t.hugeVert;
    }

    /**
     * 「模糊档」：超了模糊档阈值、但<b>还没到巨大档</b>（= 0.6 &lt; horiz ≤ 2.0 或 1.0 &lt; |Δy| ≤ 1.5）。
     *
     * <p>只有这一档会<b>计入熔断</b>，也只有这一档在熔断期间被放行。
     * 混合情形（水平在模糊带、垂直在巨大带）由③先命中 ⇒ 归巨大档。
     */
    public static boolean blurred(double dvx, double dvy, double dvz, Tuning tuning) {
        final Tuning t = tuning == null ? Tuning.production() : tuning;
        return big(dvx, dvy, dvz, t.dropHoriz, t.dropVert) && !huge(dvx, dvy, dvz, t);
    }

    /**
     * 丢弃时真正写下去的 y。
     *
     * <pre>
     *   默认 = 这次想写的 y（只清水平 ⇒ 重力/跳跃/下落不受影响，不可能卡死）
     *   |Δy| &gt; 阈值 且 Δy &gt; 0（被弹飞）⇒ 0
     *   非有限值（NaN / ±Inf）      ⇒ 0（绝不把怪值写进字段）
     * </pre>
     */
    public static double keepY(double newY, double dvy, double dropVert) {
        double keep = newY;
        if (Math.abs(dvy) > dropVert && dvy > 0.0D) {
            keep = 0.0D;
        }
        if (Double.isNaN(keep) || Double.isInfinite(keep)) {
            keep = 0.0D;
        }
        return keep;
    }

    /** 水平幅度 = {@code hypot(dx, dz)}（NaN 传播；任一 Infinity ⇒ Infinity）。 */
    public static double horizontal(double dx, double dz) {
        return Math.hypot(dx, dz);
    }

    // ═══════════════════════════════ 硬闸（逐玩家；时钟可注入） ═══════════════════════════════

    /**
     * 一个玩家的「连续丢弃 ⇒ 停手 5 秒 ⇒ 自动复位」状态机（<b>只在服务端线程里读写</b>）。
     *
     * <p>为什么必须有它：上一轮"刹车"把玩家冻住过一次（用户原话「我被打的时候自己都动不了了」）。
     * 但 2026-11-08 14:0x 的现场证明<b>它把刹车关在了最不该关的时候</b>（43 tick ⇒ 关 1200 tick），
     * 所以本轮改成：
     * <pre>
     *   只有<b>模糊档</b>的丢弃才调用 {@link #noteDrop}（巨大档走独立分支，根本不进来）
     *   每一次计入的丢弃让 streak 增加「距上一次计入的丢弃过了几个 tick」（同一 tick 内多次只算一次）
     *   两次计入的丢弃间隔 &gt; sustain（120 tick）⇒ 算新的一轮，从 1 重新数
     *   streak ≥ 120 ⇒ 踢开：until = now + 100 tick，打 {@code brake_disabled reason=sustained drop_ticks=120 …}
     *   nowTick 走到 until ⇒ 下一次调用 {@link #pollRearm} 返回 true（<b>一次性</b>），打 {@code brake_rearmed …}
     * </pre>
     */
    public static final class Gate {

        /** 连续计入的丢弃已经覆盖的 tick 数。 */
        private int streakTicks;
        /** 上一次计入的丢弃的 tick（{@link #NEVER} = 还没有）。 */
        private long lastDropTick = NEVER;
        /** 硬闸开到什么时候（{@code nowTick < offUntil} ⇒ 停手）。 */
        private long offUntil = NEVER;
        /** 踢开过几次。 */
        private long trips;
        /** 一共计入过多少次丢弃（只算模糊档）。 */
        private long drops;
        /** 停手期间一共放行了多少次。 */
        private long gateSkips;
        /** 复位过几次。 */
        private long rearms;
        /** 最近一次踢开时用的停手长度（读数）。 */
        private int lastGateOffTicks;
        /** 最近一次踢开时的连续丢弃 tick 数（读数：{@code brake_disabled drop_ticks=120}）。 */
        private int lastStreakTicks;
        /** 当前这一轮里计入了多少次丢弃。 */
        private int windowDrops;
        /** 最近一次踢开时，那一轮里计入了多少次丢弃（读数：{@code brake_rearmed dropped_window=120}）。 */
        private int lastWindowDrops;
        /** 已经踢开、还没复位（⇒ 时间一到就要打一行 {@code brake_rearmed}）。 */
        private boolean rearmPending;

        /** 现在是不是停手期间（{@code nowTick < offUntil}）。 */
        public boolean off(long nowTick) {
            return offUntil != NEVER && nowTick < offUntil;
        }

        /**
         * 保险丝复位：<b>一次性</b>（时间走到 {@code offUntil} 之后的第一次调用返回 true）。
         *
         * @return true = 这一次刚复位（调用方立刻打一行 {@code brake_rearmed dropped_window=… now_tick=…}）
         */
        public boolean pollRearm(long nowTick) {
            if (!rearmPending || nowTick < offUntil) {
                return false;
            }
            rearmPending = false;
            rearms++;
            return true;
        }

        /**
         * 记一次<b>模糊档</b>丢弃（<b>判据已经决定要丢</b>之后调；巨大档<b>不许</b>调它）。
         *
         * @return true = 这一次丢弃把硬闸踢开了（调用方立刻打 {@code brake_disabled reason=sustained}）
         */
        public boolean noteDrop(long nowTick, Tuning tuning) {
            final Tuning t = tuning == null ? Tuning.production() : tuning;
            drops++;
            if (lastDropTick == NEVER || nowTick < lastDropTick || nowTick - lastDropTick > t.sustainTicks) {
                streakTicks = 1;                       // 新的一轮
                windowDrops = 1;
            } else if (nowTick > lastDropTick) {
                final long add = nowTick - lastDropTick;
                final long room = Integer.MAX_VALUE / 4L - streakTicks;
                streakTicks += (int) Math.min(add, room);
                if (windowDrops < Integer.MAX_VALUE) {
                    windowDrops++;
                }
            }
            // nowTick == lastDropTick（同一 tick 内多次丢弃）⇒ 只算一次：tick 跨度与次数都不动
            lastDropTick = nowTick;
            if (streakTicks >= t.sustainTicks) {
                offUntil = nowTick + t.gateOffTicks;
                lastGateOffTicks = t.gateOffTicks;
                lastStreakTicks = streakTicks;
                lastWindowDrops = windowDrops;
                rearmPending = true;
                trips++;
                streakTicks = 0;
                windowDrops = 0;
                lastDropTick = NEVER;
                return true;
            }
            return false;
        }

        /** 停手期间放行了一次（读数）。 */
        public void noteGateSkip() {
            gateSkips++;
        }

        /** 全部清零（停服 / 换局；**不**改读数）。 */
        public void reset() {
            streakTicks = 0;
            windowDrops = 0;
            lastDropTick = NEVER;
            offUntil = NEVER;
            rearmPending = false;
        }

        /** 读到 0（离线自检 / 换局）。 */
        public void resetCounters() {
            trips = 0L;
            drops = 0L;
            gateSkips = 0L;
            rearms = 0L;
            lastGateOffTicks = 0;
            lastStreakTicks = 0;
            lastWindowDrops = 0;
        }

        /** 当前连续计入的丢弃覆盖的 tick 数。 */
        public int streakTicks() {
            return streakTicks;
        }

        /** 最近一次踢开时的连续丢弃 tick 数（= {@code brake_disabled drop_ticks=} 那一格）。 */
        public int lastStreakTicks() {
            return lastStreakTicks;
        }

        /** 最近一次踢开那一轮里计入了多少次丢弃（= {@code brake_rearmed dropped_window=} 那一格）。 */
        public int lastWindowDrops() {
            return lastWindowDrops;
        }

        /** 当前这一轮里计入了多少次丢弃。 */
        public int windowDrops() {
            return windowDrops;
        }

        /** 硬闸开到哪个 tick（{@link #NEVER} = 没开过）。 */
        public long offUntil() {
            return offUntil;
        }

        /** 踢开过几次。 */
        public long trips() {
            return trips;
        }

        /** 计入过多少次丢弃（只算模糊档）。 */
        public long drops() {
            return drops;
        }

        /** 停手期间放行过多少次。 */
        public long gateSkips() {
            return gateSkips;
        }

        /** 复位过几次。 */
        public long rearms() {
            return rearms;
        }

        /** 最近一次踢开用的停手长度（tick）。 */
        public int lastGateOffTicks() {
            return lastGateOffTicks;
        }

        /** 一行读数。 */
        public String statusLine() {
            return "streak_ticks=" + streakTicks
                    + " window_drops=" + windowDrops
                    + " trips=" + trips
                    + " rearms=" + rearms
                    + " drops=" + drops
                    + " gate_skips=" + gateSkips
                    + " gate_off_ticks=" + lastGateOffTicks
                    + " off_until=" + offUntil
                    + " rearm_pending=" + rearmPending;
        }
    }

    // ═══════════════════════════════ 日志字面量（纯函数 ⇒ 离线可逐字断言） ═══════════════════════════════

    /**
     * 用户点名的那一行（<b>完整一行，含前缀</b>）：
     * <pre>
     *   [SHANHAI-GUARD] motion_brake why=drop|pass \
     *        reason=self|vanilla_small|foreign_big|foreign_huge|gate_off \
     *        dv=(x,y,z) horiz=… vert=… folded=N
     * </pre>
     *
     * @param decision 结论
     * @param folded   上一条落盘以来被 1/s 挡下的次数（与 {@code ShanhaiStateLog} 同一套语义）
     */
    public static String logLine(Decision decision, long folded) {
        return PREFIX + TITLE
                + " why=" + decision.why()
                + " reason=" + decision.reason.token()
                + " dv=" + decision.dvText()
                + " horiz=" + num(decision.horiz)
                + " vert=" + num(decision.vert)
                + " folded=" + Math.max(0L, folded);
    }

    /**
     * 硬闸踢开的那一行：{@code [SHANHAI-GUARD] brake_disabled reason=sustained drop_ticks=120 gate_off_ticks=100 …}。
     *
     * @param gate    这个玩家的硬闸
     * @param nowTick 现在（服务端 tick）
     * @param folded  折叠计数（同上）
     */
    public static String disabledLine(Gate gate, long nowTick, long folded) {
        return PREFIX + DISABLED_TITLE
                + " reason=sustained"
                + " drop_ticks=" + (gate == null ? 0 : gate.lastStreakTicks())
                + " gate_off_ticks=" + (gate == null ? 0 : gate.lastGateOffTicks())
                + " now_tick=" + nowTick
                + " until_tick=" + (gate == null ? NEVER : gate.offUntil())
                + " folded=" + Math.max(0L, folded);
    }

    /**
     * 硬闸复位的那一行（用户点名的字面量，<b>一次一行，不折叠</b>）：
     * <pre>
     *   [SHANHAI-GUARD] brake_rearmed dropped_window=120 now_tick=1300
     * </pre>
     * {@code dropped_window} = 触发这次熔断的那一轮里<b>计入熔断的丢弃次数</b>
     * （与 {@code brake_disabled drop_ticks=} 同一轮、同一口径；逐 tick 连续丢时两个数相等）。
     *
     * @param gate    这个玩家的硬闸
     * @param nowTick 现在（服务端 tick）
     */
    public static String rearmLine(Gate gate, long nowTick) {
        return PREFIX + REARMED_TITLE
                + " dropped_window=" + (gate == null ? 0 : gate.lastWindowDrops())
                + " now_tick=" + nowTick;
    }

    /** 一个三分量值：{@code (x, y, z)}（三位小数，{@link java.util.Locale#ROOT} 防逗号小数点）。 */
    public static String vec(double x, double y, double z) {
        return "(" + num(x) + ", " + num(y) + ", " + num(z) + ")";
    }

    /** 单个数（三位小数；NaN/±Inf 原样写字面量，不假装是数）。 */
    public static String num(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
