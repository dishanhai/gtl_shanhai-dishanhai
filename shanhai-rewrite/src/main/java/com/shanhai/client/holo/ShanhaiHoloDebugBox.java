package com.shanhai.client.holo;

import java.util.Locale;

/**
 * 山海重构 · 悬浮全息「看不见」实验的<b>三档（＋第四档）调试色块</b>纯逻辑核。
 *
 * <h2>0. 它要分清的三种病因（用户 2026-11-08 现场）</h2>
 * <pre>
 *   visibility menu_on=true env_near=true boards_in=5/5 in_frustum=true screen=(350,213)
 *     local_drawn=true drawn_boards=5 quads=270 verts=1080 alpha=1.000 scale=1.000 visible=1
 *   ⇒ 我们【声称提交了 270 个四边形】，而用户屏幕上什么都没有（ontop 开/关都一样）。
 * </pre>
 * 三种可能，而旧日志一条都分不开：
 * <ol>
 *   <li><b>渲染通路坏</b>：顶点进了 builder，但根本没栅格化（着色器/投影/批次/GL 状态）；</li>
 *   <li><b>板子自身变换坏</b>：矩阵里 NaN / 缩放为 0 ⇒ 几何退化成一个点；</li>
 *   <li><b>被深度挡住</b>：新读数 {@code depth_occluded=1 ×18（depth_at_center=0.000000）}，
 *       而 {@code depth_at_center=na ×55} 又说明"深度可能根本读不到"。</li>
 * </ol>
 *
 * <h2>1. 四档各画什么（同一帧里同时画；颜色/位置都不同 ⇒ 各自独立可辨）</h2>
 * <table border="1">
 *   <tr><th>档</th><th>坐标怎么来</th><th>画什么</th><th>深度测试</th><th>它能判死什么</th></tr>
 *   <tr><td><b>A</b></td><td><b>纯相机空间</b>（顶点就写 {@code (0,0,-3)}，只过相机姿态矩阵）</td>
 *       <td>1×1 格不透明<b>红</b>方块，屏正中心 3 格前</td><td><b>关</b></td>
 *       <td>A 看不见 ⇒ <b>渲染通路坏</b>（与板子几何无关）</td></tr>
 *   <tr><td><b>B</b></td><td>同一个点，但先算成<b>世界坐标</b>
 *       （{@code cam + right·(−1.25) + look·3}），再过 {@link ShanhaiHoloSurroundView#composeView}</td>
 *       <td>同尺寸不透明<b>品红</b>方块，A 左边 1.25 格（同高同距）</td><td><b>关</b></td>
 *       <td>A 有、B 没有 ⇒ <b>我们的相机变换</b>（composeView 那条）送错了位置</td></tr>
 *   <tr><td><b>C</b></td><td>{@code applyHoloFrame ＋ poseBoard(boards[0])} —— 板子的<b>完整</b>位姿链，一个数都不换</td>
 *       <td>整块板面 560×62 bu 换成不透明<b>纯绿</b>（颜色/贴图/alpha 全不参与）</td><td><b>关</b></td>
 *       <td>A、B 有、C 没有 ⇒ <b>板子变换坏</b>（NaN / 缩放 0），配合日志的 {@code board_nan=} /
 *           {@code board0_width=} 一眼定案</td></tr>
 *   <tr><td><b>D</b></td><td>与 A <b>同一个位置</b>、同一条管线，<b>只把深度测试开着</b></td>
 *       <td>两个 0.6×0.6 不透明<b>黄</b>小方块：D0 在 A 正中间、D1 在 A 正上方 1.6 格</td><td><b>开</b></td>
 *       <td>A 看得见 + D 看不见 ⇒ <b>确认是深度遮挡</b>（那个 0 深度真的挡在前面）；
 *           D0 没有而 D1 有 ⇒ 挡住的是<b>我们自己的板面</b>（ontop=off 时板会写深度）</td></tr>
 * </table>
 * ⚠️ A/B/C 必须<b>先关深度测试再画</b>，否则 A 也会被同一个"贴脸 0 深度"挡住，
 * 实验就分不清"通路坏"还是"被挡"——D 就是专门留出来当这条对照的。
 * <p>⚠️ B 在 A 左边 1.25 格、D 在 A 正中间与其正上方 1.6 格：三处都<b>不是随手挪</b>——
 * 位置完全重合时后画的会盖住先画的，判据会退化成"看见一块颜色"（分不清是谁画的）。
 *
 * <h2>2. 为什么要一个纯类（而不是把判定写进渲染器）</h2>
 * "四档 submitted/skipped 的决策"和"pose 里有没有 NaN"这两件事<b>都是纯布尔运算</b>：
 * 抽出来之后，{@link #selfTest()} 能在<b>没有游戏、没有 GL</b> 的机器上逐条验（含 NaN 与非 NaN 两组矩阵），
 * 渲染器只按 {@link #plan} 的结论去画。<b>渲染器不自己判任何一个布尔</b> ——
 * 于是"判定写反了却全绿"这种错（本工程踩过：规则自洽地错，没有任何症状）不可能藏在渲染路径里。
 *
 * <h2>3. 日志（两行，字段名与顺序固定）</h2>
 * <pre>
 *   [SHANHAI-HOLO] debugbox frame=12 A=submitted B=submitted C=skipped D=submitted
 *       cam=(1.234,64.000,-8.765) look=(0.000,0.000,-1.000) pose_has_nan=false
 *       board0_world=(1.234,63.900,-5.965) board0_ndc=(0.0012,-0.0456)
 *       occluder_depth=0.000000 depth_fail=read_fbo_not_main depth_early=na
 *       board0_width=2.221 board_nan=false depth_a=0 depth_d=1 restore_ok=true skip=C:no_board
 *   [SHANHAI-HOLO] depth_read_fail reason=pbo_bound at=early pixel=(960,540) read_fbo=7 main_fbo=12
 *       pack_buf=3 fbo_status=0x8CD5 depth_attach=yes gl_err=0x502 value=na
 * </pre>
 * 换行只为读起来方便，<b>真的一行</b>。
 * <ul>
 *   <li>{@code depth_a=} / {@code depth_d=} / {@code restore_ok=} 是<b>上一帧</b>画的时候回读的
 *       GL <b>真值</b>（滞后 1 帧 ≈16 ms；与 {@code occl_probe} 的 {@code drawn_boards} 同一个口径）。
 *       为什么不打"这一帧"的：任务书要求<b>画之前</b>就把读数打出来（一个像素都没画出来日志也得有），
 *       而这三项只有在画的时候才存在 ⇒ 只能取上一帧。</li>
 *   <li>{@code depth_fail=} 是<b>板心那一个像素</b>深度读数的失败原因（{@link #DEPTH_OK} = 读成功）；
 *       这就是用户点名要的那条：{@code na} 背后到底是"GL 错误 / 非主 FBO / 无深度附件 / PBO 被绑"。</li>
 * </ul>
 *
 * <h2>4. 🔴 这条读数最贵的一条：{@code 0.000000} 可能不是"被挡"，而是"读失败"</h2>
 * 旧实现里 {@code glReadPixels} 之后没有查 {@code glGetError}、也没有查"读的是不是主帧缓冲"：
 * 读数落在<b>类加载时分配的那一个 FloatBuffer</b> 上，读失败时它<b>一个字节都不会被写</b>
 * ⇒ 取出来的就是上一次的旧值（或初值 <b>0.0f</b>）⇒ 日志里是<b>一个看着很真的 0.000000</b>。
 * 于是 18 帧 {@code depth_at_center=0.000000} 有两种完全相反的读法，而旧日志分不开：
 * ①真的有个东西贴在你脸前；②读失败 + 初值 0。<b>本类把 ② 变成一个有名字的 reason。</b>
 * 判定口径见 {@link #depthFailReason}：任一条不成立 ⇒ 那个数<b>不许</b>当成读数用（一律 {@code na}）。
 *
 * <p>⚠️ 本类<b>零依赖</b>：只有 {@code java.util.Locale}（零 Minecraft / 零 GL / 零 JOML）。
 * 离线自证 = 直接用 javac 编这一个文件再跑 {@link #main}
 * （{@code temp/holo-debug/selfcheck/run.ps1}，含正对照 + 两个负对照）。
 */
public final class ShanhaiHoloDebugBox {

    private ShanhaiHoloDebugBox() {}

    // ==================================================================== 一、常量（下游按这些字面量判读）

    /** 主日志行行首（固定：{@code debugbox}）。 */
    public static final String LOG_PREFIX = "debugbox";

    /** 命令那一行的行首（固定：{@code holo_debugbox}）。 */
    public static final String SWITCH_PREFIX = "holo_debugbox";

    /** 命令注册回读那一行的行首（固定：{@code holo_debugbox_registered}）。 */
    public static final String REGISTERED_PREFIX = "holo_debugbox_registered";

    /** 深度读失败那一行的行首（固定：{@code depth_read_fail}）。 */
    public static final String DEPTH_FAIL_PREFIX = "depth_read_fail";

    /** 深度读<b>恢复</b>那一行的行首（固定：{@code depth_read_ok}）。 */
    public static final String DEPTH_OK_PREFIX = "depth_read_ok";

    /** 自检读数行首（固定：{@code holo_debugbox_selftest}）。 */
    public static final String SELFTEST_PREFIX = "holo_debugbox_selftest";

    /**
     * 开关的<b>默认值</b> = <b>off</b>（用户点名："默认 off，运行时开关不落配置"）。
     * <p>改这一个常量就等于改"所有人第一次进世界的状态"（本项目里只有这一处定义）。
     */
    public static final boolean DEFAULT_ON = false;

    /** 三档各自的结论文本（只有这两个值；机器可判）。 */
    public static final String SUBMITTED = "submitted";
    public static final String SKIPPED = "skipped";

    /** 「读不到 / 不适用」的固定文本。<b>不许</b>用 0 冒充。 */
    public static final String NA = "na";

    /** 没有（没有跳过原因 / 深度读成功）。 */
    public static final String NONE = "none";

    // ---- 跳过原因（{@link #plan} 的唯一出口；日志里拼成 skip=C:no_board 这种）----

    /** 菜单没开（{@code menu_on=false}）：四档一律不画（用户硬要求：只在菜单开着时画）。 */
    public static final String REASON_MENU_OFF = "menu_off";
    /** 板表是空的（面板/菜单都可能，理论上不该发生）。 */
    public static final String REASON_NO_BOARD = "no_board";
    /** 相机姿态那 16 个元素里有 NaN/Inf。 */
    public static final String REASON_POSE_NAN = "pose_nan";
    /** composeView 之后的那 16 个元素里有 NaN/Inf。 */
    public static final String REASON_VIEW_NAN = "view_nan";
    /** 板子最终位姿矩阵那 16 个元素里有 NaN/Inf。 */
    public static final String REASON_BOARD_NAN = "board_nan";
    /** 这一档在画的时候抛了异常（只 WARN 一次，不影响主流程）。 */
    public static final String REASON_EXCEPTION = "exception";
    /** 没有任何失败原因（= 这一档确实 submit 了）。 */
    public static final String REASON_OK = "ok";

    // ---- 深度读失败原因（{@link #depthFailReason} 的唯一出口）----

    /** 读成功（唯一"这个数可以当读数用"的值）。 */
    public static final String DEPTH_OK = NONE;
    /** {@code GL_PIXEL_PACK_BUFFER_BINDING != 0}：像素被 GL 写进 PBO，我们那个 FloatBuffer 一个字节都没动。 */
    public static final String DEPTH_FAIL_PBO = "pbo_bound";
    /** 绑着的读帧缓冲<b>不是</b>主帧缓冲（Iris/Oculus 一类着色器模组会把世界画进自己的 FBO）。 */
    public static final String DEPTH_FAIL_READ_FBO = "read_fbo_not_main";
    /** {@code glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE}。 */
    public static final String DEPTH_FAIL_FBO_STATUS = "fbo_incomplete";
    /** 那个帧缓冲根本没有深度附件（那就没有任何深度可读）。 */
    public static final String DEPTH_FAIL_NO_ATTACH = "no_depth_attachment";
    /** {@code glGetError()} 非 0（或读取过程抛异常）：{@code glReadPixels} 被 GL 拒了。 */
    public static final String DEPTH_FAIL_GL_ERROR = "gl_error";
    /** 读回来的值非有限 / 不在 [0,1]：缓冲里是垃圾，不是一个深度。 */
    public static final String DEPTH_FAIL_RANGE = "out_of_range";
    /** 连"读的是哪个 FBO"都没问到（查询本身失败）。 */
    public static final String DEPTH_FAIL_UNKNOWN = "unknown";

    /** {@code GL_FRAMEBUFFER_COMPLETE}（GL 3.0 的固定值 0x8CD5；本类不 import LWJGL ⇒ 直接写死并自检）。 */
    public static final int FBO_COMPLETE = 0x8CD5;

    /** {@code hasDepth} 的三态：不知道。</summary> */
    public static final int DEPTH_ATTACH_UNKNOWN = -1;
    /** {@code hasDepth} 的三态：没有深度附件。 */
    public static final int DEPTH_ATTACH_NO = 0;
    /** {@code hasDepth} 的三态：有深度附件。 */
    public static final int DEPTH_ATTACH_YES = 1;

    // ==================================================================== 二、开关状态机

    /** 初始值 = {@link #DEFAULT_ON}（渲染器在类加载时取它，不自己再写一个 false）。 */
    public static boolean initial() {
        return DEFAULT_ON;
    }

    /**
     * <b>唯一</b>的开关状态机（语义与 {@link ShanhaiHoloOntop#resolve} 逐字相同，只是默认值相反）。
     * <pre>
     *   arg == null / 空串  ⇒ 翻转
     *   "on"/"true"/"1"     ⇒ true
     *   "off"/"false"/"0"   ⇒ false
     *   其它                ⇒ 原样返回 current（不改状态、不抛）
     * </pre>
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

    /** 命令那一行：{@code holo_debugbox on=true} / {@code holo_debugbox on=false}。 */
    public static String switchLine(boolean on) {
        return SWITCH_PREFIX + " on=" + boolText(on);
    }

    /** 注册回读那一行：{@code holo_debugbox_registered present=true path=/shanhai_add/holo/debugbox}。 */
    public static String registeredLine(boolean present, String path) {
        return REGISTERED_PREFIX + " present=" + boolText(present)
                + " path=" + (path == null || path.isEmpty() ? NA : path);
    }

    // ==================================================================== 三、四档的 submitted/skipped 决策

    /**
     * 四档的入场条件（渲染器采样完原始事实后交进来；本类只做布尔运算，不采任何数）。
     *
     * @param menuOn      菜单是否开着（{@code ShanhaiHoloMenuState.enabled()} = 日志里的 {@code menu_on}）
     * @param hasBoard    板表非空（菜单 5 块 / 面板若干行）
     * @param poseFinite  相机姿态矩阵 16 个元素全有限
     * @param viewFinite  {@code composeView(pose, cam)} 的结果 16 个元素全有限
     * @param boardFinite 板子最终矩阵（{@code applyHoloFrame→poseBoard}）16 个元素全有限
     */
    public record Gates(boolean menuOn, boolean hasBoard,
                        boolean poseFinite, boolean viewFinite, boolean boardFinite) {
    }

    /** 一档的结论：{@code name} 是 A/B/C/D，{@code submitted} 是画不画，{@code reason} 是为什么。 */
    public record Tier(String name, boolean submitted, String reason) {

        /** {@code submitted} / {@code skipped}（日志里那两个固定词）。 */
        public String status() {
            return submitted ? SUBMITTED : SKIPPED;
        }
    }

    /** 四档的完整计划（日志 {@code A=… B=… C=… D=…} 的唯一来源）。 */
    public record Plan(Tier a, Tier b, Tier c, Tier d) {

        /** {@code A=submitted B=skipped C=submitted D=submitted}。 */
        public String text() {
            return "A=" + a.status() + " B=" + b.status()
                    + " C=" + c.status() + " D=" + d.status();
        }

        /** 只有"没画"的那些档才列原因：{@code C:no_board,B:view_nan}；全画了 ⇒ {@code none}。 */
        public String skipText() {
            final StringBuilder sb = new StringBuilder(48);
            for (Tier t : new Tier[]{a, b, c, d}) {
                if (t.submitted()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(t.name()).append(':').append(t.reason());
            }
            return sb.length() == 0 ? NONE : sb.toString();
        }
    }

    /**
     * 🔴 <b>四档决策的唯一落点</b>。
     *
     * <pre>
     *   A：menuOn &amp;&amp; poseFinite                        （纯相机空间，只用相机姿态矩阵）
     *   B：menuOn &amp;&amp; poseFinite &amp;&amp; viewFinite        （还要 composeView 那一步不出 NaN）
     *   C：menuOn &amp;&amp; hasBoard &amp;&amp; poseFinite &amp;&amp; boardFinite
     *   D：与 A 完全相同（同一条管线，只是深度测试开着 —— 差异只在 GL 状态，不在决策）
     * </pre>
     * 原因优先级（每档各自独立判，先命中先定）：{@code menu_off} → {@code no_board} →
     * {@code pose_nan} → {@code view_nan} → {@code board_nan} → {@code ok}。
     */
    public static Plan plan(Gates g) {
        return new Plan(tier("A", g, false, false),
                tier("B", g, false, true),
                tier("C", g, true, false),
                tier("D", g, false, false));
    }

    /**
     * 单档决策（{@link #plan} 的内部式子；<b>唯一</b>一份，四个档调同一个）。
     *
     * @param needsBoard true = 这一档要板子（只有 C：既要"有板"，也要"板的矩阵有限"）
     * @param needsView  true = 这一档要 composeView 的结果有限（只有 B）
     */
    private static Tier tier(String name, Gates g, boolean needsBoard, boolean needsView) {
        if (!g.menuOn()) {
            return new Tier(name, false, REASON_MENU_OFF);
        }
        if (needsBoard && !g.hasBoard()) {
            return new Tier(name, false, REASON_NO_BOARD);
        }
        if (!g.poseFinite()) {
            return new Tier(name, false, REASON_POSE_NAN);
        }
        if (needsView && !g.viewFinite()) {
            return new Tier(name, false, REASON_VIEW_NAN);
        }
        if (needsBoard && !g.boardFinite()) {
            return new Tier(name, false, REASON_BOARD_NAN);
        }
        return new Tier(name, true, REASON_OK);
    }

    // ==================================================================== 四、NaN / 有限性

    /** 16 个元素里有没有 {@code NaN/±Inf}（空数组/长度不足 ⇒ 当成"有"：宁可判坏也不判好）。 */
    public static boolean hasNonFinite(float[] m16) {
        if (m16 == null || m16.length < 16) {
            return true;
        }
        for (int i = 0; i < 16; i++) {
            if (!isFinite(m16[i])) {
                return true;
            }
        }
        return false;
    }

    /** 三元组里有没有 {@code NaN/±Inf}（相机位置 / 视线方向用）。 */
    public static boolean anyNonFinite(double a, double b, double c) {
        return !isFinite(a) || !isFinite(b) || !isFinite(c);
    }

    /** {@code true}/{@code false}（日志里固定小写，机器可判；不用 {@code Boolean.toString} 的隐形依赖）。 */
    public static String boolText(boolean v) {
        return v ? "true" : "false";
    }

    // ==================================================================== 五、深度读数的判定（唯一落点）

    /** 一次 {@code glReadPixels} 的全部原始事实（采样在渲染器里做，本记录只是搬运）。 */
    public record DepthSample(double value, String reason,
                              int readFbo, int mainFbo, int packBuf,
                              int fboStatus, int hasDepth, int glError,
                              int px, int py) {
    }

    /**
     * 🔴 <b>这一读到底成没成</b>（顺序即优先级，先命中先定）。
     *
     * <table border="1">
     *   <tr><th>#</th><th>条件</th><th>reason</th><th>为什么它先</th></tr>
     *   <tr><td>①</td><td>{@code packBuf > 0}</td><td>{@link #DEPTH_FAIL_PBO}</td>
     *       <td>PBO 一绑上，{@code glReadPixels} 就写进那块缓冲，我们那个 FloatBuffer <b>一个字节都不动</b>
     *           ⇒ 读出来的数是旧的/初值 0 ⇒ 这是"看着像 0.000000"的头号元凶</td></tr>
     *   <tr><td>②</td><td>{@code readFbo != mainFbo}（任一未知也算）</td><td>{@link #DEPTH_FAIL_READ_FBO}</td>
     *       <td>读的不是主帧缓冲 ⇒ 读到的深度不是"玩家看到的那一层"</td></tr>
     *   <tr><td>③</td><td>{@code fboStatus != FBO_COMPLETE}</td><td>{@link #DEPTH_FAIL_FBO_STATUS}</td>
     *       <td>FBO 不完整 ⇒ 那个附件根本不可读</td></tr>
     *   <tr><td>④</td><td>{@code hasDepth == NO}</td><td>{@link #DEPTH_FAIL_NO_ATTACH}</td>
     *       <td>没有深度附件 ⇒ 没有深度可读（未知不算）</td></tr>
     *   <tr><td>⑤</td><td>{@code glError != 0}</td><td>{@link #DEPTH_FAIL_GL_ERROR}</td>
     *       <td>GL 明确拒了这次读取（-1 = 读取本身抛异常，也归这里）</td></tr>
     *   <tr><td>⑥</td><td>值非有限 / 不在 [0,1]</td><td>{@link #DEPTH_FAIL_RANGE}</td>
     *       <td>缓冲里是垃圾，不是一个窗口深度</td></tr>
     *   <tr><td>⑦</td><td>以上都不成立</td><td>{@link #DEPTH_OK}</td><td>只有这一支的数可以当读数用</td></tr>
     * </table>
     * ⚠️ 值恰好 {@code 0.0} <b>不</b>算失败：0 = 近裁剪面，是一个<b>合法且有意义的读数</b>
     * （"有东西贴在你脸前"）。它是不是真的，要靠 ①–⑥ 这几条来保：<b>先证明读成功，再相信那个 0</b>。
     */
    public static String depthFailReason(int packBuf, int readFbo, int mainFbo, int fboStatus,
                                         int hasDepth, int glError, double value) {
        if (packBuf > 0) {
            return DEPTH_FAIL_PBO;
        }
        if (readFbo < 0 || mainFbo < 0) {
            return DEPTH_FAIL_UNKNOWN;
        }
        if (readFbo != mainFbo) {
            return DEPTH_FAIL_READ_FBO;
        }
        if (fboStatus >= 0 && fboStatus != FBO_COMPLETE) {
            return DEPTH_FAIL_FBO_STATUS;
        }
        if (hasDepth == DEPTH_ATTACH_NO) {
            return DEPTH_FAIL_NO_ATTACH;
        }
        if (glError != 0) {
            return DEPTH_FAIL_GL_ERROR;
        }
        if (!isFinite(value) || value < 0.0 || value > 1.0) {
            return DEPTH_FAIL_RANGE;
        }
        return DEPTH_OK;
    }

    /** 这一次读成功了吗（{@link #DEPTH_OK} 才算成功）。 */
    public static boolean depthReadOk(String reason) {
        return DEPTH_OK.equals(reason);
    }

    /** 三态附件文本：{@code yes}/{@code no}/{@code na}。 */
    public static String depthAttachText(int hasDepth) {
        if (hasDepth == DEPTH_ATTACH_YES) {
            return "yes";
        }
        return hasDepth == DEPTH_ATTACH_NO ? "no" : NA;
    }

    /** GL 错误码文本：{@code 0x0502} / {@code none}（0）/ {@code na}（负 = 没查到）。 */
    public static String glErrorText(int code) {
        if (code < 0) {
            return NA;
        }
        return code == 0 ? NONE : String.format(Locale.ROOT, "0x%04X", code);
    }

    /** 深度文本：<b>只有读成功</b>才给 6 位小数（否则 {@code na}）—— 见 {@link #depthFailReason}。 */
    public static String depthText(double value, String reason) {
        if (!depthReadOk(reason)) {
            return NA;
        }
        return f6(value);
    }

    /**
     * 深度读失败那一行（字段名与顺序固定；只写一次的原因见渲染器）。
     *
     * @param where {@code early}（本帧最早那次，屏幕正中心）/ {@code board}（板心那个像素）/ {@code debugbox}
     */
    public static String depthFailLine(DepthSample s, String where) {
        if (s == null) {
            return DEPTH_FAIL_PREFIX + " reason=" + DEPTH_FAIL_UNKNOWN + " at=" + nonEmpty(where)
                    + " pixel=" + NA + " read_fbo=" + NA + " main_fbo=" + NA + " pack_buf=" + NA
                    + " fbo_status=" + NA + " depth_attach=" + NA + " gl_err=" + NA + " value=" + NA;
        }
        return DEPTH_FAIL_PREFIX
                + " reason=" + nonEmpty(s.reason())
                + " at=" + nonEmpty(where)
                + " pixel=" + "(" + s.px() + "," + s.py() + ")"
                + " read_fbo=" + intText(s.readFbo())
                + " main_fbo=" + intText(s.mainFbo())
                + " pack_buf=" + intText(s.packBuf())
                + " fbo_status=" + (s.fboStatus() < 0 ? NA : String.format(Locale.ROOT, "0x%04X", s.fboStatus()))
                + " depth_attach=" + depthAttachText(s.hasDepth())
                + " gl_err=" + glErrorText(s.glError())
                + " value=" + (isFinite(s.value()) ? f6(s.value()) : NA);
    }

    /** 深度读恢复那一行：{@code depth_read_ok at=early value=0.987654 read_fbo=12}。 */
    public static String depthOkLine(DepthSample s, String where) {
        final String value = s == null ? NA : (isFinite(s.value()) ? f6(s.value()) : NA);
        final String readFbo = s == null ? NA : intText(s.readFbo());
        return DEPTH_OK_PREFIX + " at=" + nonEmpty(where) + " value=" + value + " read_fbo=" + readFbo;
    }

    // ==================================================================== 六、那一行日志（唯一拼装点）

    /**
     * 主日志行所需的<b>全部读数</b>（字段顺序 <b>=</b> 日志顺序；采样全在渲染器里做）。
     *
     * @param frame        开这个开关以来的第几帧（{@code 1} 起）
     * @param plan         四档决策（{@link #plan}）
     * @param camXYZ       相机世界坐标
     * @param lookXYZ      视线方向（单位向量，世界空间）
     * @param poseHasNan   相机姿态 <b>或</b> 板子位姿的 16 个元素里有 NaN/Inf（{@code board_nan} 另给）
     * @param boardNaN     板子最终矩阵 16 个元素里有 NaN/Inf
     * @param board0XYZ    第一块板的<b>世界</b>坐标（板心，不是 NDC）
     * @param board0NdcX/Y 现有投影结果（NDC；不可投影 ⇒ NaN ⇒ 打 {@code na}）
     * @param occluderDepth 板心那一个像素的窗口深度（读失败 ⇒ 打 {@code na}）
     * @param depthFail    {@link #occluderDepth} 那次读的 {@link #depthFailReason}
     * @param earlyDepth   本帧<b>最早</b>那次读（屏幕正中心）的窗口深度（失败 ⇒ {@code na}）
     * @param board0Width  第一块板在世界上有多宽（格；{@code 2.2} 左右 = 正常，{@code 0.000} = 缩放坏了）
     * @param depthA       上一帧 A 档画的时候 {@code glIsEnabled(GL_DEPTH_TEST)} 的真值（0/1）
     * @param depthD       上一帧 D 档画的时候的真值（0/1）
     * @param restoreOk    上一帧画完 GL 状态有没有原样还回去
     */
    public record Sample(long frame, Plan plan,
                         double camX, double camY, double camZ,
                         double lookX, double lookY, double lookZ,
                         boolean poseHasNan, boolean boardNaN,
                         double board0X, double board0Y, double board0Z,
                         float board0NdcX, float board0NdcY,
                         double occluderDepth, String depthFail,
                         double earlyDepth,
                         double board0Width,
                         int depthA, int depthD, boolean restoreOk) {
    }

    /** 把一份 {@link Sample} 拼成那<b>一行</b>（字段名与顺序固定，见类注释 §3）。 */
    public static String line(Sample s) {
        final StringBuilder sb = new StringBuilder(384);
        sb.append(LOG_PREFIX)
                .append(" frame=").append(Math.max(0L, s.frame()))
                .append(' ').append(s.plan().text())
                .append(" cam=").append(vec3(s.camX(), s.camY(), s.camZ()))
                .append(" look=").append(vec3(s.lookX(), s.lookY(), s.lookZ()))
                .append(" pose_has_nan=").append(boolText(s.poseHasNan()))
                .append(" board0_world=").append(vec3(s.board0X(), s.board0Y(), s.board0Z()))
                .append(" board0_ndc=").append(ndc2(s.board0NdcX(), s.board0NdcY()))
                .append(" occluder_depth=").append(depthText(s.occluderDepth(), s.depthFail()))
                .append(" depth_fail=").append(nonEmpty(s.depthFail()))
                .append(" depth_early=").append(f6(s.earlyDepth()))
                .append(" board0_width=").append(f3(s.board0Width()))
                .append(" board_nan=").append(boolText(s.boardNaN()))
                .append(" depth_a=").append(s.depthA() < 0 ? NA : Integer.toString(s.depthA()))
                .append(" depth_d=").append(s.depthD() < 0 ? NA : Integer.toString(s.depthD()))
                .append(" restore_ok=").append(boolText(s.restoreOk()))
                .append(" skip=").append(s.plan().skipText());
        return sb.toString();
    }

    /** 主日志行里字段出现的顺序（离线自检逐项断言 {@code indexOf} 递增：字段没漂）。 */
    public static final String[] FIELD_ORDER = {
            "debugbox frame=", " cam=", " look=", " pose_has_nan=", " board0_world=", " board0_ndc=",
            " occluder_depth=", " depth_fail=", " depth_early=", " board0_width=", " board_nan=",
            " depth_a=", " depth_d=", " restore_ok=", " skip="};

    // ==================================================================== 七、文本小工具

    /** {@code (1.234,5.678,9.012)}；分量非有限 ⇒ 那一格写 {@code nan}（整条不变成 na：另外两格还有信息）。 */
    public static String vec3(double x, double y, double z) {
        return "(" + f3(x) + "," + f3(y) + "," + f3(z) + ")";
    }

    /** {@code (0.0012,-0.0456)}；任一分量非有限 ⇒ {@code na}（两个数要一起读才有意义）。 */
    public static String ndc2(float x, float y) {
        return (isFinite(x) && isFinite(y)) ? "(" + f4(x) + "," + f4(y) + ")" : NA;
    }

    /** 3 位小数（非有限 ⇒ {@code nan}）。 */
    public static String f3(double v) {
        return isFinite(v) ? String.format(Locale.ROOT, "%.3f", v) : "nan";
    }

    /** 4 位小数（非有限 ⇒ {@code nan}）。 */
    public static String f4(double v) {
        return isFinite(v) ? String.format(Locale.ROOT, "%.4f", v) : "nan";
    }

    /** 6 位小数（非有限 ⇒ {@code na}；深度一律走这个口径）。 */
    public static String f6(double v) {
        return isFinite(v) ? String.format(Locale.ROOT, "%.6f", v) : NA;
    }

    private static String intText(int v) {
        return v < 0 ? NA : Integer.toString(v);
    }

    private static String nonEmpty(String s) {
        return (s == null || s.isEmpty()) ? "none" : s;
    }

    /** 非有限值判定（{@code NaN}、{@code ±Infinity} 一律算"不知道"）。 */
    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    // ==================================================================== 八、离线自证

    /** 自检读数（{@code pass=/fail=/exit=}，与 {@link ShanhaiHoloOntop.Report} 同口径）。 */
    public record Report(int pass, int fail, String bad) {

        /** 全过？ */
        public boolean ok() {
            return fail == 0;
        }

        /** 一行读数（三个字段固定，机器可判）。 */
        public String line() {
            return "pass=" + pass + " fail=" + fail + " exit=" + exitCode();
        }

        /** 进程退出码：全过 0，有失败 1。 */
        public int exitCode() {
            return fail == 0 ? 0 : 1;
        }
    }

    private static final class Acc {
        private int pass;
        private int fail;
        private final StringBuilder bad = new StringBuilder(256);

        void eq(String what, Object actual, Object expected) {
            if (actual == null ? expected == null : actual.equals(expected)) {
                pass++;
                return;
            }
            fail++;
            bad.append(' ').append(what).append("：实际 `").append(actual)
                    .append("`，应当 `").append(expected).append("`;");
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

    /** 一组<b>不含 NaN</b> 的 16 元素矩阵（单位阵 + 平移 + 绕 Y 旋转；当"好"的那一半）。 */
    public static float[] goodMatrix() {
        return new float[]{
                0.0f, 0.0f, -1.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f, 0.0f,
                1.5f, 64.0f, -8.5f, 1.0f};
    }

    /** 同一组矩阵，但把第 7 个元素换成 {@code NaN}（当"坏"的那一半；负对照）。 */
    public static float[] nanMatrix() {
        final float[] m = goodMatrix();
        m[6] = Float.NaN;
        return m;
    }

    /** {@code Infinity} 也要算坏（负对照：只查 NaN 是漏的）。 */
    public static float[] infiniteMatrix() {
        final float[] m = goodMatrix();
        m[15] = Float.POSITIVE_INFINITY;
        return m;
    }

    private static Gates gates(boolean menuOn, boolean hasBoard,
                               boolean poseFinite, boolean viewFinite, boolean boardFinite) {
        return new Gates(menuOn, hasBoard, poseFinite, viewFinite, boardFinite);
    }

    /**
     * 离线自检（<b>不碰 MC / 不碰 GL</b>）：{@code fail == 0} 即全过。
     *
     * <pre>
     *   N1  goodMatrix 不含 NaN
     *   N2  nanMatrix 含 NaN（负对照：判定必须能抓到）
     *   N3  infiniteMatrix 含 Inf（负对照：只查 NaN 是漏的）
     *   N4  null / 长度不足 ⇒ 当成"有"（宁可判坏）
     *   N5  anyNonFinite 三元组（正例 / 负例）
     *   P1  全齐 ⇒ 四档全 submitted
     *   P2  菜单关 ⇒ 四档全 skipped，原因一律 menu_off
     *   P3  pose 含 NaN ⇒ A/B/C/D 全 skipped（A、D 报 pose_nan）
     *   P4  view 含 NaN、pose 好 ⇒ 只有 B skipped（报 view_nan），A/C/D 照画
     *   P5  板矩阵含 NaN ⇒ 只有 C skipped（报 board_nan）
     *   P6  没板 ⇒ 只有 C skipped（报 no_board）
     *   P7  skipText 只列没画的档（正例 + 全画 ⇒ none）
     *   P8  plan.text() 逐字
     *   S1–S8 开关状态机（默认 off / 翻转 / 别名 / 非法参数不改）
     *   D1  packBuf&gt;0 ⇒ pbo_bound（且优先于别的）
     *   D2  readFbo≠mainFbo ⇒ read_fbo_not_main
     *   D3  fboStatus 不完整 ⇒ fbo_incomplete
     *   D4  hasDepth=NO ⇒ no_depth_attachment
     *   D5  glError≠0 ⇒ gl_error；glError=-1（抛异常）也归 gl_error
     *   D6  值超范围 / NaN ⇒ out_of_range
     *   D7  全好 ⇒ none（<b>且值 0.0 也是 none</b>：0 是合法读数）
     *   D8  读不到时 depthText 一律 na（负对照：不许把 0 当读数）
     *   L1  整行逐字比对（黄金样本）
     *   L2  字段顺序没漂
     *   L3  board0_ndc 非有限 ⇒ na
     *   L4  depthFail 非 ok ⇒ occluder_depth=na（同一个口径）
     *   L5  depthFailLine / depthOkLine / registeredLine / switchLine 的形态
     *   L6  FBO_COMPLETE 常量真的是 0x8CD5
     * </pre>
     */
    public static Report selfTest() {
        final Acc a = new Acc();

        // ---- NaN / 有限性 ----
        a.ok("N1 goodMatrix 不含 NaN/Inf", !hasNonFinite(goodMatrix()));
        a.ok("N2 nanMatrix 被抓到", hasNonFinite(nanMatrix()));
        a.ok("N3 infiniteMatrix 也被抓到（只查 NaN 是漏的）", hasNonFinite(infiniteMatrix()));
        a.ok("N4a null ⇒ 当成有", hasNonFinite(null));
        a.ok("N4b 长度 15 ⇒ 当成有", hasNonFinite(new float[15]));
        a.ok("N5a 三元组含 NaN", anyNonFinite(1.0, Double.NaN, 3.0));
        a.ok("N5b 三元组含 +Inf", anyNonFinite(1.0, 2.0, Double.POSITIVE_INFINITY));
        a.ok("N5c 三元组正常 ⇒ false", !anyNonFinite(1.0, 2.0, 3.0));

        // ---- 四档决策 ----
        final Plan all = plan(gates(true, true, true, true, true));
        a.eq("P1 四档 text", all.text(),
                "A=submitted B=submitted C=submitted D=submitted");
        a.eq("P1b 四档 skipText", all.skipText(), NONE);

        final Plan menuOff = plan(gates(false, true, true, true, true));
        a.eq("P2a 菜单关 ⇒ 四档全 skipped", menuOff.text(),
                "A=skipped B=skipped C=skipped D=skipped");
        a.eq("P2b 原因一律 menu_off", menuOff.skipText(),
                "A:menu_off,B:menu_off,C:menu_off,D:menu_off");

        final Plan poseBad = plan(gates(true, true, false, false, true));
        a.eq("P3a pose 坏 ⇒ A/B/C/D 全 skipped", poseBad.text(),
                "A=skipped B=skipped C=skipped D=skipped");
        a.eq("P3b A 的原因是 pose_nan", poseBad.a().reason(), REASON_POSE_NAN);
        a.eq("P3c D 的原因是 pose_nan", poseBad.d().reason(), REASON_POSE_NAN);

        final Plan viewBad = plan(gates(true, true, true, false, true));
        a.eq("P4a view 坏 ⇒ 只有 B skipped", viewBad.text(),
                "A=submitted B=skipped C=submitted D=submitted");
        a.eq("P4b B 的原因是 view_nan", viewBad.b().reason(), REASON_VIEW_NAN);
        a.eq("P4c skipText 只列 B", viewBad.skipText(), "B:view_nan");

        final Plan boardBad = plan(gates(true, true, true, true, false));
        a.eq("P5a 板矩阵坏 ⇒ 只有 C skipped", boardBad.text(),
                "A=submitted B=submitted C=skipped D=submitted");
        a.eq("P5b C 的原因是 board_nan", boardBad.c().reason(), REASON_BOARD_NAN);

        final Plan noBoard = plan(gates(true, false, true, true, true));
        a.eq("P6a 没板 ⇒ 只有 C skipped", noBoard.text(),
                "A=submitted B=submitted C=skipped D=submitted");
        a.eq("P6b C 的原因是 no_board", noBoard.c().reason(), REASON_NO_BOARD);

        a.eq("P7a 两档没画 ⇒ skipText 两段", plan(gates(true, false, true, false, true)).skipText(),
                "B:view_nan,C:no_board");
        a.eq("P7b 全画 ⇒ skipText=none", all.skipText(), NONE);

        // ---- 开关 ----
        a.eqBool("S1 默认值 = off", initial(), false);
        a.eqBool("S2 无参翻转 off→on", resolve(false, null), true);
        a.eqBool("S3 无参翻转 on→off", resolve(true, null), false);
        a.eqBool("S4 \"on\" ⇒ true", resolve(false, "on"), true);
        a.eqBool("S5 \"0\" ⇒ false", resolve(true, "0"), false);
        a.eqBool("S6 非法参数不改（off）", resolve(false, "wat"), false);
        a.eqBool("S7 非法参数不改（on）", resolve(true, "wat"), true);
        a.eqBool("S8 空白按无参翻转", resolve(true, "   "), false);

        // ---- 深度读判定 ----
        final int okRead = 12;
        final int okMain = 12;
        a.eq("D1 pbo_bound（优先于别的）", depthFailReason(3, 12, 12, FBO_COMPLETE,
                DEPTH_ATTACH_YES, 0x502, 0.0), DEPTH_FAIL_PBO);
        a.eq("D2 read_fbo_not_main", depthFailReason(0, 7, 12, FBO_COMPLETE,
                DEPTH_ATTACH_YES, 0, 0.5), DEPTH_FAIL_READ_FBO);
        a.eq("D3 fbo_incomplete", depthFailReason(0, okRead, okMain, 0x8CD6,
                DEPTH_ATTACH_YES, 0, 0.5), DEPTH_FAIL_FBO_STATUS);
        a.eq("D4 no_depth_attachment", depthFailReason(0, okRead, okMain, FBO_COMPLETE,
                DEPTH_ATTACH_NO, 0, 0.5), DEPTH_FAIL_NO_ATTACH);
        a.eq("D5a gl_error", depthFailReason(0, okRead, okMain, FBO_COMPLETE,
                DEPTH_ATTACH_YES, 0x0502, 0.5), DEPTH_FAIL_GL_ERROR);
        a.eq("D5b 读取抛异常（-1）也归 gl_error", depthFailReason(0, okRead, okMain, FBO_COMPLETE,
                DEPTH_ATTACH_YES, -1, 0.5), DEPTH_FAIL_GL_ERROR);
        a.eq("D6a 值 >1 ⇒ out_of_range", depthFailReason(0, okRead, okMain, FBO_COMPLETE,
                DEPTH_ATTACH_YES, 0, 1.5), DEPTH_FAIL_RANGE);
        a.eq("D6b 值 = NaN ⇒ out_of_range", depthFailReason(0, okRead, okMain, FBO_COMPLETE,
                DEPTH_ATTACH_YES, 0, Double.NaN), DEPTH_FAIL_RANGE);
        a.eq("D7a 全好 ⇒ none", depthFailReason(0, okRead, okMain, FBO_COMPLETE,
                DEPTH_ATTACH_YES, 0, 0.5), DEPTH_OK);
        a.eq("D7b 值恰好 0.0 也是 none（0 是合法读数）", depthFailReason(0, okRead, okMain, FBO_COMPLETE,
                DEPTH_ATTACH_YES, 0, 0.0), DEPTH_OK);
        a.eq("D7c 附件未知（三态 -1）不算失败", depthFailReason(0, okRead, okMain, FBO_COMPLETE,
                DEPTH_ATTACH_UNKNOWN, 0, 0.25), DEPTH_OK);
        a.eq("D7d FBO 查询失败（-1）不算失败", depthFailReason(0, okRead, okMain, -1,
                DEPTH_ATTACH_UNKNOWN, 0, 0.25), DEPTH_OK);
        a.eq("D8a 读失败 ⇒ depthText 一律 na", depthText(0.0, DEPTH_FAIL_PBO), NA);
        a.eq("D8b 读成功 ⇒ 6 位小数", depthText(0.0, DEPTH_OK), "0.000000");
        a.eq("D8c 读成功 0.9876543 ⇒ 0.987654", depthText(0.9876543, DEPTH_OK), "0.987654");
        a.eq("D9a glErrorText(0)=none", glErrorText(0), NONE);
        a.eq("D9b glErrorText(0x502)=0x0502", glErrorText(0x502), "0x0502");
        a.eq("D9c glErrorText(-1)=na", glErrorText(-1), NA);
        a.eq("D10a attach yes", depthAttachText(DEPTH_ATTACH_YES), "yes");
        a.eq("D10b attach no", depthAttachText(DEPTH_ATTACH_NO), "no");
        a.eq("D10c attach unknown", depthAttachText(DEPTH_ATTACH_UNKNOWN), NA);

        // ---- 整行 ----
        final Sample golden = new Sample(7L, all,
                1.5, 64.0, -8.5,
                0.0, 0.0, -1.0,
                false, false,
                1.25, 63.9, -5.75,
                0.25f, -0.5f,
                0.0, DEPTH_OK,
                1.0,
                2.2,
                0, 1, true);
        final String goldenLine = "debugbox frame=7 A=submitted B=submitted C=submitted D=submitted "
                + "cam=(1.500,64.000,-8.500) look=(0.000,0.000,-1.000) pose_has_nan=false "
                + "board0_world=(1.250,63.900,-5.750) board0_ndc=(0.2500,-0.5000) "
                + "occluder_depth=0.000000 depth_fail=none depth_early=1.000000 "
                + "board0_width=2.200 board_nan=false depth_a=0 depth_d=1 restore_ok=true skip=none";
        a.eq("L1 整行逐字", line(golden), goldenLine);

        final String l = line(golden);
        int at = -1;
        boolean ordered = true;
        String brokeAt = "";
        for (String f : FIELD_ORDER) {
            final int here = l.indexOf(f);
            if (here <= at) {
                ordered = false;
                brokeAt = f;
                break;
            }
            at = here;
        }
        a.ok("L2 字段顺序没漂（首个断点=" + (ordered ? "none" : brokeAt) + "）", ordered);
        a.ok("L2b 行首 = debugbox frame=", l.startsWith("debugbox frame="));

        final Sample nanNdc = new Sample(1L, all, 1.5, 64.0, -8.5, 0.0, 0.0, -1.0,
                true, true, Double.NaN, 63.9, -5.75,
                Float.NaN, -0.5f, Double.NaN, DEPTH_FAIL_READ_FBO, Double.NaN, Double.NaN,
                -1, -1, false);
        final String nl = line(nanNdc);
        a.ok("L3a board0_ndc 非有限 ⇒ na", nl.contains(" board0_ndc=na "));
        a.ok("L3b board0_world 分量非有限 ⇒ 那一格 nan", nl.contains("board0_world=(nan,63.900,-5.750)"));
        a.ok("L3c pose_has_nan=true", nl.contains(" pose_has_nan=true "));
        a.ok("L4a depthFail 非 ok ⇒ occluder_depth=na", nl.contains(" occluder_depth=na "));
        a.ok("L4b depth_fail 原样", nl.contains(" depth_fail=read_fbo_not_main "));
        a.ok("L4c depth_a=-1 ⇒ na", nl.contains(" depth_a=na "));
        a.ok("L4d depth_early 非有限 ⇒ na", nl.contains(" depth_early=na "));
        a.ok("L4e board0_width 非有限 ⇒ nan", nl.contains(" board0_width=nan "));

        a.eq("L5a switchLine(true)", switchLine(true), "holo_debugbox on=true");
        a.eq("L5b switchLine(false)", switchLine(false), "holo_debugbox on=false");
        a.eq("L5c registeredLine(true)", registeredLine(true, "/shanhai_add/holo/debugbox"),
                "holo_debugbox_registered present=true path=/shanhai_add/holo/debugbox");
        a.eq("L5d registeredLine 缺路径 ⇒ path=na",
                registeredLine(false, null),
                "holo_debugbox_registered present=false path=na");
        final DepthSample bad = new DepthSample(Double.NaN, DEPTH_FAIL_PBO,
                7, 12, 3, FBO_COMPLETE, DEPTH_ATTACH_YES, 0x502, 960, 540);
        a.eq("L5e depthFailLine 逐字", depthFailLine(bad, "early"),
                "depth_read_fail reason=pbo_bound at=early pixel=(960,540) read_fbo=7 main_fbo=12 "
                        + "pack_buf=3 fbo_status=0x8CD5 depth_attach=yes gl_err=0x0502 value=na");
        final DepthSample good = new DepthSample(0.25, DEPTH_OK, 12, 12, 0, FBO_COMPLETE,
                DEPTH_ATTACH_YES, 0, 10, 20);
        a.eq("L5f depthOkLine 逐字", depthOkLine(good, "early"),
                "depth_read_ok at=early value=0.250000 read_fbo=12");
        a.ok("L5g depthFailLine(null) 也成一行",
                depthFailLine(null, "board").startsWith("depth_read_fail reason=unknown at=board "));
        a.eq("L6 FBO_COMPLETE 常量", FBO_COMPLETE, 0x8CD5);

        return a.toReport();
    }

    /** 一行自检读数（进日志；{@code pass=/fail=/exit=} 三个字段固定）。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return SELFTEST_PREFIX + " " + r.line() + (r.ok() ? "" : " FAILED:" + r.bad());
    }

    /**
     * 离线入口：{@code java -cp <只有本类所在目录> com.shanhai.client.holo.ShanhaiHoloDebugBox}
     * <p>输出一行 {@code holo_debugbox_selftest pass=… fail=… exit=…}，退出码同 {@code exit=}。
     * <p>⚠️ 本类<b>不依赖 Minecraft / 不依赖 GL</b> ⇒ 这条自检不需要构建 mod、不需要开游戏。
     */
    public static void main(String[] args) {
        final Report r = selfTest();
        System.out.println(SELFTEST_PREFIX + " " + r.line());
        if (!r.ok()) {
            System.out.println("FAILED:" + r.bad());
        }
        System.exit(r.exitCode());
    }
}
