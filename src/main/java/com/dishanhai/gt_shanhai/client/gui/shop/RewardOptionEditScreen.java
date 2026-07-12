package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 奖励池单项编辑器（山海署名，客户端）：编辑权重（RANDOM 模式抽取占比）+ 数量区间
 * （每次交付独立随机取整，min==max 即固定数量），对标 FTBQuests 战利品表的 weight/count 配置。
 * 从 {@link ShopEntryEditScreen} 悬停奖励池槽位点击进入，确认后回填对应下标。
 */
public class RewardOptionEditScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 200;
    private static final int TARGET_H = 132;

    private final Screen parent;
    private final ShopEntry.RewardOption original;
    private final Consumer<ShopEntry.RewardOption> onConfirm;
    private final Runnable onDelete;

    private int weight;
    private int minCount;
    private int maxCount;

    private EditBox weightBox, minBox, maxBox;
    private int left, top, panelWidth, panelHeight;

    public RewardOptionEditScreen(Screen parent, ShopEntry.RewardOption original,
                                  Consumer<ShopEntry.RewardOption> onConfirm, Runnable onDelete) {
        super(Component.literal("编辑奖励"));
        this.parent = parent;
        this.original = original;
        this.onConfirm = onConfirm;
        this.onDelete = onDelete;
        this.weight = original.weight();
        this.minCount = original.minCount();
        this.maxCount = original.maxCount();
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    @Override
    protected void initScaled() {
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);

        int fx = left + 62;
        weightBox = mkNumBox(fx, top + 36, 50, weight, v -> weight = v);
        minBox = mkNumBox(fx, top + 54, 50, minCount, v -> { minCount = v; if (maxCount < minCount) maxCount = minCount; });
        maxBox = mkNumBox(fx, top + 72, 50, maxCount, v -> { maxCount = v; if (minCount > maxCount) minCount = maxCount; });
        addRenderableWidget(weightBox);
        addRenderableWidget(minBox);
        addRenderableWidget(maxBox);
    }

    private EditBox mkNumBox(int x, int y, int w, int val, java.util.function.IntConsumer setter) {
        EditBox b = new EditBox(this.font, x, y, w, 12, Component.literal(""));
        b.setValue(Integer.toString(val));
        b.setMaxLength(9);
        b.setBordered(true);
        b.setTextColor(0xFFFFFF);
        b.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        b.setResponder(s -> {
            int v;
            try { v = s.isEmpty() ? 1 : Integer.parseInt(s); }
            catch (NumberFormatException e) { v = 1; }
            setter.accept(Math.max(1, v));
        });
        return b;
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, top + 18, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        g.drawString(this.font, "§6编辑奖励", left + 8, top + 5, GOLD, true);
        g.renderItem(original.item(), left + 8, top + 20);
        g.renderItemDecorations(this.font, original.item(), left + 8, top + 20);
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, original.item().getHoverName().getString(), panelWidth - 40),
                left + 30, top + 24, WHITE, true);

        g.drawString(this.font, "§7权重", left + 8, top + 39, GRAY, true);
        g.drawString(this.font, "§7最小", left + 8, top + 57, GRAY, true);
        g.drawString(this.font, "§7最大", left + 8, top + 75, GRAY, true);

        int btnY = top + panelHeight - 20;
        drawBtn(g, left + 8, btnY, 40, 16, "§c删除", mx, my);
        drawBtn(g, left + panelWidth - 8 - 46 - 44, btnY, 44, 16, "§7取消", mx, my);
        drawBtn(g, left + panelWidth - 8 - 46, btnY, 46, 16, "§a确认", mx, my);
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hv = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hv ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        int btnY = top + panelHeight - 20;
        if (GuiRenderUtil.isHovering(mx, my, left + 8, btnY, 40, 16)) {
            if (onDelete != null) onDelete.run();
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + panelWidth - 8 - 46 - 44, btnY, 44, 16)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + panelWidth - 8 - 46, btnY, 46, 16)) {
            confirm();
            return true;
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    private void confirm() {
        onConfirm.accept(ShopEntry.RewardOption.of(original.item(), weight, minCount, maxCount));
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
