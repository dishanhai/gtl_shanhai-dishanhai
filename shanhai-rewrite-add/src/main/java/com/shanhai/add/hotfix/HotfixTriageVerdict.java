package com.shanhai.add.hotfix;

/**
 * 本体分诊那一行 {@code [SHANHAI-TRIAGE] solved=… r1=… r2=… r3=… r4=… hp_true=… src=… ｜ player=…}
 * 的<b>解析结果</b>（纯数据，不判任何事）。
 *
 * <h2>为什么要复用本体的日志行，而不是自己再判一遍</h2>
 * 规格里"这一拍分诊判 {@code solved=false}"是<b>本体的结论</b>。add 侧若自己重算一遍，
 * 就会出现"两个地方各有一套判据"的局面 —— 一旦某天本体改了判据，我们就会在<b>它认为解决了</b>
 * 的时候动手。所以这里只做两件事：<b>读它的行</b>、<b>照抄它的结论</b>。
 *
 * <h2>格式（逐字照抄本体 {@code ShanhaiGuardTriage.line(...)}）</h2>
 * <pre>
 *   [SHANHAI-TRIAGE] solved=false r1=true r2=false r3=false r4=true hp_true=0.0 hp_api=0.0 src=die ｜ player=Steve
 * </pre>
 * <p>注意两点：① 前缀里那条空格是<b>本体自己带的</b>（{@code PREFIX = "[SHANHAI-TRIAGE] "}）；
 * ② 玩家名那一段用的是<b>全角竖线</b> {@code ｜}（U+FF5C），不是 ASCII 的 {@code |}
 * —— 后者是 {@code strike_caller} 行里分隔栈帧用的，区分开才不会把帧切坏。
 *
 * <p>本类只碰 {@code java.lang}：离线自检可以拿真实日志行直接喂它。
 */
public final class HotfixTriageVerdict {

    /** 本体分诊行的前缀（**不带**尾部空格：解析时用的是 {@code indexOf}）。 */
    public static final String PREFIX = "[SHANHAI-TRIAGE]";

    /** 本体在玩家名之前用的全角竖线（U+FF5C）。用转义写，免得源文件编码变化把它改掉。 */
    public static final char FULLWIDTH_BAR = '\uFF5C';

    /** 这一拍本体判"解决了"吗（{@code solved=true}）。 */
    public final boolean solved;

    /** R1：我们的监听器这一拍 fire 过。 */
    public final boolean r1;

    /** R2：救完之后字段真值血量 &gt; 0。 */
    public final boolean r2;

    /** R3：外部直调 {@code setHealth(<=0)} 且同拍无事件。 */
    public final boolean r3;

    /** R4：{@code die}/{@code setRemoved} 触达且同拍无伤害事件。 */
    public final boolean r4;

    /** {@code hp_true=} 的原文（{@code NaN} 原样保留，不转成 0）。 */
    public final String hpTrue;

    /** {@code hp_api=} 的原文。 */
    public final String hpApi;

    /** {@code src=} 的原文（事件名／{@code direct}／{@code die}／{@code remove}）。 */
    public final String src;

    /** 尾部 {@code player=<名字>}；没有 ⇒ {@code null}。 */
    public final String player;

    /** 原始那一行（留档用）。 */
    public final String raw;

    /**
     * 这一条是不是<b>我们自己在违规现场抓的</b>（{@code violation_stack}），而不是本体分诊行。
     *
     * <p>🆕 2026-11-08：用户要求"每一次违规/拦截的现场都抓一次完整栈，交给 strike 通道"。
     * 那种现场没有本体那四项读数（{@code r1..r4}），所以这里用 {@link #fromViolation} ＋
     * {@link #whyOverride} 把它和本体分诊行区分开 —— <b>不假装它有 r1..r4</b>。
     */
    public final boolean fromViolation;

    /** 违规现场的 {@code why}（{@code health|die|remove|motion|pos|attack}）；本体行 ⇒ {@code null}。 */
    public final String whyOverride;

    private HotfixTriageVerdict(boolean solved, boolean r1, boolean r2, boolean r3, boolean r4,
                                String hpTrue, String hpApi, String src, String player, String raw,
                                boolean fromViolation, String whyOverride) {
        this.solved = solved;
        this.r1 = r1;
        this.r2 = r2;
        this.r3 = r3;
        this.r4 = r4;
        this.hpTrue = hpTrue == null ? "?" : hpTrue;
        this.hpApi = hpApi == null ? "?" : hpApi;
        this.src = src == null ? "?" : src;
        this.player = player;
        this.raw = raw == null ? "" : raw;
        this.fromViolation = fromViolation;
        this.whyOverride = whyOverride;
    }

    /**
     * 造一条"违规现场"用的 verdict（<b>不是</b>本体分诊结论）。
     *
     * <p>{@code solved=false} 是<b>故意的</b>：语义是"这一拍<b>没</b>解决，有外部写入被我们抓了个正着"，
     * 这样它才能进 {@code HotfixAutoCounter} 那条既有的门槛链（且不需要给那条链开新口子）。
     * {@code r1..r4} 一律 {@code false}（<b>没有测过就是没测过</b>），{@code why()} 走 {@link #whyOverride}。
     *
     * @param where  {@code health|die|remove|motion|pos|attack}
     * @param player 玩家名（可 {@code null}）
     * @return 可用于 {@link HotfixTriageTap.Sink#onVerdict} 的实例
     */
    public static HotfixTriageVerdict ofViolation(String where, String player) {
        final String w = where == null || where.isEmpty() ? "unknown" : where;
        return new HotfixTriageVerdict(false, false, false, false, false, "?", "?", w, player,
                "violation_stack where=" + w + " player=" + (player == null ? "?" : player),
                true, w);
    }

    /**
     * 解析一行日志。
     *
     * @param line 日志原文（可以带 {@code [SHANHAI-TRIAGE]} 之前的内容）
     * @return 解析结果；这一行<b>不是</b>分诊结论行（例如 {@code strike_caller#N} 行）⇒ {@code null}
     */
    public static HotfixTriageVerdict parse(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        if (line.indexOf(PREFIX) < 0) {
            return null;
        }
        final String solvedText = field(line, "solved=");
        if (solvedText == null) {
            return null;   // strike_caller 行没有 solved= ⇒ 不是结论行
        }
        return new HotfixTriageVerdict(
                isTrue(solvedText),
                isTrue(field(line, "r1=")),
                isTrue(field(line, "r2=")),
                isTrue(field(line, "r3=")),
                isTrue(field(line, "r4=")),
                field(line, "hp_true="),
                field(line, "hp_api="),
                field(line, "src="),
                playerOf(line),
                line,
                false,
                null);
    }

    /**
     * 「为什么没解决」的机器可判标签（进日志的 {@code why=} 字段）。
     *
     * <p>顺序是<b>故意的</b>：{@code r3}/{@code r4} 是"外部有人在写"，也就是最值得追到调用方去的两种；
     * 而 {@code r1}/{@code r2} 为假多半说明"我们自己的路没走到"。
     *
     * @return {@code r3}/{@code r4}/{@code r2}/{@code r1}/{@code unknown}
     */
    public String why() {
        if (whyOverride != null) {
            return whyOverride;        // 违规现场：为什么是它自己带的（health/die/remove/motion/pos/attack）
        }
        if (r3) {
            return "r3";
        }
        if (r4) {
            return "r4";
        }
        if (!r2) {
            return "r2";
        }
        if (!r1) {
            return "r1";
        }
        return solved ? "solved" : "unknown";
    }

    /** 一行摘要（不带空格，可直接进日志）。 */
    public String brief() {
        if (fromViolation) {
            // 违规现场没有 r1..r4 四项读数 —— 如实写"没测过"，不编 true/false
            return "solved=false violation=" + src + " why=" + why() + " r1..r4=not_measured"
                    + " player=" + (player == null ? "?" : player);
        }
        return "solved=" + solved + " r1=" + r1 + " r2=" + r2 + " r3=" + r3 + " r4=" + r4
                + " hp_true=" + hpTrue + " src=" + src + " player=" + (player == null ? "?" : player);
    }

    // ------------------------------------------------------------------ 纯文本小工具（离线自检也用）

    /**
     * 取 {@code key=} 后面的那一段值（到空格／全角竖线／ASCII 竖线为止）。
     *
     * @param line 日志原文
     * @param key  <b>带等号</b>的键，例如 {@code "r3="}
     * @return 值；键不存在 ⇒ {@code null}
     */
    public static String field(String line, String key) {
        if (line == null || key == null || key.isEmpty()) {
            return null;
        }
        final int at = line.indexOf(key);
        if (at < 0) {
            return null;
        }
        final int start = at + key.length();
        int end = start;
        while (end < line.length()) {
            final char c = line.charAt(end);
            if (c == ' ' || c == FULLWIDTH_BAR || c == '|') {
                break;
            }
            end++;
        }
        return line.substring(start, end);
    }

    /** 取尾部 {@code player=<名字>}；没有 ⇒ {@code null}。 */
    public static String playerOf(String line) {
        final String v = field(line, "player=");
        return v == null || v.isEmpty() ? null : v;
    }

    private static boolean isTrue(String text) {
        return text != null && ("true".equalsIgnoreCase(text) || "1".equals(text));
    }
}
