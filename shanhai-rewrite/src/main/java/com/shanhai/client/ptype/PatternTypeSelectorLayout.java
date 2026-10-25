package com.shanhai.client.ptype;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.ToIntFunction;

/**
 * 选择器的全部几何与配色常量（<b>位置全部写成常量，允许后续微调</b>）。
 *
 * <h2>坐标口径</h2>
 * 所有数值都是 <b>GUI 缩放后</b>的坐标系，与 AE2 控件、{@code ScreenEvent} 里的鼠标坐标
 * 同一套（{@code ScreenEvent.Render.Post} 发生在 {@code Screen.renderWithTooltip} 之后，
 * 此时 pose 已经缩放过；{@code MouseButtonPressed/Scrolled} 给的也是同一套坐标）。
 *
 * <h2>胶囊挂哪</h2>
 * 首选挂在 AE2 窗口<b>左边缘外侧</b>（{@code guiLeft - CAPSULE_W - GAP}）——
 * 那里一定没有任何 AE2 控件，也就<b>绝不会压到模式按钮那一列</b>
 * （javap 实证那列其实在<b>右侧</b>：样式表 {@code pattern_encoding_terminal.json} 里
 * {@code modeTabButton0..3} 的 {@code left=173}，窗口宽 195）。
 * <p>空间不够时按 {@link #capsulePlacement} 的两级兜底退让，并在日志里说明用了哪一级。
 */
@OnlyIn(Dist.CLIENT)
public final class PatternTypeSelectorLayout {

    // ------------------------------------------------------------------ 胶囊

    /** 胶囊宽度（够放「配方类型：」+ 3~4 个汉字，超出截断）。 */
    public static final int CAPSULE_W = 106;
    /** 左边缘空间不足时允许压窄到的下限（再窄就只剩「配方类型…」，不如换个位置）。 */
    public static final int CAPSULE_MIN_W = 64;
    /** 胶囊高度。 */
    public static final int CAPSULE_H = 14;
    /** 胶囊与窗口左边缘的间距。 */
    public static final int CAPSULE_GAP = 3;
    /** 胶囊相对窗口左上角的下移量（贴着标题带下方）。 */
    public static final int CAPSULE_TOP_OFFSET = 19;

    // ------------------------------------------------------------------ 面板

    /**
     * 面板宽。
     *
     * <p>2026-10-25 由 190 加宽到 236：用户报「类型名被截成 两三个字 + …」。
     * 量出来的根因不是面板窄（见 {@link #rowLabels}），但 190 时行内只有 168px、
     * 英文 id 一占就更紧；加宽后行内可用宽度 = 236-12-4-1-4-3 = 212px
     * （≈23 个汉字），GT 全量 216 条里最长的名字也放得下。
     * <p>小分辨率仍安全：GUI 缩放后的屏幕宽最小按 320 算，236 < 320-8。
     */
    public static final int PANEL_W = 236;
    /** 面板高（屏幕太矮时会缩）。 */
    public static final int PANEL_H = 178;
    /** 面板高下限。 */
    public static final int PANEL_MIN_H = 96;
    /** 面板内边距。 */
    public static final int PAD = 6;
    /** 标题行高。 */
    public static final int TITLE_H = 11;
    /** 搜索框高。 */
    public static final int SEARCH_H = 13;
    /** 「清除」行高。 */
    public static final int CLEAR_H = 12;
    /** 列表行高。 */
    public static final int ROW_H = 11;
    /** 底部统计行高。 */
    public static final int FOOTER_H = 11;
    /** 滚动条宽。 */
    public static final int SCROLL_W = 4;
    /** 区块之间的缝隙。 */
    public static final int GAP = 3;

    // ------------------------------------------------------------------ 列表行的文字排布

    /** 行内文字左边距（相对行矩形）。 */
    public static final int ROW_TEXT_LEFT = 4;
    /** 行内文字右边距（相对行矩形右边界）。 */
    public static final int ROW_TEXT_RIGHT = 3;
    /** 中文名与右侧 id 之间<b>至少</b>留的空隙：不够就整条 id 不画，把宽度全给名字。 */
    public static final int ID_GAP = 8;

    // ------------------------------------------------------------------ 配色（跟着 AE2 的深底细边风格）

    public static final int COLOR_CAPSULE_BG = 0xFF101418;
    public static final int COLOR_CAPSULE_BG_UNSUPPORTED = 0xFF1A1012;
    public static final int COLOR_CAPSULE_BORDER = 0xFF4C5F6C;
    public static final int COLOR_CAPSULE_BORDER_UNSUPPORTED = 0xFF6B3A3A;
    public static final int COLOR_CAPSULE_TEXT = 0xFFE6E6E6;
    public static final int COLOR_CAPSULE_TEXT_UNSUPPORTED = 0xFFC08A8A;
    public static final int COLOR_CAPSULE_HOVER = 0x30FFFFFF;

    /**
     * 面板底色（🔴 <b>必须 alpha=0xFF</b>）。
     *
     * <p>用户报「面板是半透明的，后面的样板格 / 物品图标 / tooltip 会露出来」。
     * 原来这里是 {@code 0xF20B0F13}（alpha 242/255）⇒ 后面亮色图标会以 5% 透出来，
     * 在这么暗的底上肉眼可见。现在改成全不透明。
     * <p>⚠️ <b>只改 alpha 并不够</b>：{@code GuiGraphics.fill} 走 {@code RenderType.gui()}，
     * 它的深度状态是 {@code LEQUAL_DEPTH_TEST}（javap 实证，见报告 §2），
     * 而物品图标画在 z=+150、物品装饰 z=+200、tooltip z=+400，全都比我们的 z=0 更靠近镜头
     * ⇒ 即使 alpha=0xFF 也<u>过不了深度测试</u>，照样会被它们"打洞"穿过来。
     * 真正的修法在 {@code PatternTypeSelectorRenderer#beginOverlay}（清深度缓冲）。
     */
    public static final int COLOR_PANEL_BG = 0xFF0B0F13;
    /** 列表区底（比面板底再深一档，让"列表是凹进去的一块"看得出来；同样不透明）。 */
    public static final int COLOR_LIST_BG = 0xFF070A0D;
    /** 底部统计条底（不透明，保证文字不被列表/后面的东西压住）。 */
    public static final int COLOR_FOOTER_BG = 0xFF121A21;
    public static final int COLOR_PANEL_BORDER = 0xFF5A6E7C;
    public static final int COLOR_PANEL_BORDER_SOFT = 0xFF2B3841;
    public static final int COLOR_TITLE_TEXT = 0xFFD6E4EE;
    public static final int COLOR_BODY_TEXT = 0xFFE6E6E6;
    public static final int COLOR_DIM_TEXT = 0xFF8FA3B0;
    public static final int COLOR_ROW_HOVER = 0xFF1A2630;
    public static final int COLOR_ROW_SELECTED = 0xFF223D4E;
    public static final int COLOR_SEARCH_BG = 0xFF06090B;
    public static final int COLOR_SEARCH_BORDER = 0xFF44555F;
    public static final int COLOR_SEARCH_BORDER_FOCUS = 0xFF7FB4CC;
    public static final int COLOR_CARET = 0xFF8FE3FF;
    public static final int COLOR_CLEAR_TEXT = 0xFFE08878;
    public static final int COLOR_SCROLL_TRACK = 0x40000000;
    public static final int COLOR_SCROLL_THUMB = 0xFF55697A;
    public static final int COLOR_SCROLL_THUMB_HOVER = 0xFF7C93A5;

    private PatternTypeSelectorLayout() {}

    /** GUI 坐标下的矩形。 */
    public record Rect(int x, int y, int w, int h) {

        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }

    /** 胶囊落位 + 用的是哪一级兜底（写日志用）。 */
    public record Placement(Rect rect, String mode) {}

    /**
     * 胶囊落位。
     *
     * <ol>
     *   <li>{@code OUTSIDE_LEFT}：挂在窗口左边缘外侧（首选，零遮挡）；</li>
     *   <li>{@code OUTSIDE_LEFT_SHRUNK}：左边缘还有地方但不够 {@link #CAPSULE_W} ⇒
     *       压窄到 {@link #CAPSULE_MIN_W} 以上继续贴左边缘（文字自动截断）；</li>
     *   <li>{@code ABOVE}：窗口上方；</li>
     *   <li>{@code INSIDE_TOP}：窗口内左上角（最后一个兜底，会压住标题带，功能仍在）。</li>
     * </ol>
     * <p>渲染与命中判定<b>都调这一个方法</b>，所以压窄/兜底之后两边的矩形一定一致。
     * <p>实测（{@code temp\ptype\harness\LayoutCheck}）：1280x720 默认 GUI 缩放 3
     * （缩放后 426x240，窗口左边界 115）走 {@code OUTSIDE_LEFT}；1920x1080 缩放 2
     * （960x540，左边界 382）同样走 {@code OUTSIDE_LEFT}。
     */
    public static Placement capsulePlacement(Screen screen, int guiLeft, int guiTop) {
        return capsulePlacement(screen.width, screen.height, guiLeft, guiTop);
    }

    /**
     * 胶囊落位的<b>纯函数</b>版本（不依赖 {@code Screen} 实例）。
     *
     * <p>为什么单独开一个：离线自证装置（{@code temp\ptype\harness}）跑在裸 JVM 上，
     * 拿不到 {@code Screen}；把几何算术与 MC 类型解耦之后，预览图里画的矩形
     * <b>就是</b>游戏里会画的那一个（不是照公式重写一遍）。
     */
    public static Placement capsulePlacement(int screenW, int screenH, int guiLeft, int guiTop) {
        int outsideY = guiTop + CAPSULE_TOP_OFFSET;
        int spaceOnLeft = guiLeft - CAPSULE_GAP - 1;
        boolean verticalRoom = outsideY >= 1 && outsideY + CAPSULE_H <= screenH - 1;
        if (verticalRoom && spaceOnLeft >= CAPSULE_W) {
            return new Placement(new Rect(guiLeft - CAPSULE_W - CAPSULE_GAP, outsideY, CAPSULE_W, CAPSULE_H),
                    "OUTSIDE_LEFT");
        }
        if (verticalRoom && spaceOnLeft >= CAPSULE_MIN_W) {
            return new Placement(new Rect(guiLeft - spaceOnLeft - CAPSULE_GAP, outsideY, spaceOnLeft, CAPSULE_H),
                    "OUTSIDE_LEFT_SHRUNK");
        }
        int aboveY = guiTop - CAPSULE_H - 2;
        if (aboveY >= 1) {
            return new Placement(new Rect(clamp(guiLeft + 2, 1, Math.max(1, screenW - CAPSULE_W - 1)),
                    aboveY, CAPSULE_W, CAPSULE_H), "ABOVE");
        }
        return new Placement(new Rect(clamp(guiLeft + 2, 1, Math.max(1, screenW - CAPSULE_W - 1)),
                clamp(guiTop + 2, 1, Math.max(1, screenH - CAPSULE_H - 1)),
                CAPSULE_W, CAPSULE_H), "INSIDE_TOP");
    }

    /** 面板矩形：以屏幕为中心，双向 clamp 到屏幕内。 */
    public static Rect panel(Screen screen) {
        return panel(screen.width, screen.height);
    }

    /** 面板矩形的纯函数版本（见 {@link #capsulePlacement(int, int, int, int)} 的说明）。 */
    public static Rect panel(int screenW, int screenH) {
        int w = Math.min(PANEL_W, Math.max(120, screenW - 8));
        int h = Math.min(PANEL_H, Math.max(PANEL_MIN_H, screenH - 12));
        int x = clamp((screenW - w) / 2, 1, Math.max(1, screenW - w - 1));
        int y = clamp((screenH - h) / 2, 1, Math.max(1, screenH - h - 1));
        return new Rect(x, y, w, h);
    }

    /** 标题行。 */
    public static Rect title(Rect panel) {
        return new Rect(panel.x() + PAD, panel.y() + PAD, Math.max(1, panel.w() - PAD * 2), TITLE_H);
    }

    /** 搜索框（标题行下方）。 */
    public static Rect searchBox(Rect panel) {
        Rect title = title(panel);
        return new Rect(title.x(), title.bottom() + GAP, title.w(), SEARCH_H);
    }

    /** 「清除」行（搜索框下方，固定不滚动）。 */
    public static Rect clearRow(Rect panel) {
        Rect search = searchBox(panel);
        return new Rect(search.x(), search.bottom() + GAP, search.w(), CLEAR_H);
    }

    /** 可滚动列表区（「清除」行下方，底部统计行上方）。 */
    public static Rect listArea(Rect panel) {
        int top = clearRow(panel).bottom() + 1;
        int bottom = panel.bottom() - PAD - FOOTER_H;
        return new Rect(panel.x() + PAD, top, Math.max(1, panel.w() - PAD * 2), Math.max(ROW_H, bottom - top));
    }

    /** 底部统计行。 */
    public static Rect footer(Rect panel) {
        return new Rect(panel.x() + PAD, panel.bottom() - PAD - FOOTER_H,
                Math.max(1, panel.w() - PAD * 2), FOOTER_H);
    }

    /** 滚动条轨道（列表右侧）。 */
    public static Rect scrollTrack(Rect listArea) {
        return new Rect(listArea.right() - SCROLL_W, listArea.y(), SCROLL_W, listArea.h());
    }

    /** 列表区能放几行（至少 1）。 */
    public static int visibleRows(Rect listArea) {
        return Math.max(1, listArea.h() / ROW_H);
    }

    /** 第 {@code index}（0 基，相对可见窗口）行的矩形。 */
    public static Rect row(Rect listArea, int index) {
        return new Rect(listArea.x(), listArea.y() + index * ROW_H, listArea.w() - SCROLL_W - 1, ROW_H);
    }

    /** 鼠标落在列表第几行（不在列表里返回 -1）。 */
    public static int rowAt(Rect listArea, double mouseX, double mouseY) {
        if (!listArea.contains(mouseX, mouseY)) {
            return -1;
        }
        int index = (int) ((mouseY - listArea.y()) / ROW_H);
        return index >= 0 && index < visibleRows(listArea) ? index : -1;
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    // ------------------------------------------------------------------ 行的文字（名字优先）

    /**
     * 一行的两段文字：{@code name} = 左边要画的中文名（可能被截断），{@code id} = 右边要画的
     * {@code ns:path}（{@code null} = 这一行<b>不画</b> id）。
     */
    public record RowLabels(String name, String id) {}

    /**
     * 决定一行怎么排 —— <b>这里就是「两个字 + …」那个 bug 的修法</b>。
     *
     * <h2>改前为什么只显示「世…」</h2>
     * 旧代码在渲染层直接算：
     * <pre>
     *   int idWidth  = font.width(entry.id().toString());      // 例如 gtceu:worldline_sampling ≈ 137px
     *   int nameRoom = row.w() - 6 - (idWidth + 6);            // 173-6-143 = 24 → max(20, 24) = 24px
     *   String nameText = trim(font, entry.name(), Math.max(20, nameRoom));   // 只剩 20px ⇒ "世…"
     *   if (idWidth + 6 &lt; row.w() / 2) { 画 id }                  // 137+6 &gt; 86 ⇒ id 根本没画
     * </pre>
     * ⇒ <b>名字的可用宽度按「要给 id 留位」算，可是 id 后来因为放不下又没画</b>：
     * 78% 的行宽被一条<u>看不见</u>的 id 占掉，名字只剩 20px（两个字 + 省略号）。
     * 既不是"按固定字符数截断"，也不是"用错字体量中文"——
     * {@code font.width} 量出来的宽度是对的，只是<u>预留</u>给了一个不画的东西。
     *
     * <h2>现在的规则（名字绝对优先）</h2>
     * <ol>
     *   <li>{@code name} 与 {@code id} 相同（名字就是 {@code ns:path}）⇒ 只画一次，不重复；</li>
     *   <li>名字 + {@link #ID_GAP} + id 都放得下 ⇒ 名字靠左、id 靠右（两个都完整）；</li>
     *   <li>放不下 ⇒ <b>id 整条不画</b>，名字独占整行可用宽度；</li>
     *   <li>名字自己就超过整行可用宽度（只有第三方超长名会这样）⇒ 按<b>像素</b>截断 + {@code …}。</li>
     * </ol>
     *
     * @param width 字符串 → 像素宽（游戏里传 {@code font::width}；离线装置传同一套字符宽度模型）
     */
    public static RowLabels rowLabels(String name, String id, int rowWidth, ToIntFunction<String> width) {
        String safeId = id == null || id.isEmpty() ? null : id;
        String safeName = name == null || name.isEmpty() ? (safeId == null ? "" : safeId) : name;
        int available = rowWidth - ROW_TEXT_LEFT - ROW_TEXT_RIGHT;
        if (available <= 0) {
            return new RowLabels("", null);
        }
        if (safeName.equals(safeId)) {
            return new RowLabels(trim(safeName, available, width), null);
        }
        int nameWidth = width.applyAsInt(safeName);
        if (nameWidth > available) {
            // 名字自己就放不下 ⇒ 只画名字（截断），不跟 id 抢
            return new RowLabels(trim(safeName, available, width), null);
        }
        if (safeId != null && nameWidth + ID_GAP + width.applyAsInt(safeId) <= available) {
            return new RowLabels(safeName, safeId);
        }
        return new RowLabels(safeName, null);
    }

    /**
     * 按<b>像素</b>宽度截断 + 末尾补 {@code …}（放得下就原样返回）。
     *
     * <p>和旧实现的关键差别：边界判断用 {@code width.applyAsInt(候选串)} 反复核，
     * 而不是"逐字累加 + 估计"—— 后者在比例字体上会被字距/空格吃掉几像素。
     * 宽度函数是注入的，所以离线装置能用<b>同一段代码</b>验证（游戏里是真 {@code Font}）。
     */
    public static String trim(String text, int maxWidth, ToIntFunction<String> width) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (width.applyAsInt(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = width.applyAsInt(ellipsis);
        if (ellipsisWidth >= maxWidth) {
            return ellipsis;
        }
        int budget = maxWidth - ellipsisWidth;
        StringBuilder builder = new StringBuilder(text.length());
        int end = 0;
        while (end < text.length()) {
            int next = end + Character.charCount(text.codePointAt(end));
            if (width.applyAsInt(text.substring(0, next)) > budget) {
                break;
            }
            builder.append(text, end, next);
            end = next;
        }
        return builder.append(ellipsis).toString();
    }
}
