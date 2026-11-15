package com.shanhai.client.holo;

import java.util.Locale;

/**
 * 山海重构 · 全息菜单「几何在视野里但看不见」的<b>遮挡探针</b>（纯逻辑：零 Minecraft / 零 GL）。
 *
 * <h2>1. 它补的是哪一段空白</h2>
 * "看不到"在旧日志里永远只能靠猜，因为没有任何字段能分开下面两条完全不同的病因：
 * <ol>
 *   <li>这一帧<b>确实被别的东西盖住了</b>（外来的全屏叠层 / 我们自己的黑洞透镜）；</li>
 *   <li>我们<b>根本没提交</b>可见内容（0 块板 / alpha≈0 / 缩放≈0）。</li>
 * </ol>
 * 本类把这两条钉成一段可 grep 的读数，并把判定规则写成纯函数 ⇒ 能在<b>没有游戏</b>的情况下逐字验证
 * （见 {@link #selfTest()} / {@link #main}）。
 *
 * <p>🔴 本类<b>不认识任何具体的模组</b>：它只吃"这一帧的几何与 GL 读数"，
 * 既不看别人的类名，也不读别人的静态字段。外来叠层到底是谁盖的，由 {@code screen=} 与
 * 上面那几个 GL 读数去回答，本类只负责判"<b>我们这一帧到底提交了没有</b>"。
 *
 * <h2>2. 一行长什么样（字段名固定，顺序固定）</h2>
 * <pre>
 *   occl_probe count=&lt;n&gt; types=&lt;ns:path,...&gt; nearest=&lt;格&gt;
 *              blend=&lt;0|1|na&gt; depth_test=&lt;0|1|na&gt; depth_write=&lt;0|1|na&gt;
 *              color_mask=&lt;四位 0/1|na&gt; src=&lt;GL 枚举原值|na&gt; dst=&lt;GL 枚举原值|na&gt;
 *              screen=&lt;Screen 简单类名|none&gt;
 *              bh=&lt;lens 路径|SKIP_NO_CENTER|na&gt;
 *              drawn_boards=&lt;n&gt; quads=&lt;n&gt; verts=&lt;n&gt; alpha=&lt;3 位小数&gt; scale=&lt;3 位小数&gt;
 *              visible=&lt;0|1&gt; occluder=&lt;not_drawn|invisible_alpha|none&gt;
 * </pre>
 * 换行只为读起来方便；<b>真的一行</b>（同一行里空格分隔）。
 *
 * <h2>3. 判定规则（顺序即优先级，先命中先定）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>条件</th><th>{@code occluder=}</th><th>含义</th></tr>
 *   <tr><td>①</td><td>{@code drawn_boards == 0}</td><td>{@code not_drawn}</td>
 *       <td>这一帧<b>一块板都没提交</b> ⇒ 与遮挡无关，掉头查"为什么没画"</td></tr>
 *   <tr><td>②</td><td>{@code alpha < 0.01}（NaN 也归这一档）</td><td>{@code invisible_alpha}</td>
 *       <td>板提交了，但逐顶点乘进去的 alpha 几乎为 0 ⇒ 画了等于没画</td></tr>
 *   <tr><td>③</td><td>以上都不成立</td><td>{@code none}</td>
 *       <td>我们确实提交了可见内容 ⇒ 剩下的可能才是"被别人盖住"</td></tr>
 * </table>
 * ⚠️ ② 用 {@code !(alpha >= 0.01)} 而不是 {@code alpha < 0.01}：后者在 alpha=NaN 时为 false，
 * 会让"读坏了"落到 {@code none}（那是假绿）。两种写法在有限值上完全等价，NaN 上有意不同。
 *
 * <h2>4. {@code visible=} 是<b>判据合成</b>字段（不是新探测）</h2>
 * {@code visible = (drawn_boards > 0) && (alpha > 0.01) && (scale > 0.05)}
 * —— 三条<b>同时</b>成立才算"真的提交了可见内容"。
 * 它与 {@code occluder=} 刻意是<b>两个口径</b>：
 * <ul>
 *   <li>{@code occluder=} 判"<b>病因是哪一档</b>"（三条规则，一条不加）；</li>
 *   <li>{@code visible=} 判"<b>这一帧有没有可见内容</b>"（含缩放）。</li>
 * </ul>
 * 于是边界上会出现 {@code visible=0} 而 {@code occluder=none}（alpha 恰好 0.01、或板提交了但 scale=0）
 * —— 这是<b>故意的</b>：两条判据本来就在问不同的问题。离线自检 V5/V6 钉住了这两个边界。
 *
 * <h2>5. 这个类不做什么</h2>
 * 不打日志、不读世界、不碰 GL、不改任何开关：它只把<b>已经采到的原始值</b>拼成一行并给一个判定。
 * 采样与限流全在 {@code ShanhaiHoloMenuRenderer} 里（500 ms 一次，与 {@code visibility} 同拍）。
 */
public final class ShanhaiHoloOccluderProbe {

    private ShanhaiHoloOccluderProbe() {}

    /** 判定 ①：一块板都没提交（与遮挡无关）。 */
    public static final String OCC_NOT_DRAWN = "not_drawn";
    /** 判定 ②：板提交了但 alpha 几乎为 0。 */
    public static final String OCC_INVISIBLE_ALPHA = "invisible_alpha";
    /** 判定 ③：我们确实提交了可见内容（三档都不成立）。 */
    public static final String OCC_NONE = "none";

    /** {@code visible=} 的 alpha 门槛（判据：{@code alpha > 0.01}）。 */
    public static final float ALPHA_MIN = 0.01f;

    /** {@code visible=} 的缩放下限（判据：{@code scale > 0.05}）。 */
    public static final float SCALE_MIN = 0.05f;

    /** 行首标记（固定；{@code grep 'occl_probe '} 即可把这一族读全）。 */
    public static final String PREFIX = "occl_probe";

    /**
     * 一行读数所需的<b>全部原始值</b>（采样在渲染器里做，本记录只是搬运；字段顺序 <b>=</b> 日志顺序）。
     *
     * @param count       附近 48 格内<b>非原版命名空间</b>（{@code namespace != minecraft}）的实体数
     * @param types       其中出现过的注册名（去重、最多 4 条；空 ⇒ {@code none}）
     * @param nearest     其中最近的一个到玩家的距离（格；没有 ⇒ {@code -1}）
     * @param glValid     GL 状态有没有采到（false ⇒ 六个 GL 字段一律打 {@code na}，<b>不许</b>假 0/1）
     * @param blend       {@code glIsEnabled(GL_BLEND)}
     * @param depthTest   {@code glIsEnabled(GL_DEPTH_TEST)}
     * @param depthWrite  {@code glGetBoolean(GL_DEPTH_WRITEMASK)}
     * @param maskR/G/B/A {@code glGetBooleanv(GL_COLOR_WRITEMASK)} 的四位
     * @param src         {@code glGetInteger(GL_BLEND_SRC_ALPHA)}（GL 枚举原值，例如 770 = SRC_ALPHA）
     * @param dst         {@code glGetInteger(GL_BLEND_DST_ALPHA)}（例如 1 = ONE）
     * @param screen      {@code Minecraft.screen} 的简单类名；没有 ⇒ {@code none}
     * @param bh          黑洞透镜这一帧的路径（{@code lens-after-weather} 等）；没中心 ⇒ {@code SKIP_NO_CENTER}
     * @param drawnBoards 本帧真正提交了顶点的板数（本地 + 远程）
     * @param quads       本帧提交的四边形数（= {@code verts / 4}；文字走 Font 通道，不计入）
     * @param verts       本帧提交的顶点数
     * @param alpha       本帧逐顶点乘进去的 alpha（{@code ShanhaiHoloMenuAnim.alpha01()}）
     * @param scale       本帧的整块缩放（{@code ShanhaiHoloMenuAnim.scale01()}）
     */
    public record Sample(
            int count, String types, double nearest,
            boolean glValid,
            boolean blend, boolean depthTest, boolean depthWrite,
            boolean maskR, boolean maskG, boolean maskB, boolean maskA,
            int src, int dst,
            String screen, String bh,
            int drawnBoards, int quads, int verts, float alpha, float scale) {
    }

    // ==================================================================== 判定（纯函数）

    /**
     * 判定 ①–③（见类注释 §3，<b>顺序即优先级</b>）。
     *
     * @return {@link #OCC_NOT_DRAWN} / {@link #OCC_INVISIBLE_ALPHA} / {@link #OCC_NONE} 之一
     */
    public static String occluder(Sample s) {
        if (s.drawnBoards() <= 0) {
            return OCC_NOT_DRAWN;
        }
        if (!(s.alpha() >= ALPHA_MIN)) {          // 见类注释 §3 的 NaN 说明
            return OCC_INVISIBLE_ALPHA;
        }
        return OCC_NONE;
    }

    /**
     * "到底提交了没有"的唯一判据（三条<b>同时</b>成立）：
     * {@code drawn_boards > 0 && alpha > 0.01 && scale > 0.05}。
     */
    public static boolean submittedVisible(Sample s) {
        return s.drawnBoards() > 0 && s.alpha() > ALPHA_MIN && s.scale() > SCALE_MIN;
    }

    // ==================================================================== 行拼装（唯一出口）

    /** 把一份 {@link Sample} 拼成那<b>一行</b>（字段名与顺序固定，见类注释 §2）。 */
    public static String line(Sample s) {
        final StringBuilder sb = new StringBuilder(384);
        sb.append(PREFIX)
                .append(" count=").append(s.count())
                .append(" types=").append(nonEmpty(s.types()))
                .append(" nearest=").append(f2(s.nearest()));
        if (s.glValid()) {
            sb.append(" blend=").append(bit(s.blend()))
                    .append(" depth_test=").append(bit(s.depthTest()))
                    .append(" depth_write=").append(bit(s.depthWrite()))
                    .append(" color_mask=").append(mask(s.maskR(), s.maskG(), s.maskB(), s.maskA()))
                    .append(" src=").append(s.src())
                    .append(" dst=").append(s.dst());
        } else {
            // 「没有读数」与「读数是 0」必须长得不一样（本工程血规矩：宁可缺，不可假）
            sb.append(" blend=na depth_test=na depth_write=na color_mask=na src=na dst=na");
        }
        sb.append(" screen=").append(nonEmpty(s.screen()))
                .append(" bh=").append(nonEmpty(s.bh()))
                .append(" drawn_boards=").append(s.drawnBoards())
                .append(" quads=").append(s.quads())
                .append(" verts=").append(s.verts())
                .append(" alpha=").append(f3(s.alpha()))
                .append(" scale=").append(f3(s.scale()))
                .append(" visible=").append(bit(submittedVisible(s)))
                .append(" occluder=").append(occluder(s));
        return sb.toString();
    }

    /** 6 个 GL 字段在一行里的出现顺序（离线自检用来钉"字段没漂"，也是给人看的字段表顺序）。 */
    public static final String[] GL_FIELD_ORDER =
            {"blend=", "depth_test=", "depth_write=", "color_mask=", "src=", "dst="};

    /** 全行的字段出现顺序（离线自检逐项断言 {@code indexOf} 递增）。 */
    public static final String[] FIELD_ORDER = {
            "occl_probe count=", " types=", " nearest=", " blend=", " depth_test=", " depth_write=",
            " color_mask=", " src=", " dst=", " screen=", " bh=", " drawn_boards=",
            " quads=", " verts=", " alpha=", " scale=", " visible=", " occluder="};

    private static String bit(boolean v) {
        return v ? "1" : "0";
    }

    private static String mask(boolean r, boolean g, boolean b, boolean a) {
        final char[] out = new char[4];
        out[0] = r ? '1' : '0';
        out[1] = g ? '1' : '0';
        out[2] = b ? '1' : '0';
        out[3] = a ? '1' : '0';
        return new String(out);
    }

    private static String nonEmpty(String s) {
        return (s == null || s.isEmpty()) ? "none" : s;
    }

    private static String f2(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "nan";
    }

    private static String f3(float v) {
        return Float.isFinite(v) ? String.format(Locale.ROOT, "%.3f", v) : "nan";
    }

    // ==================================================================== 离线自检
    //
    //   为什么要它：这行读数将来唯一的用途是"下结论"。而"结论"最贵的错法是
    //   【规则自己写反了却全绿】—— 那种错没有症状，只会把排查带沟里。
    //   ⇒ 每条规则都要有一个正例 + 一个负对照（边界值各钉一次）。

    /** 自检读数：{@code pass} / {@code fail} 计数 + 失败原文（每条形如 {@code 标签：实际 …，应当 …;}）。 */
    public record Report(int pass, int fail, String bad) {

        /** 全过？ */
        public boolean ok() {
            return fail == 0;
        }

        /** 一行读数（{@code pass=/fail=/exit=} 三个字段，任务书点名）。 */
        public String line() {
            return "pass=" + pass + " fail=" + fail + " exit=" + exitCode();
        }

        /** 进程退出码：全过 0，有失败 1。 */
        public int exitCode() {
            return fail == 0 ? 0 : 1;
        }
    }

    /** 自检的断言累加器（把"过了几条 / 挂了哪几条"一次算清）。 */
    private static final class Acc {

        private int pass;
        private int fail;
        private final StringBuilder bad = new StringBuilder(256);

        void eq(String what, String actual, String expected) {
            if (expected == null ? actual == null : expected.equals(actual)) {
                pass++;
                return;
            }
            fail++;
            bad.append(' ').append(what).append("：实际 `").append(actual)
                    .append("`，应当 `").append(expected).append("`;");
        }

        void eqInt(String what, int actual, int expected) {
            if (actual == expected) {
                pass++;
                return;
            }
            fail++;
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("，应当 ").append(expected).append(';');
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
     * 自检用的样本（{@code count/types/nearest/src/dst/...} 取一组固定的值，只让被测的那几个量变）。
     * <p>样本里的注册名是<b>中立假数据</b>（{@code example:thing}），不指向任何具体模组。
     */
    private static Sample s(boolean glValid, int boards, int verts, float alpha, float scale) {
        return new Sample(2, "example:thing,example:other", 3.2, glValid,
                false, true, false,
                true, true, true, true,
                770, 1,
                "none", "lens-after-weather",
                boards, verts / 4, verts, alpha, scale);
    }

    /**
     * 离线自检（<b>不碰 MC / 不碰 GL</b>）：返回 {@code fail == 0} 即全过。
     *
     * <pre>
     *   O1  boards=0, alpha=0                        ⇒ not_drawn
     *   O2  boards=0, alpha=1                        ⇒ not_drawn       （①优先于②）
     *   O3  boards=5, alpha=0.0099                   ⇒ invisible_alpha
     *   O4  boards=5, alpha=0.01                     ⇒ none            （边界：< 0.01 才算不可见）
     *   O5  boards=5, alpha=NaN                      ⇒ invisible_alpha （负对照：NaN 不许落 none）
     *   O6  boards=5, alpha=1                        ⇒ none
     *   O7  boards=-1（读坏了）                       ⇒ not_drawn
     *   V1  boards=5, alpha=0.5, scale=0             ⇒ visible=0 / occluder=none
     *   V2  boards=5, alpha=0.5, scale=0.05          ⇒ visible=0       （严格 >）
     *   V3  boards=5, alpha=0.0101, scale=0.06       ⇒ visible=1
     *   V4  boards=0, alpha=1, scale=1               ⇒ visible=0
     *   V5  boards=5, alpha=0.01, scale=1            ⇒ visible=0 / occluder=none（两个口径的边界）
     *   L1  整行逐字比对（黄金样本）
     *   L2  字段顺序：indexOf 逐个递增（字段没漂、顺序没换）
     *   L3  types=null/空                            ⇒ types=none
     *   L4  screen/空、bh/空                          ⇒ screen=none / bh=none
     *   L5  alpha/scale 固定 3 位小数
     *   L6  color_mask 四位逐位（含负对照 1011）
     *   L7  blend/depth_test/depth_write 打 0/1
     *   L8  glValid=false ⇒ 六个 GL 字段全是 na，且不出现 src=770
     *   L9  nearest 2 位小数 / 非有限值 ⇒ nan
     *   L10 行首固定为 "occl_probe count="
     * </pre>
     */
    public static Report selfTest() {
        final Acc a = new Acc();

        // ---- 判定规则 ①–③ ----
        a.eq("O1 boards=0 ⇒ not_drawn", occluder(s(true, 0, 0, 0.0f, 1.0f)), OCC_NOT_DRAWN);
        a.eq("O2 boards=0 优先于 alpha=1", occluder(s(true, 0, 0, 1.0f, 1.0f)), OCC_NOT_DRAWN);
        a.eq("O3 alpha=0.0099 ⇒ invisible_alpha",
                occluder(s(true, 5, 740, 0.0099f, 1.0f)), OCC_INVISIBLE_ALPHA);
        a.eq("O4 alpha=0.01 ⇒ none（边界 <）",
                occluder(s(true, 5, 740, 0.01f, 1.0f)), OCC_NONE);
        a.eq("O5 alpha=NaN ⇒ invisible_alpha（负对照）",
                occluder(s(true, 5, 740, Float.NaN, 1.0f)), OCC_INVISIBLE_ALPHA);
        a.eq("O6 boards=5 且 alpha=1 ⇒ none", occluder(s(true, 5, 740, 1.0f, 1.0f)), OCC_NONE);
        a.eq("O7 boards=-1（读坏了）⇒ not_drawn", occluder(s(true, -1, 0, 1.0f, 1.0f)), OCC_NOT_DRAWN);

        // ---- "到底提交了没有"（visible=，三条同时成立）----
        a.ok("V1a scale=0 ⇒ 不算提交可见内容",
                !submittedVisible(s(true, 5, 740, 0.5f, 0.0f)));
        a.eq("V1b …但 occluder 仍是 none（两个口径）",
                occluder(s(true, 5, 740, 0.5f, 0.0f)), OCC_NONE);
        a.ok("V2 scale=0.05 恰好不算（严格 >）",
                !submittedVisible(s(true, 5, 740, 0.5f, 0.05f)));
        a.ok("V3 alpha=0.0101+scale=0.06 ⇒ 算",
                submittedVisible(s(true, 5, 740, 0.0101f, 0.06f)));
        a.ok("V4 boards=0 ⇒ 不算", !submittedVisible(s(true, 0, 0, 1.0f, 1.0f)));
        a.ok("V5 alpha=0.01 恰好不算（严格 >）",
                !submittedVisible(s(true, 5, 740, 0.01f, 1.0f)));

        // ---- 行拼装 ----
        final Sample golden = s(true, 5, 740, 1.0f, 1.0f);
        final String goldenLine = "occl_probe count=2 types=example:thing,example:other nearest=3.20 "
                + "blend=0 depth_test=1 depth_write=0 color_mask=1111 src=770 dst=1 "
                + "screen=none bh=lens-after-weather "
                + "drawn_boards=5 quads=185 verts=740 alpha=1.000 scale=1.000 "
                + "visible=1 occluder=none";
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
        a.ok("L10 行首 = " + PREFIX + " count=", l.startsWith(PREFIX + " count="));

        final Sample noTypes = new Sample(0, null, -1.0, true,
                true, true, true, true, true, true, true, 1, 0,
                "", "",
                0, 0, 0, 0.0f, 1.0f);
        final String noTypesLine = line(noTypes);
        a.ok("L3a types=null ⇒ types=none", noTypesLine.contains(" types=none "));
        a.ok("L3b screen/空 ⇒ screen=none", noTypesLine.contains(" screen=none "));
        a.ok("L3c bh=空 ⇒ bh=none", noTypesLine.contains(" bh=none "));
        a.ok("L5a alpha 3 位小数（0.5 ⇒ 0.500）",
                line(s(true, 5, 740, 0.5f, 0.25f)).contains(" alpha=0.500 "));
        a.ok("L5b scale 3 位小数（0.25 ⇒ 0.250）",
                line(s(true, 5, 740, 0.5f, 0.25f)).contains(" scale=0.250 "));
        final Sample maskSample = new Sample(0, "example:thing", 0.0, true,
                true, true, true, true, false, true, true, 770, 1,
                "none", "none", 1, 1, 4, 1.0f, 1.0f);
        a.ok("L6a color_mask 逐位（1011 形态）", line(maskSample).contains(" color_mask=1011 "));
        a.ok("L6b 负对照：不是 1111", !line(maskSample).contains(" color_mask=1111 "));
        a.ok("L7a blend=true ⇒ 1", line(maskSample).contains(" blend=1 "));
        a.ok("L7b depth_write=true ⇒ 1", line(maskSample).contains(" depth_write=1 "));
        final Sample noGl = new Sample(0, "example:thing", 0.0, false,
                true, true, true, true, true, true, true, 770, 1,
                "none", "none", 1, 1, 4, 1.0f, 1.0f);
        final String noGlLine = line(noGl);
        a.ok("L8a 采不到 GL ⇒ 六字段全 na",
                noGlLine.contains(" blend=na depth_test=na depth_write=na color_mask=na src=na dst=na"));
        a.ok("L8b 采不到 GL 时不许出现假值 src=770", !noGlLine.contains("src=770"));
        a.ok("L9a nearest 2 位小数（3.2 ⇒ 3.20）", line(golden).contains(" nearest=3.20 "));
        a.ok("L9b nearest 非有限 ⇒ nan", line(new Sample(0, "example:thing", Double.NaN, true,
                true, true, true, true, true, true, true, 1, 0,
                "none", "none", 1, 1, 4, 1.0f, 1.0f)).contains(" nearest=nan "));

        return a.toReport();
    }

    /** 一行自检读数（进日志；{@code pass=/fail=/exit=} 三个字段固定）。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return "occl_probe_selftest " + r.line() + (r.ok() ? "" : " FAILED:" + r.bad());
    }

    /**
     * 离线入口：{@code java -cp <只有本类所在目录> com.shanhai.client.holo.ShanhaiHoloOccluderProbe}
     * <p>输出一行 {@code occl_probe_selftest pass=… fail=… exit=…}，退出码同 {@code exit=}。
     * <p>⚠️ 本类<b>不依赖 Minecraft</b> ⇒ 这条自检不需要构建 mod、不需要开游戏。
     */
    public static void main(String[] args) {
        final Report r = selfTest();
        System.out.println("occl_probe_selftest " + r.line());
        if (!r.ok()) {
            System.out.println("FAILED:" + r.bad());
        }
        System.exit(r.exitCode());
    }
}
