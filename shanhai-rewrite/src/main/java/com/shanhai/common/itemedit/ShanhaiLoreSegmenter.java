package com.shanhai.common.itemedit;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · <b>物品描述（lore）的「分段器」</b> —— <b>纯函数、零 MC 依赖、可离线单测</b>。
 *
 * <h2>1. 它解决什么（2026-11-01 用户实机报的混排问题）</h2>
 * 用户在<b>同一行</b>里混用两套样式：
 * <pre>
 *   第 1 行： &amp;$golden-123&amp;r                        ← 纯山海，实机「正常」
 *   第 5 行： &amp;$magic-123&amp;r4§3567§r8                  ← 混排，实机「不正常」
 *   第 6 行： §4123§r4&amp;$aurora-567&amp;r8                 ← 混排，实机「不正常」
 * </pre>
 * <b>根因</b>：tooltip 的绘制走 {@code Component → FormattedCharSequence}，
 * {@code §} 在<b>那一步就已经被转成样式对象、并从文本里剥掉</b>
 * ⇒ {@code ShanhaiTextParser} 里「按文本里的 § 切段」的前提在 tooltip 上<b>不成立</b>
 * （它看到的重建文本里一个 § 都没有 ⇒ 整行被当成「纯山海」⇒ § 段被按彩虹重画/丢色）。
 * <p>
 * <b>竖切修法</b>（本类只做第一步）：把<b>一行</b>再切成<b>几段</b>，
 * {@code ShanhaiItemLore} 为每段造<b>一个</b> {@code Component}
 * ⇒ 每段各自走一次 {@code Font.drawInBatch} ⇒ 各段由各自的渲染器接管。
 *
 * <h2>2. 切分规则（定死，逐条对应实现）</h2>
 * <ol>
 *   <li>只要一行里出现 {@code &$}（{@link #SEGMENT_MARK}）就进切分；<b>一个都没有 ⇒ 整行 = 1 个原版段</b>
 *       （{@code ShanhaiItemLore} 据此走「逐字不变」的老路，这是回归底线）；</li>
 *   <li><b>山海段</b>从 {@code &$} 起，到<b>下一个 {@code &r}（含）</b> / <b>下一个 {@code &$}</b> / <b>行尾</b>
 *       三者最早的那个为止。<b>{@code &r} 留在段文本里</b>（不是装饰）：
 *       解析器的「正文 = {@code -} 之后到 {@code &r} 或串尾」这条规则靠它把正文边界钉住，
 *       否则后面的原版文字会被吞进 body 里按彩虹画；</li>
 *   <li>{@code &r} 缺失 ⇒ 该段一直到行尾 / 下一个 {@code &$}（与解析器「找不到 {@code &r} ⇒ suffix 为空」
 *       同一条语义，<b>不额外补</b> {@code &r}）；</li>
 *   <li>{@code &$} 之前、段与段之间的字 = <b>原版段</b>（逐字节原样，含 {@code §} 码）；
 *       <b>空的原版段不产出</b>；</li>
 *   <li>🔴 <b>{@code §} 出现在山海段内部 ⇒ 该段整体降级成原版段</b>（原样给原版画，并打一行限流 WARN，
 *       见 {@code ShanhaiItemLore#warnDegradedSegment}）。理由与 {@code ShanhaiTextParser} §7.3 同一条：
 *       {@code §} 是零宽控制码，我们的逐字路径自己算宽、自己取色，混进一段里下标会算错；
 *       降级后至少原版能把 {@code §} 语义画对；</li>
 *   <li><b>不校验形状</b>：{@code &$} 后面没有 {@code -}、没有样式名、正文为空…… 一律仍按「山海段」切出去。
 *       这种畸形写法在解析器那边本来就会判 {@code ours=false}（交回原版渲染），
 *       切分器<b>不重复那一层判断</b> —— 两处各判一次，迟早会不一致。</li>
 * </ol>
 * <p>
 * 🔴 <b>硬不变量</b>（离线自证里的"没丢字"断言就是它）：
 * <b>把全部段文本按顺序拼起来，必须逐字节等于输入行</b>。
 *
 * <h2>3. 负对照的唯一改动点</h2>
 * {@link #splitSegments(String)} 里只有一行 {@code return rawSplit(line);}。
 * 把它换成 {@code return List.of(new Seg(Kind.VANILLA, line, false));}（= 永远整行一段），
 * <b>其余代码一个字节都不动</b> ⇒ 自证里第 5、6 行的断言必须报红。
 * 这样报红只能归因于"切分"这一步，不能归因于顺手改了别处。
 *
 * <h2>4. 本类<b>不</b>负责的事（别在这里加）</h2>
 * <ul>
 *   <li><b>不加 {@code §7} 前缀</b>：那是 {@code ShanhaiItemLore#LINE_PREFIX} 的事（显示层口径）；</li>
 *   <li><b>不判样式名认不认识、不剥 {@code &$…-} 码</b>：那是解析器/渲染器的事
 *       （本类保持原样文本，正是为了让渲染器还能按老路解析）；</li>
 *   <li><b>不造 {@code Component}</b>：那要 MC，本类要能离线跑。</li>
 * </ul>
 */
public final class ShanhaiLoreSegmenter {

    /** 段的种类。<b>只有两种</b>：交给我们的渲染器，或者交给原版。 */
    public enum Kind {
        /** 山海段：{@code &$…-正文&r}，<b>原样文本</b>（含样式码）交给我们的渲染器。 */
        MOUNTAIN,
        /** 原版段：原样子串（可能含 {@code §} 码），整段交给原版。 */
        VANILLA
    }

    /**
     * 一个绘制段。
     *
     * @param kind     {@link Kind#MOUNTAIN} / {@link Kind#VANILLA}
     * @param text     <b>原始子串</b>（逐字节取自入参，一个字符都不改；两段拼回去 == 原行）
     * @param degraded {@code true} = 本来是山海段，因为<b>段内出现 {@code §}</b> 而降级成原版段
     *                 （{@link Kind#VANILLA} 的一支；调用方据此打 WARN）
     */
    public record Seg(Kind kind, String text, boolean degraded) {

        /** 这一段该由我们的渲染器接管吗。 */
        public boolean mountain() {
            return kind == Kind.MOUNTAIN;
        }
    }

    /** 我们那一段的起始标记（与 {@code ShanhaiTextParser#SEGMENT_MARK} 同一个串，<b>刻意各写一份常量</b>：本类不许依赖解析器，离线也要能编译）。 */
    public static final String SEGMENT_MARK = "&$";

    /** 正文还原符（与 {@code ShanhaiTextParser#RESTORE_MARKER} 同一个串）。 */
    public static final String RESTORE_MARKER = "&r";

    /** 原版样式码字符 U+00A7（唯一触发「段内降级」的字符）。 */
    public static final char SECTION_SIGN = '\u00A7';

    private ShanhaiLoreSegmenter() {}

    /**
     * 把<b>一行</b>切成若干段（规则见类注释 §2）。
     *
     * @param line 一行文本（<b>已经</b>去掉 {@code [s]} / {@code [c]} 行首标记）
     * @return <b>不可变</b>列表；<b>不产出空段</b>；{@code null}/空串 ⇒ 空表。
     *         全部段文本按顺序拼接 == {@code line}（硬不变量）
     */
    public static List<Seg> splitSegments(String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        return rawSplit(line);
    }

    /** 切分本体（不进缓存：lore 每帧都要用，但一行只有几十字节；缓存带来的失效问题比省下的这点开销更贵）。 */
    private static List<Seg> rawSplit(String line) {
        final int n = line.length();
        final List<Seg> out = new ArrayList<>(4);
        final StringBuilder vanilla = new StringBuilder();
        int i = 0;
        while (i < n) {
            final int mark = line.indexOf(SEGMENT_MARK, i);
            if (mark < 0) {
                vanilla.append(line, i, n);
                break;
            }
            vanilla.append(line, i, mark);
            if (vanilla.length() > 0) {
                out.add(new Seg(Kind.VANILLA, vanilla.toString(), false));
                vanilla.setLength(0);
            }
            final int afterMark = mark + SEGMENT_MARK.length();
            final int restore = line.indexOf(RESTORE_MARKER, afterMark);
            final int nextMark = line.indexOf(SEGMENT_MARK, afterMark);
            final int end;
            if (restore >= 0 && (nextMark < 0 || restore < nextMark)) {
                end = restore + RESTORE_MARKER.length();   // &r 归本段（含）
            } else if (nextMark >= 0) {
                end = nextMark;                            // 下一个 &$ 抢先 ⇒ 本段到它之前
            } else {
                end = n;                                   // 都没有 ⇒ 到行尾
            }
            final String text = line.substring(mark, end);
            final boolean degraded = text.indexOf(SECTION_SIGN) >= 0;
            out.add(new Seg(degraded ? Kind.VANILLA : Kind.MOUNTAIN, text, degraded));
            i = end;
        }
        if (vanilla.length() > 0) {
            out.add(new Seg(Kind.VANILLA, vanilla.toString(), false));
        }
        return List.copyOf(out);
    }
}
