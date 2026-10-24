package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.Supplier;

/**
 * 商店初始化向导（山海署名，客户端）：这台客户端第一次打开商店时先弹出，三页功能介绍 + 一页偏好设置。
 *
 * <p>偏好页只放最影响观感的几项：界面主题（点选即刻整页预览）、减少动效、商店音效、记住选取与搜索。
 * 「完成」把选择存进 {@link ClientShopUiSettings} 并记下已完成；「跳过」/ESC 只记已完成，不改任何设置。
 * 之后可在「商店设置」底栏的「新手引导」重看。所有文字按宽度换行，放不下就往下占行，不截断。</p>
 */
public class ShopWelcomeScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;

    private static final int TARGET_W = 380;
    private static final int TARGET_H = 280;
    private static final int HEADER_H = 22;
    private static final int FOOTER_H = 30;
    private static final int PAD = 14;
    private static final int BTN_H = 16;
    private static final int CARD_H = 40;

    private static final String[] PAGE_TITLES = {"欢迎来到山海商店", "钱包、货币与银行", "背包、AE 与设置"};
    private static final String[][][] PAGE_ITEMS = {
            {
                    {"看详情与购买", "左键点商品卡片，右侧展开详情；改好次数再点确认。顶部「购买 / 出售」页签切换方向。"},
                    {"购物车", "Ctrl+左键把商品加进购物车，顶栏「购物车」里可以改数量、一次结算。"},
                    {"右键菜单与收藏", "右键商品可以收藏，顶栏「★收藏」只看收藏。搜索词和选中的商品关店后可以记住。"},
                    {"周期限购与限时特惠", "限购商品会写明本周期已购、剩余次数和刷新倒计时；限时特惠会显示折扣和剩余时间。"},
            },
            {
                    {"货币中心", "把背包里的货币存进钱包，或按汇率换成另一种币，也能和星火互转。"},
                    {"兑换中心", "按兑换条目付出物品、流体或星火，换取另一侧；可以正向，也可以反向。"},
                    {"银会中心", "购买永久会员折扣（一次买断，不会过期）；银行页可以办理定期存款和贷款。"},
                    {"充值全部", "顶栏一键把背包里的货币全部存进钱包余额。"},
            },
            {
                    {"精妙背包", "开启后，扣款和交货优先走身上穿戴的精妙背包。"},
                    {"AE 模式", "开启后，交易优先走已绑定的在线 AE 网络；没有绑定在线的商店终端或 FTBQ 提交器时开不了。"},
                    {"商店设置", "卡片大小、获取预览模式、动效、音效和主题随时可以改；设置页底栏的「新手引导」可以重看本引导。"},
            },
    };
    private static final int PAGE_SETUP = PAGE_ITEMS.length;
    private static final int PAGE_COUNT = PAGE_ITEMS.length + 1;

    private static final String[] TOGGLE_HINTS = {
            "§7按钮不缩放、不抖、不走流光，也不画点击波纹；成交结果的颜色闪保留",
            "§7点击、步进连发与成交结果的提示音，跟随原版主音量",
            "§7关店再打开时还原上次的搜索词和选中的商品",
    };

    private final Supplier<Screen> next;
    /** 预览（测试命令）：结束时不写「已完成」标记，其余照常。 */
    private final boolean preview;
    private final Spring openMotion = Spring.opening(Spring.PANEL);
    private int page;
    private boolean glassTheme = ClientShopUiSettings.glassTheme();
    private boolean reduceMotion = ClientShopUiSettings.reduceMotion();
    private boolean uiSounds = ClientShopUiSettings.uiSounds();
    private boolean rememberView = ClientShopUiSettings.rememberShopView();
    private int left, top, panelWidth, panelHeight;

    /** @param next 向导结束后要去的界面（首次打开=商店本体，从设置页重看=回设置页）。 */
    public ShopWelcomeScreen(Supplier<Screen> next) {
        this(next, false);
    }

    public ShopWelcomeScreen(Supplier<Screen> next, boolean preview) {
        super(Component.literal("商店初始化"));
        this.next = next;
        this.preview = preview;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    private ShopTheme theme() {
        return glassTheme ? ShopTheme.GLASS : ShopTheme.CLASSIC;
    }

    @Override
    protected void initScaled() {
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);
        left = (vWidth - panelWidth) / 2;
        top = (vHeight - panelHeight) / 2;
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        if (!theme().renderBackdropDim(g, this.width, this.height)) super.renderBackground(g);
    }

    // ===== 几何 =====
    private int contentX() { return left + PAD; }
    private int contentW() { return panelWidth - PAD * 2; }
    private int contentTop() { return top + HEADER_H + 10; }
    private int footerY() { return top + panelHeight - FOOTER_H + 6; }
    private int skipX() { return left + PAD; }
    private int nextW() { return 70; }
    private int nextX() { return left + panelWidth - PAD - nextW(); }
    private int prevX() { return nextX() - 6 - 60; }
    private int cardW() { return (contentW() - 8) / 2; }
    private int cardX(int i) { return contentX() + i * (cardW() + 8); }
    private int cardY() { return contentTop() + 12; }

    private int wrappedLines(String text) {
        return Math.max(1, this.font.split(Component.literal(text), contentW()).size());
    }

    private int toggleRowsTop() { return cardY() + CARD_H + 14; }

    /** 第 i 个开关按钮的 Y：每行 = 按钮 + 换行后的说明 + 间距。 */
    private int toggleY(int i) {
        int y = toggleRowsTop();
        for (int k = 0; k < i; k++) y += BTN_H + wrappedLines(TOGGLE_HINTS[k]) * 10 + 8;
        return y;
    }

    // ===== 渲染 =====
    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        openMotion.update(System.currentTimeMillis());
        float scale = GuiMotion.panelScale(openMotion.value());
        boolean opening = scale < 0.9995f;
        if (opening) {
            g.pose().pushPose();
            GuiRenderUtil.scaleAt(g, left + panelWidth / 2f, top + panelHeight / 2f, scale);
        }
        try {
            drawPanel(g, mx, my);
        } finally {
            if (opening) g.pose().popPose();
        }
    }

    private void drawPanel(GuiGraphics g, int mx, int my) {
        ShopTheme t = theme();
        t.rootPanel(g, left, top, panelWidth, panelHeight,
                left + 5, top + HEADER_H, panelWidth - 10, panelHeight - HEADER_H - 5);
        int head = t.glass ? GlassPalette.TEXT_HEAD : GOLD;
        g.drawString(this.font, "商店初始化", left + 10, top + 7, head, !t.glass);
        String pageNo = (page + 1) + " / " + PAGE_COUNT;
        g.drawString(this.font, pageNo, left + panelWidth - 10 - this.font.width(pageNo), top + 7,
                t.glass ? GlassPalette.TEXT_MUTED : GRAY, !t.glass);

        if (page == PAGE_SETUP) drawSetupPage(g, t, mx, my);
        else drawIntroPage(g, t);

        drawFooter(g, t, mx, my);
    }

    private void drawIntroPage(GuiGraphics g, ShopTheme t) {
        int accent = t.glass ? GlassPalette.ACCENT : GOLD;
        int body = t.glass ? GlassPalette.TEXT : WHITE;
        int y = contentTop();
        g.drawString(this.font, PAGE_TITLES[page], contentX(), y, accent, !t.glass);
        y += 16;
        for (String[] item : PAGE_ITEMS[page]) {
            g.drawString(this.font, item[0], contentX(), y, t.glass ? GlassPalette.TEXT_HEAD : GOLD, !t.glass);
            y += 11;
            y = drawWrapped(g, item[1], contentX() + 6, y, contentW() - 6, body, !t.glass) + 7;
        }
    }

    private void drawSetupPage(GuiGraphics g, ShopTheme t, int mx, int my) {
        int accent = t.glass ? GlassPalette.ACCENT : GOLD;
        g.drawString(this.font, "先选几个偏好（之后都能在「商店设置」里改，只存在这台客户端）",
                contentX(), contentTop(), accent, !t.glass);
        drawThemeCard(g, 0, ShopTheme.CLASSIC, "经典金框", "KE 风格金色描边，实色底", !glassTheme, mx, my);
        drawThemeCard(g, 1, ShopTheme.GLASS, "动态玻璃", "半透明毛玻璃，同山海样板生成器", glassTheme, mx, my);

        String[] labels = {
                reduceMotion ? "§a减少动效: 开" : "§8减少动效: 关",
                uiSounds ? "§a商店音效: 开" : "§8商店音效: 关",
                rememberView ? "§a记住选取与搜索: 开" : "§8记住选取与搜索: 关",
        };
        for (int i = 0; i < labels.length; i++) {
            int y = toggleY(i);
            drawBtn(g, t, contentX(), y, contentW(), BTN_H, labels[i], mx, my);
            drawWrapped(g, TOGGLE_HINTS[i], contentX(), y + BTN_H + 3, contentW(),
                    t.glass ? GlassPalette.TEXT_MUTED : WHITE, !t.glass);
        }
    }

    /** 主题点选卡：用它自己代表的主题画一块小底板，选中的边框亮起并标「已选」。 */
    private void drawThemeCard(GuiGraphics g, int i, ShopTheme preview, String name, String sub,
                               boolean selected, int mx, int my) {
        int x = cardX(i);
        int y = cardY();
        int w = cardW();
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, CARD_H);
        int border = selected || hover ? preview.frame : preview.frameDark;
        if (preview.glass) preview.backdrop(g, x, y, w, CARD_H);
        preview.box(g, x, y, w, CARD_H, border, selected ? preview.selectBg : preview.rowBg);
        g.drawString(this.font, name, x + 8, y + 7, preview.glass ? GlassPalette.TEXT_HEAD : GOLD, !preview.glass);
        g.drawString(this.font, sub, x + 8, y + 22, preview.glass ? GlassPalette.TEXT_MUTED : GRAY, !preview.glass);
        if (selected) {
            String mark = "已选";
            g.drawString(this.font, mark, x + w - 8 - this.font.width(mark), y + 7,
                    preview.glass ? GlassPalette.OK : GOLD, !preview.glass);
        }
    }

    private void drawFooter(GuiGraphics g, ShopTheme t, int mx, int my) {
        int y = footerY();
        drawBtn(g, t, skipX(), y, 60, BTN_H, "§7跳过", mx, my);
        if (page > 0) drawBtn(g, t, prevX(), y, 60, BTN_H, "§e上一页", mx, my);
        drawBtn(g, t, nextX(), y, nextW(), BTN_H, page == PAGE_COUNT - 1 ? "§a完成" : "§b下一页", mx, my);
    }

    private void drawBtn(GuiGraphics g, ShopTheme t, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        t.button(g, this.font, x, y, w, h, label, hover && t.glass ? t.frame : t.frameDark,
                hover ? t.buttonHover : t.buttonBg, WHITE);
    }

    /** 按宽度换行逐行画，返回画完后的下一行 Y；放不下就往下占行，不截断。 */
    private int drawWrapped(GuiGraphics g, String text, int x, int y, int w, int color, boolean shadow) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), w);
        for (FormattedCharSequence line : lines) {
            g.drawString(this.font, line, x, y, color, shadow);
            y += 10;
        }
        return y;
    }

    // ===== 交互 =====
    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (btn != 0) return super.universalMouseClicked(mx, my, btn);
        int fy = footerY();
        if (GuiRenderUtil.isHovering(mx, my, skipX(), fy, 60, BTN_H)) {
            finish(false);
            return true;
        }
        if (page > 0 && GuiRenderUtil.isHovering(mx, my, prevX(), fy, 60, BTN_H)) {
            page--;
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, nextX(), fy, nextW(), BTN_H)) {
            advance();
            return true;
        }
        if (page == PAGE_SETUP) {
            if (GuiRenderUtil.isHovering(mx, my, cardX(0), cardY(), cardW(), CARD_H)) {
                glassTheme = false;
                return true;
            }
            if (GuiRenderUtil.isHovering(mx, my, cardX(1), cardY(), cardW(), CARD_H)) {
                glassTheme = true;
                return true;
            }
            for (int i = 0; i < TOGGLE_HINTS.length; i++) {
                if (GuiRenderUtil.isHovering(mx, my, contentX(), toggleY(i), contentW(), BTN_H)) {
                    switch (i) {
                        case 0 -> reduceMotion = !reduceMotion;
                        case 1 -> uiSounds = !uiSounds;
                        default -> rememberView = !rememberView;
                    }
                    return true;
                }
            }
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // GLFW: 右 262 / 左 263 / 回车 257 / 小键盘回车 335
        if (keyCode == 262 || keyCode == 257 || keyCode == 335) {
            advance();
            return true;
        }
        if (keyCode == 263 && page > 0) {
            page--;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void advance() {
        if (page >= PAGE_COUNT - 1) finish(true);
        else page++;
    }

    /** ESC 等同「跳过」：只记已完成，不改设置。 */
    @Override
    public void onClose() {
        finish(false);
    }

    private void finish(boolean save) {
        if (save) {
            ClientShopUiSettings.setShopTheme(glassTheme ? ClientShopUiSettings.THEME_GLASS : ClientShopUiSettings.THEME_CLASSIC);
            ClientShopUiSettings.setFeedbackPrefs(reduceMotion, uiSounds);
            ClientShopUiSettings.setRememberShopView(rememberView);
        }
        if (!preview) ClientShopUiSettings.setSetupDone(true);
        Minecraft.getInstance().setScreen(next.get());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
