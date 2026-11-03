package com.shanhai.common.reality;

/**
 * 「往文本框里插一段码」的<b>纯字符串层</b> ＋ <b>选择器按钮表</b>。
 *
 * <h2>🔴 为什么单开一个类（一个 MC 类都不 import）</h2>
 * 插入这件事的<b>全部逻辑</b>就是"给定 当前串 / 光标位 / token，算出 新串 / 新光标位"，
 * 与 Minecraft 无关。把它放在这里 ⇒ <b>能离线跑单测</b>（{@code temp\loreedit\impl\} 里的探针），
 * 而按钮坐标也放这里 ⇒ 探针能对"按钮互不重叠、且不与 10 个行框相交"出<b>读数</b>，而不是靠人看图。
 *
 * <h2>🔴 为什么必须用 {@code setValue} 路线（这条是全案的地基）</h2>
 * {@code §}(U+00A7=167) 在原版里是"非法聊天字符"：
 * <ul>
 *   <li>手敲 ⇒ {@code EditBox#charTyped} 走 {@code SharedConstants#isAllowedChatCharacter}，{@code c != 167} ⇒ 丢弃；</li>
 *   <li>粘贴 / {@code EditBox#insertText} ⇒ 走 {@code SharedConstants#filterText}，<b>§ 被静默吃掉</b>；</li>
 *   <li>{@code EditBox#setValue} ⇒ <b>不过 filterText</b>，§ 存活 ⇒ 唯一绕行点。</li>
 * </ul>
 * ⇒ 所以 {@link #insert} 只做纯字符串拼接，落地由 {@code ShanhaiTokenField#insertAtCursor} 用
 * {@code setCurrentString}（内部 = {@code EditBox#setValue}）完成。<b>绝不许</b>换成 {@code insertText}。
 *
 * <p>取证与字节码出处：{@code temp\loreedit\取证.md} §① §③。
 */
public final class ShanhaiTokenLibrary {

    private ShanhaiTokenLibrary() {
    }

    /** 段位符 U+00A7。🔴 用常量写，源码里<b>不打字面量</b>（工具/编码容易把它搞坏）。 */
    public static final char SECTION = '\u00A7';

    /** 山海样式的收尾符（正文区里第一个 {@code &r} 之后的部分交回原色）。 */
    public static final String RESTORE = "&r";

    /** 按钮属于哪一组（只影响 tooltip 用词与探针分组，不影响插入）。 */
    public enum Group {
        /** 原版 16 色。 */
        VANILLA_COLOR,
        /** 原版 6 个格式码。 */
        VANILLA_FORMAT,
        /** 山海 {@code &$…-} 样式。 */
        STYLE,
        /** {@code &r} 收尾。 */
        RESTORE
    }

    /**
     * 一个按钮。<b>坐标是面板局部坐标</b>（与「提示」多行编辑器其余控件同一个坐标系，面板 340×300）。
     *
     * @param token 点一下插进文本框的原文（例：{@code §f} / {@code &$neon-} / {@code &r}）
     * @param group 分组
     * @param label 画在按钮上的字（色块按钮为空串）
     * @param zh    中文名（tooltip / 状态行用；<b>不含 §</b>，见类注释的"§ 显示不出来"说明）
     * @param rgb   色块颜色（{@code -1} = 不画色块）
     */
    public record Chip(String token, Group group, String label, String zh,
                       int rgb, int x, int y, int w, int h) {
    }

    // ================================================================== 插入（纯字符串）

    /** 插入结果码。 */
    public enum Status {
        /** 插好了。 */
        OK,
        /** token 是空串（调用方给错了，不是玩家能触发的）。 */
        EMPTY_TOKEN,
        /** 插进去会超过每行上限 ⇒ <b>整次拒绝</b>（不静默截断，见任务书 ⑤）。 */
        TOO_LONG
    }

    /**
     * 插入结果。
     *
     * @param status 结果码
     * @param value  {@code OK} 时是新串；其它情况是<b>原串</b>（调用方可以安全忽略）
     * @param cursor {@code OK} 时是新光标位；其它情况是<b>夹紧后的原光标位</b>
     */
    public record Insert(Status status, String value, int cursor) {

        public boolean ok() {
            return this.status == Status.OK;
        }

        public int length() {
            return this.value == null ? 0 : this.value.length();
        }
    }

    /**
     * <b>纯函数</b>：把 {@code token} 插到 {@code cursor} 处。
     *
     * <p>语义与 {@code EditBox} 的光标语义对齐：{@code cursor} 会被夹到 {@code [0, value.length()]}；
     * 插入后光标落在 token 之后（与手打一个字后光标前进一致）。
     *
     * <p>🔴 超上限 ⇒ {@link Status#TOO_LONG} ＋ <b>原串原样返回</b>。为什么不"截断到上限"：
     * {@code EditBox#setValue} 自己就会静默截断到 {@code maxLength}，如果这里再截一次，
     * 玩家会看到"点了没反应/字少了"却没有任何解释。⇒ <b>宁可拒绝 + 出声</b>。
     */
    public static Insert insert(String value, int cursor, String token, int maxLength) {
        final String v = value == null ? "" : value;
        final String t = token == null ? "" : token;
        final int caret = Math.max(0, Math.min(cursor, v.length()));
        if (t.isEmpty()) {
            return new Insert(Status.EMPTY_TOKEN, v, caret);
        }
        final String next = v.substring(0, caret) + t + v.substring(caret);
        if (maxLength > 0 && next.length() > maxLength) {
            return new Insert(Status.TOO_LONG, v, caret);
        }
        return new Insert(Status.OK, next, caret + t.length());
    }

    // ================================================================== 按钮内容

    /** 原版 16 色的标准色值（1.20.1 {@code ChatFormatting}）。 */
    private static final int[] COLOR_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF00, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    /** 16 色的色码（顺序与 {@link #COLOR_RGB} 一一对应）。 */
    private static final String[] COLOR_CODES = {
            "0", "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "a", "b", "c", "d", "e", "f"
    };

    /** 16 色的中文名（顺序同上；tooltip / 状态行用）。 */
    private static final String[] COLOR_ZH = {
            "黑", "深蓝", "深绿", "深青", "深红", "深紫", "金色", "灰",
            "深灰", "亮蓝", "亮绿", "亮青", "亮红", "亮粉", "亮黄", "白"
    };

    /** 6 个格式码与中文名。 */
    private static final String[][] FORMATS = {
            {"k", "随机乱码"},
            {"l", "粗体"},
            {"m", "删除线"},
            {"n", "下划线"},
            {"o", "斜体"},
            {"r", "清除格式"}
    };

    /**
     * 16 个山海样式。
     *
     * <p>🔴 这 16 个名字<b>逐个对齐 {@code ShanhaiTextPalette} 的注册名</b>
     * （{@code ShanhaiTextPalette:251-273}；未在表里 = 解析器走"退化 A"，只剥码不上色）。
     * 探针 {@code ProbeStyleNames} 会打印两列对拍。
     *
     * <p>列 3 = <b>是否与 ultimate 共用同一套色板</b>（{@code ultimateRainbow} / {@code gray} / {@code green}
     * 在上游就是回落到 {@code ULTIMATE_RAINBOW_RGB}）⇒ 按钮上要标出来，否则玩家以为是三种新颜色。
     */
    private static final Object[][] STYLES = {
            // {注册名, 按钮两字标签, 中文名, 与 ultimate 同色板?}
            {"ultimate", "ul", "终极彩虹", Boolean.FALSE},
            {"golden", "gd", "黄金", Boolean.FALSE},
            {"magic", "mg", "魔法", Boolean.FALSE},
            {"water", "wt", "水波", Boolean.FALSE},
            {"aurora", "ar", "极光", Boolean.FALSE},
            {"neon", "ne", "霓虹", Boolean.FALSE},
            {"crimson", "cr", "猩红", Boolean.FALSE},
            {"cosmic", "cs", "宇宙", Boolean.FALSE},
            {"electric", "el", "电光", Boolean.FALSE},
            {"body_golden", "bG", "正文金黄", Boolean.FALSE},
            {"body_moss", "bM", "正文苔绿", Boolean.FALSE},
            {"body_aurora", "bA", "正文极光", Boolean.FALSE},
            {"body_silver", "bS", "正文银白", Boolean.FALSE},
            {"ultimateRainbow", "uR", "终极彩虹（别名）", Boolean.TRUE},
            {"gray", "gy", "灰（与 ultimate 同色板）", Boolean.TRUE},
            {"green", "gn", "绿（与 ultimate 同色板）", Boolean.TRUE}
    };

    /** 一个样式的元信息（探针用；渲染走 {@link #CHIPS}）。 */
    public record StyleInfo(String name, String label, String zh, boolean sharesUltimatePalette) {
    }

    /** 16 个样式的元信息（顺序 = 按钮顺序）。 */
    public static final StyleInfo[] STYLE_INFO = buildStyleInfo();

    private static StyleInfo[] buildStyleInfo() {
        final StyleInfo[] out = new StyleInfo[STYLES.length];
        for (int i = 0; i < STYLES.length; i++) {
            out[i] = new StyleInfo((String) STYLES[i][0], (String) STYLES[i][1],
                    (String) STYLES[i][2], (Boolean) STYLES[i][3]);
        }
        return out;
    }

    // ================================================================== 布局（唯一真源）

    /** 第一排（原版色码）的 y。 */
    public static final int ROW_A_Y = 240;
    /** 第二排（山海样式）的 y。 */
    public static final int ROW_B_Y = 258;
    /** 按钮统一高度。 */
    public static final int CHIP_H = 16;
    /** 按钮带左边距（左侧 6..42 留给组标题）。 */
    public static final int CHIP_X0 = 46;
    /** 色码/格式码按钮宽（13 × 22 个 = 286px ≤ 面板可用 294px）。 */
    public static final int COLOR_CHIP_W = 13;
    /** 样式按钮宽（16 × 17 个 = 272px）。 */
    public static final int STYLE_CHIP_W = 16;
    /** 第一排组标题（「原版格式」）的锚点。 */
    public static final int GROUP_A_LABEL_X = 6;
    public static final int GROUP_A_LABEL_Y = 244;
    /** 第二排组标题（「山海样式」）的锚点。 */
    public static final int GROUP_B_LABEL_X = 6;
    public static final int GROUP_B_LABEL_Y = 262;

    /**
     * <b>39 个按钮的完整坐标表</b>（16 色 ＋ 6 格式 ＋ 16 样式 ＋ 1 个 {@code &r}）。
     *
     * <p>🔴 为什么是 22 / 17 而不是"每排 12 个"：39 个 token 用「每排 12 个 × 2 排 = 24 格」<b>放不下</b>
     * （任务书 ② 给的 26×16 ×2 排最多 24 格）。保持任务书 ④ 的「接受/取消 在 y=278」这条最重要的约束，
     * 就只能让按钮带保持 <b>2 排</b>（y=240..274），于是每排的格数由宽度决定：
     * 22 × 13 = 286（色码/格式码），17 × 16 = 272（样式/收尾）。见报告「与任务书的偏离」。
     */
    public static final Chip[] CHIPS = buildChips();

    private static Chip[] buildChips() {
        final java.util.List<Chip> out = new java.util.ArrayList<>(39);
        int x = CHIP_X0;
        // ---- 第一排：16 色 + 6 格式（共 22 个） ----
        for (int i = 0; i < COLOR_CODES.length; i++) {
            out.add(new Chip(String.valueOf(SECTION) + COLOR_CODES[i],
                    Group.VANILLA_COLOR, "", COLOR_ZH[i],
                    0xFF000000 | COLOR_RGB[i], x, ROW_A_Y, COLOR_CHIP_W, CHIP_H));
            x += COLOR_CHIP_W;
        }
        for (String[] f : FORMATS) {
            out.add(new Chip(String.valueOf(SECTION) + f[0],
                    Group.VANILLA_FORMAT, f[0], f[1],
                    -1, x, ROW_A_Y, COLOR_CHIP_W, CHIP_H));
            x += COLOR_CHIP_W;
        }
        // ---- 第二排：16 样式 + &r（共 17 个） ----
        x = CHIP_X0;
        for (StyleInfo s : STYLE_INFO) {
            out.add(new Chip("&$" + s.name() + "-", Group.STYLE, s.label(), s.zh(),
                    -1, x, ROW_B_Y, STYLE_CHIP_W, CHIP_H));
            x += STYLE_CHIP_W;
        }
        out.add(new Chip(RESTORE, Group.RESTORE, "&r", "收尾：后半段交回原色",
                -1, x, ROW_B_Y, STYLE_CHIP_W, CHIP_H));
        return out.toArray(new Chip[0]);
    }

    /** 按钮带上沿（含）与下沿（不含）—— 探针用它断言"与 10 个行框不相交"。 */
    public static int bandTop() {
        return ROW_A_Y;
    }

    /** 按钮带下沿（不含）。 */
    public static int bandBottom() {
        return ROW_B_Y + CHIP_H;
    }

    // ================================================================== 文案

    /**
     * 状态行/tooltip 里怎么称呼一个 token。
     *
     * <p>🔴 <b>为什么不用 § 字面量</b>：MC 的任何 String 路径（{@code LabelWidget} /
     * {@code graphics.drawString} / {@code Component.literal}）在绘制时都会把 {@code §} 解析成格式码，
     * <b>没有转义写法</b> ⇒ 屏幕上是<b>画不出</b> {@code §} 这个字的。所以这里一律用「颜色码 f」「格式码 l」
     * 这种说法，真正的码由按钮本身（色块 / 字母）表达。
     */
    public static String describe(Chip chip) {
        if (chip == null) {
            return "";
        }
        return switch (chip.group()) {
            case VANILLA_COLOR -> "颜色码 " + chip.token().substring(1) + "（" + chip.zh() + "）";
            case VANILLA_FORMAT -> "格式码 " + chip.token().substring(1) + "（" + chip.zh() + "）";
            case STYLE -> chip.token() + "（" + chip.zh() + "）";
            case RESTORE -> RESTORE + "（" + chip.zh() + "）";
        };
    }

    /** 把 {@code §} 等"画不出来/会被解析"的字符换成可读占位（日志、状态行用）。 */
    public static String visible(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace(SECTION, '#');
    }
}
