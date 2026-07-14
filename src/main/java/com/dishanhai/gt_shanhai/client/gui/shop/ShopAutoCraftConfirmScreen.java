package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopAutoCraftConfirmPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动合成方案确认框（山海署名，客户端）：花费预览「补齐全部缺口」算完后弹出，展示会消耗的用料
 * 和跳过/材料不足的提示，玩家确认才真正提交（{@link ShopAutoCraftConfirmPacket}）。仿 {@link RewardChoiceScreen}
 * 的列表+确认/取消布局，只是这里是纯文本行，不需要选中态。
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

    // 打开确认框那一刻的当前屏幕（预期是 ShopScreen），确认/取消后回去；静态持有，供 openOrUpdate 跨包调用。
    private static Screen parentScreen;
    private static boolean anySubmittable = false;
    private static List<String> useLines = List.of();
    private static List<String> noteLines = List.of();

    private int left, top, panelWidth, panelHeight;
    private int listX, listY, listW, listH;
    private int scroll = 0;

    public ShopAutoCraftConfirmScreen() {
        super(Component.literal("自动合成确认"));
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    /** {@code ShopAutoCraftPlanPacket} 到达时调用：已开着就只刷新内容，没开就在当前屏幕上打开。 */
    public static void openOrUpdate(boolean anySubmittableIn, List<String> useLinesIn, List<String> noteLinesIn) {
        anySubmittable = anySubmittableIn;
        useLines = useLinesIn != null ? useLinesIn : List.of();
        noteLines = noteLinesIn != null ? noteLinesIn : List.of();
        Screen current = Minecraft.getInstance().screen;
        if (current instanceof ShopAutoCraftConfirmScreen) return;
        parentScreen = current;
        Minecraft.getInstance().setScreen(new ShopAutoCraftConfirmScreen());
    }

    private List<String> displayLines() {
        List<String> lines = new ArrayList<>();
        if (!noteLines.isEmpty()) {
            lines.add("§c— 跳过/材料不足 —");
            lines.addAll(noteLines);
        }
        if (!useLines.isEmpty()) {
            lines.add("§a— 将会消耗 —");
            lines.addAll(useLines);
        }
        if (lines.isEmpty()) lines.add("§7（没有可提交的合成项）");
        return lines;
    }

    @Override
    protected void initScaled() {
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);
        listX = left + 8;
        listY = top + 26;
        listW = panelWidth - 16;
        listH = panelHeight - 26 - 30;
    }

    private int visibleRows() {
        return Math.max(1, listH / ROW_H);
    }

    private int maxScroll(List<String> lines) {
        return Math.max(0, lines.size() - visibleRows());
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, top + 18, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        g.drawString(this.font, "§6自动合成确认", left + 8, top + 5, GOLD, true);
        g.drawString(this.font, anySubmittable ? "§7确认后向 AE 网络提交合成任务" : "§c没有任何一项能提交",
                left + 8, top + 14, GRAY, true);

        List<String> lines = displayLines();
        int visible = visibleRows();
        int maxScroll = maxScroll(lines);
        if (scroll > maxScroll) scroll = maxScroll;
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= lines.size()) break;
            int ry = listY + i * ROW_H;
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, lines.get(idx), listW), listX, ry, WHITE, true);
        }
        if (lines.size() > visible) {
            g.drawString(this.font, "§8" + (scroll + 1) + "-" + Math.min(scroll + visible, lines.size()) + "/" + lines.size()
                    + " 滚轮翻页", listX, listY + listH + 2, GRAY, true);
        }

        int btnY = top + panelHeight - 22;
        int confirmW = panelWidth - 16 - 60;
        drawBtn(g, left + 8, btnY, confirmW, 18, anySubmittable ? "§a确认合成" : "§8无可提交项", mx, my, anySubmittable);
        drawBtn(g, left + panelWidth - 8 - 52, btnY, 52, 18, "§c取消", mx, my, true);
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, boolean enabled) {
        boolean hv = enabled && GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hv ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        int btnY = top + panelHeight - 22;
        int confirmW = panelWidth - 16 - 60;
        if (anySubmittable && GuiRenderUtil.isHovering(mx, my, left + 8, btnY, confirmW, 18)) {
            confirm();
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + panelWidth - 8 - 52, btnY, 52, 18)) {
            cancel();
            return true;
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (GuiRenderUtil.isHovering(mx, my, listX, listY, listW, listH)) {
            scroll = Math.max(0, Math.min(maxScroll(displayLines()), scroll - (int) Math.signum(d)));
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    private void confirm() {
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(true));
        Minecraft.getInstance().setScreen(parentScreen);
    }

    private void cancel() {
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(false));
        Minecraft.getInstance().setScreen(parentScreen);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
