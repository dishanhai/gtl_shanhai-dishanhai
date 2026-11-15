package com.shanhai.client.holo;

import java.util.Locale;

/**
 * 山海重构 · 悬浮全息「<b>画到最上层</b>」（ontop）实验的<b>纯逻辑核</b>。
 *
 * <h2>0. 这个类解决的问题是哪一个</h2>
 * 上一轮的实机读数已经把"看不见板"的三条候选排掉两条：
 * <pre>
 *   drawn_boards=5 alpha=1.000 scale=1.000   ⇒ 不是没画
 *   occluder=none                             ⇒ 我们确实提交了可见内容（不是没画，也不是全透明）
 *   depth_test=1 depth_write=1 blend=0        ⇒ 上家留给我们的 GL 状态正常
 * </pre>
 * 剩下唯一没排除的是<b>世界深度遮挡</b>：板画在 {@code AFTER_PARTICLES}，那时地形深度已经在缓冲里，
 * 板子在玩家周围 2.8 格，贴墙/贴方块时会被地形深度挡掉。
 *
 * <p>⇒ 本类只做两件事，且都是<b>纯算术</b>（零 MC / 零 GL，可离线逐条验）：
 * <ol>
 *   <li><b>ontop 开关的状态机</b>：默认值、翻转、"关掉之后不再改状态"；</li>
 *   <li><b>深度遮挡判定</b>：{@code occluded = depth_at_center < board_ndc_z}（单位见 §2）。</li>
 * </ol>
 * 渲染器（{@link ShanhaiHoloMenuRenderer}）只负责"按这里的结论去设 GL 状态 / 拼日志"，
 * <b>不自己判</b>任何一个布尔 —— 于是这一层能不开游戏就验完。
 *
 * <h2>1. 开关语义（不落配置，默认 on）</h2>
 * <pre>
 *   /shanhai_add holo ontop        无参 ⇒ 翻转（on ⇄ off）
 *   /shanhai_add holo ontop on     ⇒ true
 *   /shanhai_add holo ontop off    ⇒ false；<b>关掉之后本类不再把它改回 true</b>
 *                                  （除了再一次显式 on / 无参翻转）
 *   非法参数                        ⇒ 状态不变（{@link #resolve} 原样返回 current）
 * </pre>
 * 「不落配置」是刻意的：这是一个<b>可逆的实验开关</b>，下次进世界回到默认值 on，
 * 不会在 {@code config/shanhai-client.toml} 里留下一份"上次实验留下的状态"。
 *
 * <h2>2. 🔴 单位：两个深度必须换到同一个空间才能比</h2>
 * <ul>
 *   <li>{@code glReadPixels(GL_DEPTH_COMPONENT, GL_FLOAT)} 读出来的<b>窗口深度</b> ∈ [0,1]
 *       （0 = 近裁剪面，1 = 远裁剪面）；</li>
 *   <li>我们的投影（{@link ShanhaiHoloViewMath#proj}）给的是 <b>NDC z</b> ∈ [−1,1]。</li>
 * </ul>
 * ⇒ 换算式（固定：{@code ndc ∈ [−1,1]} ↔ {@code window ∈ [0,1]}，<b>线性的</b>，
 * 因为 OpenGL 的 NDC→窗口映射本身就是 {@code (ndc+1)/2}）：
 * <pre>
 *   windowZ(ndcZ) = ndcZ * 0.5 + 0.5
 *   occluded      = depth_at_center &lt; windowZ(board_ndc_z)      ← 严格小于
 * </pre>
 * 判据的三条边界（{@link #selfTest()} 每一条都有正例）：
 * <table border="1">
 *   <tr><th>情形</th><th>occluded</th><th>为什么</th></tr>
 *   <tr><td>两者<b>恰好相等</b></td><td>{@code false}</td>
 *       <td>深度测试自身就是 {@code GL_LEQUAL}（相等通过）⇒ "相等"不算被挡，两端口径要一致</td></tr>
 *   <tr><td>{@code depth_at_center} 是 {@code na}（{@link Double#NaN}）</td><td>{@code false}</td>
 *       <td>「不知道」不许被读成「被挡住」；日志里也写 {@code na} 而不是编一个 0/1</td></tr>
 *   <tr><td>板心在屏外 / 在相机背后</td><td>不参与判定（调用方直接给 {@code na}）</td>
 *       <td>读一个屏外像素的深度没有意义</td></tr>
 * </table>
 *
 * <h2>3. 日志字段（两个，都追加在 visibility 那个已有探针行的<b>行末</b>，不新增行）</h2>
 * <pre>
 *   … depth_occluded=1
 *   ontop=1 depth_at_center=0.987654 board_ndc_z=-0.020000 depth_occluded=1
 *   ontop=0 depth_at_center=na board_ndc_z=na depth_occluded=na      （读不到 / 屏外 / 没画板）
 * </pre>
 * 另有一条<b>单独的事件行</b>（只在 ontop 打开时、状态变化那一刻 + 每 2 秒最多一行）：
 * <pre>
 *   [SHANHAI-HOLO] holo_render_state depth_test=1 -&gt; 0 ontop=true boards=5 text=see_through restored=true
 * </pre>
 *
 * <p>⚠️ 本类<b>零依赖</b>：只有 {@code java.util.Locale}。离线自证 = 直接用 javac 编这一个文件再跑
 * {@link #main}（见 {@code temp/holo-occluder/ontop-selfcheck/run.ps1}）。
 */
public final class ShanhaiHoloOntop {

    private ShanhaiHoloOntop() {}

    /**
     * 开关的<b>默认值</b>。🔴 用户拍板：默认 <b>on</b>（"如果确实是 [深度遮挡]，默认值就留着 on"）。
     * <p>改这一个常量就等于改"所有人第一次进世界的状态"（本项目里只有这一处定义）。
     */
    public static final boolean DEFAULT_ON = true;

    /** 读不到 / 不适用时的文本（<b>固定</b>这两个字母；下游按它判"这条读数不可用"，不许写 0）。 */
    public static final String NA = "na";

    /**
     * 板心深度读数的限流（纳秒）。{@code glReadPixels} 是一次 GPU 同步点，
     * 每帧读会把帧预算吃掉一截 ⇒ 1 次 / 500 ms（与 visibility 那一行的节拍同一个数）。
     */
    public static final long DEPTH_READ_THROTTLE_NANOS = 500_000_000L;

    /** 日志里那条事件行的行首（固定：{@code holo_render_state}）。 */
    public static final String RENDER_STATE_PREFIX = "holo_render_state";

    /** 命令那一行的行首（固定：{@code holo_ontop}）。 */
    public static final String SWITCH_PREFIX = "holo_ontop";

    // ==================================================================== 一、开关状态机

    /** 初始值 = {@link #DEFAULT_ON}（渲染器在类加载时取它，不自己再写一个 true）。 */
    public static boolean initial() {
        return DEFAULT_ON;
    }

    /**
     * <b>唯一</b>的开关状态机：给"现在的状态"和"命令参数"，返回"之后的状态"。
     *
     * <p>三条语义（逐条与 {@link #selfTest()} 的 S1–S8 对应）：
     * <ul>
     *   <li>{@code arg == null}（无参）⇒ <b>翻转</b>；</li>
     *   <li>{@code "on"/"true"/"1"} ⇒ {@code true}；{@code "off"/"false"/"0"} ⇒ {@code false}；</li>
     *   <li>其它（拼错了 / 大小写怪）⇒ <b>原样返回 current</b>（不改状态，也不抛）。
     *       这一步是"关掉之后不会再被悄悄打开"的机械保证：除非显式 on 或无参翻转，
     *       其余任何输入都只会得到当前值。</li>
     * </ul>
     */
    public static boolean resolve(boolean current, String arg) {
        if (arg == null) {
            return !current;
        }
        final String a = arg.trim();
        if (a.isEmpty()) {
            return !current;
        }
        if (a.equalsIgnoreCase("on") || a.equalsIgnoreCase("true") || a.equals("1")) {
            return true;
        }
        if (a.equalsIgnoreCase("off") || a.equalsIgnoreCase("false") || a.equals("0")) {
            return false;
        }
        return current;
    }

    /** 命令那一行：{@code holo_ontop on=true} / {@code holo_ontop on=false}。 */
    public static String switchLine(boolean on) {
        return SWITCH_PREFIX + " on=" + on;
    }

    // ==================================================================== 二、深度遮挡判定

    /**
     * NDC z ∈ [−1,1] → 窗口深度 ∈ [0,1]（{@code (ndc+1)/2}）。非有限值原样返回 {@link Double#NaN}。
     *
     * <p>为什么要这一个函数、而不是两处各写一遍 {@code *0.5+0.5}：
     * "读出来的那个数"和"比出来的那个数"必须<b>同一个口径</b>，否则日志看着对、判定是错的
     * （本工程踩过同族的坑：单位/起点没对齐 ⇒ 报了一堆假不一致）。
     */
    public static double windowZ(double ndcZ) {
        if (!isFinite(ndcZ)) {
            return Double.NaN;
        }
        return ndcZ * 0.5 + 0.5;
    }

    /**
     * 🔴 <b>核心判定</b>：板心被世界深度挡住了吗？
     *
     * @param depthAtCenter {@code glReadPixels} 读到的窗口深度 ∈ [0,1]；读不到/不适用 ⇒ {@link Double#NaN}
     * @param boardNdcZ     板心投影的 NDC z ∈ [−1,1]；不可投影 ⇒ {@link Double#NaN}
     * @return {@code true} = <b>地形挡在板前面</b>（{@code depth_at_center < windowZ(board_ndc_z)}）；
     *         任一侧 {@code na}、或两者恰好相等 ⇒ {@code false}
     */
    public static boolean occluded(double depthAtCenter, double boardNdcZ) {
        return occludedWindow(depthAtCenter, windowZ(boardNdcZ));
    }

    /** 同上，但右端已经是<b>窗口深度</b>（给已经换算好的一方用；语义与 {@link #occluded} 逐字相同）。 */
    public static boolean occludedWindow(double depthAtCenter, double boardWindowZ) {
        if (!isFinite(depthAtCenter) || !isFinite(boardWindowZ)) {
            return false;                       // 「不知道」不算遮挡（负对照 O4/O5）
        }
        return depthAtCenter < boardWindowZ;    // 🔴 严格小于：相等不算（边界 O1）
    }

    /** 深度值文本（6 位小数；非有限值 ⇒ {@code na}，**不许**写 0）。 */
    public static String depthText(double depth) {
        return isFinite(depth) ? String.format(Locale.ROOT, "%.6f", depth) : NA;
    }

    /** NDC z 文本（6 位小数；非有限值 ⇒ {@code na}）。 */
    public static String ndcText(double ndcZ) {
        return isFinite(ndcZ) ? String.format(Locale.ROOT, "%.6f", ndcZ) : NA;
    }

    /** 判定结论文本：{@code 1}/{@code 0}；任一侧 {@code na} ⇒ {@code na}（不把"不知道"写成 0）。 */
    public static String occludedText(double depthAtCenter, double boardNdcZ) {
        if (!isFinite(depthAtCenter) || !isFinite(boardNdcZ)) {
            return NA;
        }
        return occluded(depthAtCenter, boardNdcZ) ? "1" : "0";
    }

    /**
     * visibility 行<b>行末</b>那四个字段（顺序固定，全部走本类 ⇒ 离线验的就是线上打的）。
     *
     * @param depthText    {@link #depthText} 的结果（或直接 {@link #NA}）
     * @param boardNdcText {@link #ndcText} 的结果（或 {@link #NA}）
     * @param occludedText 判定结论文本（或 {@link #NA}）
     */
    public static String probeSuffix(boolean ontopOn, String depthText, String boardNdcText,
                                     String occludedText) {
        return "ontop=" + (ontopOn ? "1" : "0")
                + " depth_at_center=" + (depthText == null ? NA : depthText)
                + " board_ndc_z=" + (boardNdcText == null ? NA : boardNdcText)
                + " depth_occluded=" + (occludedText == null ? NA : occludedText);
    }

    /** 事件行：{@code holo_render_state depth_test=<0|1> -> 0 ontop=true boards=<n>}。 */
    public static String renderStateLine(boolean origDepthTest, int boards) {
        return RENDER_STATE_PREFIX
                + " depth_test=" + (origDepthTest ? "1" : "0")
                + " -> 0 ontop=true boards=" + Math.max(0, boards);
    }

    /** 非有限值判定（{@code NaN}、{@code ±Infinity} 一律算"不知道"）。 */
    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    // ==================================================================== 三、离线自证

    /** 自检读数（{@code pass=/fail=/bad}）。与 {@link ShanhaiHoloOccluderProbe.Report} 同口径同字段。 */
    public record Report(int pass, int fail, String bad) {
        public boolean ok() {
            return fail == 0;
        }

        /** {@code pass=<n> fail=<m> exit=<0|1>}（三个字段固定，机器可判）。 */
        public String line() {
            return "pass=" + pass + " fail=" + fail + " exit=" + exitCode();
        }

        public int exitCode() {
            return fail == 0 ? 0 : 1;
        }
    }

    private static final class Acc {
        int pass;
        int fail;
        final StringBuilder bad = new StringBuilder();

        void eq(String what, Object actual, Object expected) {
            if (actual == null ? expected == null : actual.equals(expected)) {
                pass++;
                return;
            }
            fail++;
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("，应当 ").append(expected).append(';');
        }

        void eqBool(String what, boolean actual, boolean expected) {
            eq(what, actual, expected);
        }

        void ok(String what, boolean actual) {
            if (actual) {
                pass++;
                return;
            }
            fail++;
            bad.append(' ').append(what).append("：实际 false，应当 true;");
        }

        Report toReport() {
            return new Report(pass, fail, bad.toString());
        }
    }

    /**
     * 离线自检（<b>不碰 MC / 不碰 GL</b>）：{@code fail == 0} 即全过。
     *
     * <pre>
     *   S1  默认值 = on（DEFAULT_ON）
     *   S2  off 之后再来一次 off ⇒ 还是 false（"关掉后不再改状态"）
     *   S3  off 之后再来一个非法参数 ⇒ 还是 false（负对照：不许被任何输入意外打开）
     *   S4  非法参数在 on 时也不改状态（还是 true）
     *   S5  on → off（显式）
     *   S6  off → on（显式）
     *   S7  无参 ⇒ 翻转（true→false / false→true）
     *   S8  "true"/"1"/"false"/"0" 四个别名
     *   W1  windowZ(-1)=0 / W2 windowZ(0)=0.5 / W3 windowZ(1)=1
     *   W4  windowZ(NaN)=NaN
     *   O1  深度<b>恰好相等</b> ⇒ 不算遮挡（边界，严格小于；用 windowZ(ndc) 造真相等）
     *   O1b ndc=0 ⇒ 窗口恰好 0.5，depth=0.5 也是相等
     *   O2  地形<b>近一个 ULP</b> ⇒ 遮挡
     *   O3  地形<b>远一个 ULP</b> ⇒ 不遮挡
     *   O4  depth_at_center=na ⇒ 不算遮挡（负对照）
     *   O5  board_ndc_z=na ⇒ 不算遮挡
     *   O6  天空（depth=1.0）对板心 ndc=0.99 ⇒ 不遮挡
     *   O7  depth=0 对板心 ndc=-1.0（窗口 0）⇒ 相等不算遮挡
     *   O8  depth=0.2 对板心 ndc=1.0（窗口 1）⇒ 遮挡
     *   O9  ±Infinity 也按 na 处理（不遮挡）
     *   T1–T3 depthText 的 na / 6 位小数 / 1.000000
     *   T4–T5 ndcText 的 na / 负数 6 位小数
     *   T6–T7 occludedText 的 1 / na
     *   L1–L2 switchLine 两个形态
     *   L3–L4 renderStateLine 两个形态（含 depth_test=0 的负对照）
     *   L5–L6 probeSuffix 两个形态（有读数 / 全 na）
     * </pre>
     */
    public static Report selfTest() {
        final Acc a = new Acc();

        // ---- 开关状态机 ----
        a.eqBool("S1 默认值 = on", initial(), true);
        a.eqBool("S2 off 之后 off 仍是 off", resolve(false, "off"), false);
        a.eqBool("S3 off 之后非法参数仍是 off", resolve(false, "wat"), false);
        a.eqBool("S4 on 时非法参数不改状态", resolve(true, "wat"), true);
        a.eqBool("S5 on → off", resolve(true, "off"), false);
        a.eqBool("S6 off → on", resolve(false, "on"), true);
        a.eqBool("S7a 无参翻转 true→false", resolve(true, null), false);
        a.eqBool("S7b 无参翻转 false→true", resolve(false, null), true);
        a.eqBool("S8a \"true\" ⇒ true", resolve(false, "true"), true);
        a.eqBool("S8b \"1\" ⇒ true", resolve(false, "1"), true);
        a.eqBool("S8c \"false\" ⇒ false", resolve(true, "false"), false);
        a.eqBool("S8d \"0\" ⇒ false", resolve(true, "0"), false);
        a.eqBool("S8e 空串按无参翻转", resolve(true, "  "), false);

        // ---- 单位换算 ----
        a.eq("W1 windowZ(-1)=0", windowZ(-1.0), 0.0);
        a.eq("W2 windowZ(0)=0.5", windowZ(0.0), 0.5);
        a.eq("W3 windowZ(1)=1", windowZ(1.0), 1.0);
        a.ok("W4 windowZ(NaN) 仍是 NaN", Double.isNaN(windowZ(Double.NaN)));

        // ---- 遮挡判定（板心 ndc=-0.2 ⇒ 窗口 ≈ 0.4）----
        final double ndc = -0.2;
        final double win = windowZ(ndc);
        a.eqBool("O1 深度<b>恰好相等</b>不算遮挡（边界，严格小于）", occluded(win, ndc), false);
        a.eqBool("O1b ndc=0 ⇒ 窗口恰好 0.5，depth=0.5 也算相等", occluded(0.5, 0.0), false);
        a.eqBool("O2 地形更近一个 ULP ⇒ 遮挡", occluded(Math.nextDown(win), ndc), true);
        a.eqBool("O3 地形更远一个 ULP ⇒ 不遮挡", occluded(Math.nextUp(win), ndc), false);
        a.eqBool("O4 depth=na ⇒ 不算遮挡（负对照）", occluded(Double.NaN, ndc), false);
        a.eqBool("O5 board_ndc_z=na ⇒ 不算遮挡", occluded(0.4, Double.NaN), false);
        a.eqBool("O6 天空 1.0 对板心 0.99 ⇒ 不遮挡", occluded(1.0, 0.99), false);
        a.eqBool("O7 depth=0 对板心 ndc=-1（窗口 0）⇒ 相等不算", occluded(0.0, -1.0), false);
        a.eqBool("O8 depth=0.2 对板心 ndc=1（窗口 1）⇒ 遮挡", occluded(0.2, 1.0), true);
        a.eqBool("O9a depth=+Inf ⇒ 不遮挡", occluded(Double.POSITIVE_INFINITY, ndc), false);
        a.eqBool("O9b board_ndc_z=-Inf ⇒ 不遮挡", occluded(0.1, Double.NEGATIVE_INFINITY), false);
        a.eqBool("O10 occludedWindow 与 occluded 同口径", occludedWindow(0.4, 0.4), false);

        // ---- 文本形态 ----
        a.eq("T1 depthText(na)", depthText(Double.NaN), NA);
        a.eq("T2 depthText(0.5)", depthText(0.5), "0.500000");
        a.eq("T3 depthText(1.0)", depthText(1.0), "1.000000");
        a.eq("T4 ndcText(na)", ndcText(Double.NaN), NA);
        a.eq("T5 ndcText(-0.02)", ndcText(-0.02), "-0.020000");
        a.eq("T6 occludedText(0.2, ndc=1.0)", occludedText(0.2, 1.0), "1");
        a.eq("T7 occludedText(na,0)", occludedText(Double.NaN, 0.0), NA);

        // ---- 日志行 ----
        a.eq("L1 switchLine(true)", switchLine(true), "holo_ontop on=true");
        a.eq("L2 switchLine(false)", switchLine(false), "holo_ontop on=false");
        a.eq("L3 renderStateLine(1,5)", renderStateLine(true, 5),
                "holo_render_state depth_test=1 -> 0 ontop=true boards=5");
        a.eq("L4 renderStateLine(0,0)（负对照：原值就是 0）", renderStateLine(false, 0),
                "holo_render_state depth_test=0 -> 0 ontop=true boards=0");
        a.eq("L5 probeSuffix 有读数", probeSuffix(true, "0.123456", "-0.020000", "1"),
                "ontop=1 depth_at_center=0.123456 board_ndc_z=-0.020000 depth_occluded=1");
        a.eq("L6 probeSuffix 全 na", probeSuffix(false, NA, NA, NA),
                "ontop=0 depth_at_center=na board_ndc_z=na depth_occluded=na");
        a.eq("L7 probeSuffix 空串按 na", probeSuffix(true, null, null, null),
                "ontop=1 depth_at_center=na board_ndc_z=na depth_occluded=na");

        return a.toReport();
    }

    /** 一行自检读数（进日志；{@code pass=/fail=/exit=} 三个字段固定）。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return "holo_ontop_selftest " + r.line() + (r.ok() ? "" : " FAILED:" + r.bad());
    }

    /**
     * 离线入口：{@code java -cp <只有本类所在目录> com.shanhai.client.holo.ShanhaiHoloOntop}
     * <p>输出一行 {@code holo_ontop_selftest pass=… fail=… exit=…}，退出码同 {@code exit=}。
     * <p>⚠️ 本类<b>不依赖 Minecraft / 不依赖 GL</b> ⇒ 这条自检不需要构建 mod、不需要开游戏。
     */
    public static void main(String[] args) {
        final Report r = selfTest();
        System.out.println("holo_ontop_selftest " + r.line());
        if (!r.ok()) {
            System.out.println("FAILED:" + r.bad());
        }
        System.exit(r.exitCode());
    }
}
