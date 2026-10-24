package com.shanhai.client.ptype;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

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

    /** 面板宽。 */
    public static final int PANEL_W = 190;
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

    // ------------------------------------------------------------------ 配色（跟着 AE2 的深底细边风格）

    public static final int COLOR_CAPSULE_BG = 0xF0101418;
    public static final int COLOR_CAPSULE_BG_UNSUPPORTED = 0xF01A1012;
    public static final int COLOR_CAPSULE_BORDER = 0xFF4C5F6C;
    public static final int COLOR_CAPSULE_BORDER_UNSUPPORTED = 0xFF6B3A3A;
    public static final int COLOR_CAPSULE_TEXT = 0xFFE6E6E6;
    public static final int COLOR_CAPSULE_TEXT_UNSUPPORTED = 0xFFC08A8A;
    public static final int COLOR_CAPSULE_HOVER = 0x30FFFFFF;

    public static final int COLOR_PANEL_BG = 0xF20B0F13;
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
        int outsideY = guiTop + CAPSULE_TOP_OFFSET;
        int spaceOnLeft = guiLeft - CAPSULE_GAP - 1;
        boolean verticalRoom = outsideY >= 1 && outsideY + CAPSULE_H <= screen.height - 1;
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
            return new Placement(new Rect(clamp(guiLeft + 2, 1, Math.max(1, screen.width - CAPSULE_W - 1)),
                    aboveY, CAPSULE_W, CAPSULE_H), "ABOVE");
        }
        return new Placement(new Rect(clamp(guiLeft + 2, 1, Math.max(1, screen.width - CAPSULE_W - 1)),
                clamp(guiTop + 2, 1, Math.max(1, screen.height - CAPSULE_H - 1)),
                CAPSULE_W, CAPSULE_H), "INSIDE_TOP");
    }

    /** 面板矩形：以屏幕为中心，双向 clamp 到屏幕内。 */
    public static Rect panel(Screen screen) {
        int w = Math.min(PANEL_W, Math.max(120, screen.width - 8));
        int h = Math.min(PANEL_H, Math.max(PANEL_MIN_H, screen.height - 12));
        int x = clamp((screen.width - w) / 2, 1, Math.max(1, screen.width - w - 1));
        int y = clamp((screen.height - h) / 2, 1, Math.max(1, screen.height - h - 1));
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
}
