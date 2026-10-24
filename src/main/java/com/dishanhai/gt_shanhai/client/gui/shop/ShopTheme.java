package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 商店界面主题（山海署名，客户端）：只管「结构色」——外框、底板、盒子、格子、按钮、滚动条轨道。
 * 文字色（金/青/绿/红这类语义色）不随主题变，买卖成败的提示色在两套主题下含义一致。
 *
 * <p>{@link #CLASSIC} 是原来的 KE 金框配色，数值与改动前逐个相同；{@link #GLASS} 沿用山海样板生成器的
 * 动态玻璃 token（{@link GlassPalette}）：半透明分层底板 + 左上受光亮边 + 根板外缘柔影，
 * hover/按下的弹簧动效两套主题共用。选哪套由 {@link ClientShopUiSettings#shopTheme()} 决定，只存在这台客户端。</p>
 */
public final class ShopTheme {

    /** 原 KE 金框配色。 */
    public static final ShopTheme CLASSIC = new ShopTheme(false,
            -22016, -7710208, -267382768, -266724838, -300674028, -300476649, -299555547, -299421158,
            -14935012, -12303292, -15967888, -14935012, -13661393);

    /**
     * 动态玻璃：色相沿用样板生成器（{@link GlassPalette}），但底板改成低不透明度——面板后面先做实时高斯
     * （{@link #backdrop}），底板只负责压一层冷色调，世界的色块能透出来。卡片与按钮略实一些保证图标和字的对比度。
     */
    public static final ShopTheme GLASS = new ShopTheme(true,
            GlassPalette.ACCENT, GlassPalette.BUTTON_EDGE,
            0x590E1726, // 根板 35%
            0x26091221, // 内层显示区 15%
            0x260B1424, // 网格/盒子底 15%
            0x99111B2D, // 商品卡 60%
            0xB319324A, // 卡片悬停 70%
            0xCC1A3549, // 选中卡 80%
            0xA6183049, // 按钮 65%
            0xD9214A6B, // 按钮悬停 85%
            GlassPalette.ROW_EDGE,
            0x801F3347, // 数字栏底 / 滚动条轨道 50%
            GlassPalette.ROW_EDGE);

    public final boolean glass;
    /** 亮框：选中格、选中页签、按钮 hover 边、滚动条把手。经典=金，玻璃=青。 */
    public final int frame;
    /** 暗框：面板外框、盒子边、按钮常态边。 */
    public final int frameDark;
    public final int panelBg;
    public final int panelInner;
    public final int boxBg;
    public final int rowBg;
    public final int rowHover;
    public final int selectBg;
    public final int buttonBg;
    public final int buttonHover;
    public final int barBorder;
    /** 数字栏底 / 滚动条轨道。 */
    public final int barBg;
    /** 商品格常态边（经典=暗绿）。 */
    public final int cellEdge;

    private ShopTheme(boolean glass, int frame, int frameDark, int panelBg, int panelInner, int boxBg,
                      int rowBg, int rowHover, int selectBg, int buttonBg, int buttonHover,
                      int barBorder, int barBg, int cellEdge) {
        this.glass = glass;
        this.frame = frame;
        this.frameDark = frameDark;
        this.panelBg = panelBg;
        this.panelInner = panelInner;
        this.boxBg = boxBg;
        this.rowBg = rowBg;
        this.rowHover = rowHover;
        this.selectBg = selectBg;
        this.buttonBg = buttonBg;
        this.buttonHover = buttonHover;
        this.barBorder = barBorder;
        this.barBg = barBg;
        this.cellEdge = cellEdge;
    }

    public static ShopTheme current() {
        return ClientShopUiSettings.glassTheme() ? GLASS : CLASSIC;
    }

    /**
     * 带边框的盒子。玻璃主题下四条边各自画、内芯半透明（内芯下面是背后的世界而不是边框色），
     * 再补左上受光亮边，同 {@code GlassPanelTexture} 的 plate。
     */
    public void box(GuiGraphics g, int x, int y, int w, int h, int border, int fill) {
        if (!glass) {
            g.fill(x, y, x + w, y + h, border);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
            return;
        }
        if (w < 2 || h < 2) {
            g.fill(x, y, x + w, y + h, border);
            return;
        }
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y + 1, x + 1, y + h - 1, border);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        if (w > 4 && h > 4) {
            g.fill(x + 1, y + 1, x + w - 1, y + 2, GlassPalette.LIT);
            g.fill(x + 1, y + 2, x + 2, y + h - 1, GlassPalette.LIT_SOFT);
        }
    }

    // ===== 按钮/页签文字：玻璃主题下改用生成器色板，去掉 § 色码与文字阴影 =====

    /** 玻璃底上的语义色（MC 的 16 色在深蓝玻璃上不是太暗就是太艳，这里换成同色相的浅色）。 */
    private static final int GLASS_RED = 0xFFFF9A9A;
    private static final int GLASS_GOLD = 0xFFFFD98A;
    private static final int GLASS_PURPLE = 0xFFE2B3FF;

    /** 按钮/页签上显示的文字：经典原样；玻璃去掉 § 格式码（颜色改由 {@link #ink} 给）。 */
    public String label(String text) {
        if (!glass || text == null || text.indexOf('§') < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 按钮文字色：经典返回 fallback（§ 色码自己生效）；玻璃按标签第一个色码换成玻璃色板里的对应色，
     * 「关」态常用的 §8/§7 映射成仍清晰可读的灰蓝，不再是深底上的深灰。
     */
    public int ink(String text, int fallback) {
        if (!glass) return fallback;
        char code = 'f';
        if (text != null) {
            for (int i = 0; i + 1 < text.length(); i++) {
                if (text.charAt(i) != '§') continue;
                char c = Character.toLowerCase(text.charAt(i + 1));
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) { code = c; break; }
            }
        }
        return switch (code) {
            case '0', '8' -> GlassPalette.TEXT_DIM;
            case '7' -> GlassPalette.TEXT_MUTED;
            case 'a', '2' -> GlassPalette.OK;
            case 'b', '3', '9', '1' -> GlassPalette.ACCENT;
            case 'c', '4' -> GLASS_RED;
            case 'e', '6' -> GLASS_GOLD;
            case 'd', '5' -> GLASS_PURPLE;
            default -> GlassPalette.TEXT;
        };
    }

    /** 页签/普通文字的常态、悬停、选中色（玻璃主题；经典调用方仍用自己的灰/白/金）。 */
    public int tabIdle() { return GlassPalette.TEXT_MUTED; }
    public int tabHover() { return GlassPalette.TEXT; }
    public int tabActive() { return GlassPalette.ACCENT; }

    /** 居中画按钮文字：经典带阴影（原样）；玻璃无阴影，同样板生成器。 */
    public void drawCentered(GuiGraphics g, net.minecraft.client.gui.Font font, String text, int cx, int y, int color) {
        if (!glass) {
            g.drawCenteredString(font, text, cx, y, color);
            return;
        }
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    /** 原版界面背景那层 75%~80% 的黑幕会把玻璃盖成实心；玻璃主题换成很淡的一层冷色。返回 false 表示调用方照旧画原版背景。 */
    public boolean renderBackdropDim(GuiGraphics g, int width, int height) {
        if (!glass) return false;
        g.fillGradient(0, 0, width, height, 0x30101828, 0x50101828);
        return true;
    }

    /** 毛玻璃：面板画上去之前，把 (x,y,w,h)（当前 pose 下的 GUI 坐标）后面的画面实时糊掉。经典主题不做。 */
    public void backdrop(GuiGraphics g, int x, int y, int w, int h) {
        backdrop(g, x, y, w, h, 1f);
    }

    /** 同上，strength 0..1：二级界面开关时跟着弹簧淡入淡出。 */
    public void backdrop(GuiGraphics g, int x, int y, int w, int h, float strength) {
        if (!glass) return;
        com.dishanhai.gt_shanhai.client.gui.glass.GlassBlur.backdrop(g,
                new com.dishanhai.gt_shanhai.client.gui.glass.GuiRect(x, y, w, h), strength);
    }

    /** 区域分隔的模糊半径（虚拟 GUI 单位）：和根板同量级，区域里只剩色块。 */
    private static final float REGION_BLUR_RADIUS = 9f;
    /** 按钮/页签的模糊半径：按钮小，半径过大整块只剩一个平均色，6 刚好糊掉背后的字。 */
    private static final float CONTROL_BLUR_RADIUS = 6f;

    /**
     * 区域分隔（余额栏、网格区、详情区）的毛玻璃：在根板之上再糊一层并压暗去色，必须在区域底盒之前调。
     * 采样夹在区域内，不会吸进相邻区域的边框和字。经典主题不做。
     */
    public void regionBackdrop(GuiGraphics g, int x, int y, int w, int h) {
        if (!glass) return;
        com.dishanhai.gt_shanhai.client.gui.glass.GlassBlur.backdrop(g,
                new com.dishanhai.gt_shanhai.client.gui.glass.GuiRect(x, y, w, h), 1f,
                ShopGlassStyle.REGION, REGION_BLUR_RADIUS);
    }

    /** 同上，strength 0..1：飞行卡片这类随动画淡入淡出的元素用。 */
    public void controlBackdrop(GuiGraphics g, int x, int y, int w, int h, float strength) {
        if (!glass) return;
        com.dishanhai.gt_shanhai.client.gui.glass.GlassBlur.backdrop(g,
                new com.dishanhai.gt_shanhai.client.gui.glass.GuiRect(x, y, w, h), strength,
                ShopGlassStyle.CONTROL, CONTROL_BLUR_RADIUS);
    }

    /** 玻璃 tooltip 底：毛玻璃 + 高不透明度深蓝底 + 青色细边，文字压在上面要读得清（底比按钮更实）。 */
    public void tooltipPanel(GuiGraphics g, int x, int y, int w, int h) {
        controlBackdrop(g, x, y, w, h);
        box(g, x, y, w, h, GlassPalette.BUTTON_EDGE, 0xE60A1322);
    }

    /** 按钮/页签底的毛玻璃：比区域压得更深，保证按钮字的对比度。在按钮底盒之前、按钮动效的 pose 里调。 */
    public void controlBackdrop(GuiGraphics g, int x, int y, int w, int h) {
        if (!glass) return;
        com.dishanhai.gt_shanhai.client.gui.glass.GlassBlur.backdrop(g,
                new com.dishanhai.gt_shanhai.client.gui.glass.GuiRect(x, y, w, h), 1f,
                ShopGlassStyle.CONTROL, CONTROL_BLUR_RADIUS);
    }

    // ===== 子页面（货币中心 / 兑换中心 / 银会中心 / 确认弹窗）共用的三件套 =====

    /**
     * 子页面根板 + 内层显示区（内层给绝对坐标）。经典：原 KE 四层嵌套（暗金→金→暗底→内层），数值同改动前；
     * 玻璃：外缘柔影 → 毛玻璃底 → 低不透明度根板 → 内层显示区，同商店主界面。
     */
    public void rootPanel(GuiGraphics g, int x, int y, int w, int h, int ix, int iy, int iw, int ih) {
        rootPanel(g, x, y, w, h, ix, iy, iw, ih, frameDark, frame, GlassPalette.ROOT_EDGE);
    }

    /** 同上，自选外框（警告弹窗的红框）：经典用 outer/line 两层，玻璃用 glassEdge 当根板边。 */
    public void rootPanel(GuiGraphics g, int x, int y, int w, int h, int ix, int iy, int iw, int ih,
                          int outer, int line, int glassEdge) {
        if (glass) {
            rootShadow(g, x, y, w, h);
            backdrop(g, x, y, w, h);
            box(g, x, y, w, h, glassEdge, panelBg);
            box(g, ix, iy, iw, ih, GlassPalette.DISPLAY_EDGE, panelInner);
            return;
        }
        g.fill(x, y, x + w, y + h, outer);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, line);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, panelBg);
        g.fill(ix, iy, ix + iw, iy + ih, panelInner);
    }

    /** 区域底（列表区、详情区）。经典：原来的纯色块；玻璃：区域毛玻璃 + 细边 + 半透明底，分区一眼可见。 */
    public void region(GuiGraphics g, int x, int y, int w, int h) {
        region(g, x, y, w, h, boxBg);
    }

    /** 同上，自选底色（如会员档位行的悬停 {@link #rowBg}）。 */
    public void region(GuiGraphics g, int x, int y, int w, int h, int fill) {
        if (!glass) {
            g.fill(x, y, x + w, y + h, fill);
            return;
        }
        regionBackdrop(g, x, y, w, h);
        box(g, x, y, w, h, GlassPalette.ROW_EDGE, fill);
    }

    /**
     * 子页面按钮：先糊按钮背后（玻璃），再画底盒与居中文字。border/fill 由调用方按自己的 hover 规则算
     * （传本主题的 frameDark/frame/buttonBg/buttonHover），经典主题下与改动前逐像素一致。
     */
    public void button(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int h,
                       String text, int border, int fill, int textColor) {
        controlBackdrop(g, x, y, w, h);
        box(g, x, y, w, h, border, fill);
        drawCentered(g, font, label(text), x + w / 2, y + (h - 8) / 2, ink(text, textColor));
    }

    /** 根板外缘三圈渐淡的下偏柔影（玻璃主题才画）。 */
    public void rootShadow(GuiGraphics g, int x, int y, int w, int h) {
        if (!glass) return;
        for (int i = 1; i <= 3; i++) {
            int alpha = 0x30 >> (i - 1);
            g.fill(x - i, y - i + 2, x + w + i, y + h + i + 2, alpha << 24);
        }
    }
}
