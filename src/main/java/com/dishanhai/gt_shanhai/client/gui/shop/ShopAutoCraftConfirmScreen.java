package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopAutoCraftConfirmPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 自动合成方案确认框（山海署名，客户端）：花费预览「补齐全部缺口」算完后弹出，展示会消耗的用料
 * 和跳过/材料不足的提示，玩家确认才真正提交（{@link ShopAutoCraftConfirmPacket}）。仿 {@link RewardChoiceScreen}
 * 的列表+确认/取消布局，只是这里是纯文本行，不需要选中态。
 *
 * <p>长行换行不截断；清单很长时可滚轮、拖右侧滚动条或用 ↑↓/PageUp/PageDown/Home/End 翻；Enter 确认、Esc 取消。
 * 按钮反馈走 {@link ShopDialogUi}。</p>
 */
public class ShopAutoCraftConfirmScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 260;
    private static final int TARGET_H = 300;
    private static final int ROW_H = 11;
    /** 列表下面：页码行（10）+ 间隔 + 按钮（18）+ 底边，页码不再压到按钮上。 */
    private static final int FOOTER_H = 36;
    private static final int WHEEL_ROWS = 3;

    private final UUID planId;
    private final Screen parentScreen;
    private final boolean anySubmittable;
    private final List<String> useLines;
    private final List<String> noteLines;
    private final ShopDialogUi ui = new ShopDialogUi(GOLD_DARK, GOLD, BTN_BG, BTN_HOVER);
    private boolean finished;

    private int left, top, panelWidth, panelHeight;
    private int listX, listY, listW, listH;
    private int scroll = 0;
    // 按当前宽度换好行的清单；行高一致，heights 只给 ShopDialogRows 用
    private List<FormattedCharSequence> rows = List.of();
    private int[] heights = new int[0];

    private ShopAutoCraftConfirmScreen(UUID planId, boolean anySubmittable,
            List<String> useLines, List<String> noteLines, Screen parentScreen) {
        super(Component.literal("自动合成确认"));
        this.planId = planId;
        this.anySubmittable = anySubmittable;
        this.useLines = List.copyOf(useLines);
        this.noteLines = List.copyOf(noteLines);
        this.parentScreen = parentScreen;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    /** 相同方案不重開；新方案使用獨立視窗狀態，舊視窗取消不能誤刪新方案。 */
    public static void openOrUpdate(UUID planId, boolean anySubmittableIn, List<String> useLinesIn, List<String> noteLinesIn) {
        Screen current = Minecraft.getInstance().screen;
        Screen parent = current;
        if (current instanceof ShopAutoCraftConfirmScreen existing) {
            if (existing.planId.equals(planId)) return;
            parent = existing.parentScreen;
        }
        Minecraft.getInstance().setScreen(new ShopAutoCraftConfirmScreen(
                planId, anySubmittableIn, useLinesIn, noteLinesIn, parent));
    }

    private List<String> displayLines() {
        List<String> lines = new ArrayList<>();
        if (!noteLines.isEmpty()) {
            lines.add("§c— 跳过/材料不足 —");
            lines.addAll(noteLines);
        }
        if (!useLines.isEmpty()) {
            lines.addAll(useLines);
        }
        if (lines.isEmpty()) lines.add("§7（没有可提交的合成项）");
        return lines;
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
        // 右缘留给滚动条
        int textW = Math.max(16, listW - ShopDialogUi.BAR_W - 3);
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (String line : displayLines()) {
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
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        ui.frame(mx, my);
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, top + 18, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        g.drawString(this.font, "§6自动合成确认", left + 8, top + 5, GOLD, true);
        g.drawString(this.font, anySubmittable ? "§7确认后向 AE 网络提交合成任务" : "§c没有任何一项能提交",
                left + 8, top + 14, GRAY, true);

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

        ui.draw(g, this.font, left + 8, btnY(), confirmW(), 18,
                anySubmittable ? "§a确认合成" : "§8无可提交项", mx, my, anySubmittable);
        ui.draw(g, this.font, left + panelWidth - 8 - 52, btnY(), 52, 18, "§c取消", mx, my, true);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (btn != 0 || finished) return false;
        int first = ui.beginDrag(mx, my, barX(), listY, listH, rows.size(), visibleRows(), maxScroll());
        if (first >= 0) {
            scroll = first;
            return true;
        }
        if (ui.hit(mx, my, left + 8, btnY(), confirmW(), 18, anySubmittable)) {
            if (anySubmittable) confirm();
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
                // 按住 Enter 的系统重复只算一次；在商店里按 Enter 打开本框时，松开前也不算
                if (ui.takeEnter()) {
                    ui.press(left + 8, btnY(), confirmW(), 18, anySubmittable);
                    if (anySubmittable) confirm();
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
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(planId, true));
        Minecraft.getInstance().setScreen(parentScreen);
    }

    private void cancel() {
        if (finished) return;
        finished = true;
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(planId, false));
        Minecraft.getInstance().setScreen(parentScreen);
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void removed() {
        ui.cancel();
        if (!finished && Minecraft.getInstance().getConnection() != null) {
            finished = true;
            ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(planId, false));
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
