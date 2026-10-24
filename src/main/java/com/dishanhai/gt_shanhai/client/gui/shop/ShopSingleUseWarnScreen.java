package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SDA / 无限盘「买太多」警告框（山海署名，客户端）：这类盘一般买一次就够，一次买到
 * {@link com.dishanhai.gt_shanhai.common.shop.ShopEntry#SINGLE_USE_WARN_AMOUNT} 份以上时，
 * 点购买后先弹本框，玩家点「仍然购买」才继续（{@code onConfirm}），取消或 Esc 回商店什么都不发。
 * 详情页购买与购物车结算共用；仿 {@link ShopAutoCraftConfirmScreen} 的纯文本行布局。
 *
 * <p>长行换行不截断；行多时可滚轮、拖右侧滚动条或用 ↑↓/PageUp/PageDown/Home/End 翻；Enter 继续购买、Esc 取消。
 * 按钮反馈走 {@link ShopDialogUi}。</p>
 */
public class ShopSingleUseWarnScreen extends ScaledScreen {

    private static final int RED = 0xFFFF5555;
    private static final int RED_DARK = 0xFFAA2222;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 260;
    private static final int TARGET_H = 190;
    private static final int ROW_H = 11;
    private static final int FOOTER_H = 36;
    private static final int WHEEL_ROWS = 3;

    private final Screen parentScreen;
    private final List<String> lines;
    private final Runnable onConfirm;
    private final ShopDialogUi ui = new ShopDialogUi(RED_DARK, RED, BTN_BG, BTN_HOVER);
    private boolean finished;

    private int left, top, panelWidth, panelHeight;
    private int listX, listY, listW, listH;
    private int scroll = 0;
    private List<FormattedCharSequence> rows = List.of();
    private int[] heights = new int[0];

    private ShopSingleUseWarnScreen(Screen parentScreen, List<String> lines, Runnable onConfirm) {
        super(Component.literal("重复购买提醒"));
        this.parentScreen = parentScreen;
        this.lines = List.copyOf(lines);
        this.onConfirm = onConfirm;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    /** 在 {@code parent}（商店）之上弹出；确认时先回到 parent 再执行 {@code onConfirm}。 */
    public static void open(Screen parent, List<String> lines, Runnable onConfirm) {
        Minecraft.getInstance().setScreen(new ShopSingleUseWarnScreen(parent, lines, onConfirm));
    }

    @Override
    protected void initScaled() {
        ui.cancel();
        ui.syncEnterHeld();
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);
        listX = left + 8;
        listY = top + 26;
        listW = panelWidth - 16;
        listH = Math.max(ROW_H, panelHeight - 26 - FOOTER_H);
        int textW = Math.max(16, listW - ShopDialogUi.BAR_W - 3);
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (String line : lines) {
            List<FormattedCharSequence> parts = this.font.split(DShanhaiTextUtil.fromLegacy(line), textW);
            if (parts.isEmpty()) wrapped.add(FormattedCharSequence.EMPTY);
            else wrapped.addAll(parts);
        }
        rows = wrapped;
        heights = new int[rows.size()];
        Arrays.fill(heights, ROW_H);
        scroll = Math.min(scroll, maxScroll());
    }

    private int visibleRows() {
        return Math.max(1, ShopDialogRows.fitFrom(heights, scroll, listH));
    }

    private int maxScroll() {
        return ShopDialogRows.maxFirst(heights, listH);
    }

    private int barX() {
        return listX + listW - ShopDialogUi.BAR_W;
    }

    private int btnY() {
        return top + panelHeight - 22;
    }

    private int confirmW() {
        return panelWidth - 16 - 60;
    }

    private void scrollTo(int first) {
        scroll = Math.max(0, Math.min(maxScroll(), first));
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        if (!ShopTheme.current().renderBackdropDim(g, this.width, this.height)) super.renderBackground(g);
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        ui.frame(mx, my);
        // 警告弹窗：红框是语义色，两套主题都保留（玻璃主题用它当根板边）
        ShopTheme.current().rootPanel(g, left, top, panelWidth, panelHeight,
                left + 5, top + 18, panelWidth - 10, panelHeight - 23, RED_DARK, RED, RED);

        g.drawString(this.font, "§c⚠ 重复购买提醒", left + 8, top + 5, RED, true);
        g.drawString(this.font, "§7SDA / 无限盘一般只需买一次", left + 8, top + 14, GRAY, true);

        int visible = visibleRows();
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= rows.size()) break;
            g.drawString(this.font, rows.get(idx), listX, listY + i * ROW_H, WHITE, true);
        }
        int maxScroll = maxScroll();
        ui.drawScrollbar(g, barX(), listY, listH, rows.size(), visible, scroll, maxScroll, mx, my);
        if (maxScroll > 0) {
            g.drawString(this.font, "§8" + (scroll + 1) + "-" + Math.min(scroll + visible, rows.size()) + "/" + rows.size()
                    + " 滚轮或拖动右侧滚动条", listX, listY + listH + 2, GRAY, true);
        }

        ui.draw(g, this.font, left + 8, btnY(), confirmW(), 18, "§e仍然购买", mx, my, true);
        ui.draw(g, this.font, left + panelWidth - 8 - 52, btnY(), 52, 18, "§a取消", mx, my, true);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (btn != 0 || finished) return false;
        int first = ui.beginDrag(mx, my, barX(), listY, listH, rows.size(), visibleRows(), maxScroll());
        if (first >= 0) {
            scroll = first;
            return true;
        }
        if (ui.hit(mx, my, left + 8, btnY(), confirmW(), 18, true)) {
            confirm();
            return true;
        }
        if (ui.hit(mx, my, left + panelWidth - 8 - 52, btnY(), 52, 18, true)) {
            cancel();
            return true;
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        int first = ui.drag(my, listY, listH, rows.size(), visibleRows(), maxScroll());
        if (first >= 0) {
            scroll = first;
            return true;
        }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        ui.release();
        return super.universalMouseReleased(mx, my, btn);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (GuiRenderUtil.isHovering(mx, my, listX, listY, listW, listH)) {
            scrollTo(scroll - (int) Math.signum(d) * WHEEL_ROWS);
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!finished) {
            int page = Math.max(1, visibleRows() - 1);
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                // 在商店里按 Enter 打开本框时，松开前的系统重复不算确认
                if (ui.takeEnter()) {
                    ui.press(left + 8, btnY(), confirmW(), 18, true);
                    confirm();
                }
                return true;
            }
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> scrollTo(scroll - 1);
                case GLFW.GLFW_KEY_DOWN -> scrollTo(scroll + 1);
                case GLFW.GLFW_KEY_PAGE_UP -> scrollTo(scroll - page);
                case GLFW.GLFW_KEY_PAGE_DOWN -> scrollTo(scroll + page);
                case GLFW.GLFW_KEY_HOME -> scrollTo(0);
                case GLFW.GLFW_KEY_END -> scrollTo(maxScroll());
                default -> {
                    return super.keyPressed(keyCode, scanCode, modifiers);
                }
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        ui.enterReleased(keyCode);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        if (finished) return;
        finished = true;
        Minecraft.getInstance().setScreen(parentScreen);
        onConfirm.run();
    }

    private void cancel() {
        if (finished) return;
        finished = true;
        Minecraft.getInstance().setScreen(parentScreen);
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void removed() {
        ui.cancel();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
