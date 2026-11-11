package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.sh_better_ui.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiButton;
import com.mojang.blaze3d.platform.InputConstants;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopAutoCraftConfirmPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
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
public class ShopAutoCraftConfirmScreen extends ShopScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
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
    private final ShopConfirmationReturn returnToParent;
    private final ShopConfirmationActions actions;
    private UiButton confirmButton;
    private UiButton cancelButton;
    private boolean spaceHeld;

    private int left, top, panelWidth, panelHeight;
    private int listX, listY, listW, listH;
    private int scroll = 0;
    // 按当前宽度换好行的清单；行高一致，heights 只给 ShopDialogRows 用
    private List<FormattedCharSequence> rows = List.of();
    private int[] heights = new int[0];

    private ShopAutoCraftConfirmScreen(UUID planId, boolean anySubmittable,
            List<String> useLines, List<String> noteLines, Screen parentScreen) {
        super(label("auto_craft.title"));
        this.planId = planId;
        this.anySubmittable = anySubmittable;
        this.useLines = List.copyOf(useLines);
        this.noteLines = List.copyOf(noteLines);
        this.parentScreen = parentScreen;
        returnToParent = new ShopConfirmationReturn(this, parentScreen);
        actions = new ShopConfirmationActions(() -> {
            ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(planId, true));
            returnToParent.restore();
        }, () -> {
            ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(planId, false));
            returnToParent.restore();
        }, () -> {
            if (Minecraft.getInstance().getConnection() != null) {
                ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(planId, false));
            }
        });
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

    private List<Component> displayLines() {
        List<Component> lines = new ArrayList<>();
        if (!noteLines.isEmpty()) {
            lines.add(label("auto_craft.notes").withStyle(ChatFormatting.RED));
            for (String line : noteLines) lines.add(DShanhaiTextUtil.fromLegacy(line));
        }
        if (!useLines.isEmpty()) {
            for (String line : useLines) lines.add(DShanhaiTextUtil.fromLegacy(line));
        }
        if (lines.isEmpty()) lines.add(label("auto_craft.empty").withStyle(ChatFormatting.GRAY));
        return lines;
    }

    @Override
    protected void initScaled() {
        ui.cancel();
        ui.syncEnterHeld();
        spaceHeld = InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_SPACE);
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
        for (Component line : displayLines()) {
            List<FormattedCharSequence> parts = this.font.split(line, textW);
            if (parts.isEmpty()) wrapped.add(FormattedCharSequence.EMPTY);
            else wrapped.addAll(parts);
        }
        rows = wrapped;
        heights = new int[rows.size()];
        Arrays.fill(heights, ROW_H);
        scroll = Math.min(scroll, maxScroll());
        confirmButton = addWidget(new UiButton(left + 8, btnY(), confirmW(), 18,
                label(anySubmittable ? "auto_craft.confirm" : "auto_craft.disabled")
                        .withStyle(anySubmittable ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                button -> confirm(), ShopVisuals.preferences(), true));
        confirmButton.active = anySubmittable;
        cancelButton = addWidget(new UiButton(left + panelWidth - 8 - 52, btnY(), 52, 18,
                CommonComponents.GUI_CANCEL.copy().withStyle(ChatFormatting.RED), button -> cancel(),
                ShopVisuals.preferences(), false));
        if (anySubmittable) setInitialFocus(confirmButton);
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
        returnToParent.render(g);
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        ui.frame(mx, my);
        ShopTheme.current().rootPanel(g, left, top, panelWidth, panelHeight,
                left + 5, top + 18, panelWidth - 10, panelHeight - 23);

        g.drawString(this.font, getTitle(), left + 8, top + 5, GOLD, true);
        g.drawString(this.font, label(anySubmittable ? "auto_craft.details" : "auto_craft.unavailable")
                        .withStyle(anySubmittable ? ChatFormatting.GRAY : ChatFormatting.RED),
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
            g.drawString(this.font, Component.translatable("gt_shanhai.shop.confirmation.scroll",
                    scroll + 1, Math.min(scroll + visible, rows.size()), rows.size()),
                    listX, listY + listH + 2, GRAY, true);
        }

        ui.draw(g, this.font, left + 8, btnY(), confirmW(), 18,
                confirmButton.getMessage(), mx, my, anySubmittable);
        ui.draw(g, this.font, left + panelWidth - 8 - 52, btnY(), 52, 18,
                cancelButton.getMessage(), mx, my, true);
        if (confirmButton.isFocused()) ShopTheme.current().focus(g, left + 8, btnY(), confirmW(), 18);
        if (cancelButton.isFocused()) ShopTheme.current().focus(g, left + panelWidth - 8 - 52, btnY(), 52, 18);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (btn != 0 || actions.finished()) return false;
        int first = ui.beginDrag(mx, my, barX(), listY, listH, rows.size(), visibleRows(), maxScroll());
        if (first >= 0) {
            scroll = first;
            return true;
        }
        if (ui.hit(mx, my, left + 8, btnY(), confirmW(), 18, anySubmittable)) {
            if (anySubmittable) {
                setFocused(confirmButton);
                confirmButton.onPress();
            }
            return true;
        }
        if (ui.hit(mx, my, left + panelWidth - 8 - 52, btnY(), 52, 18, true)) {
            setFocused(cancelButton);
            cancelButton.onPress();
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
        if (!actions.finished()) {
            int page = Math.max(1, visibleRows() - 1);
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                // 按住 Enter 的系统重复只算一次；在商店里按 Enter 打开本框时，松开前也不算
                if (ui.takeEnter()) {
                    UiButton target = getFocused() == cancelButton ? cancelButton : confirmButton;
                    ui.press(target.getX(), target.getY(), target.getWidth(), target.getHeight(), target.active);
                    if (target.active) target.onPress();
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_SPACE && getFocused() instanceof UiButton target) {
                if (!spaceHeld) {
                    spaceHeld = true;
                    ui.press(target.getX(), target.getY(), target.getWidth(), target.getHeight(), target.active);
                    if (target.active) target.onPress();
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
        if (keyCode == GLFW.GLFW_KEY_SPACE) spaceHeld = false;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        actions.confirm(anySubmittable);
    }

    private void cancel() {
        actions.cancel();
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void removed() {
        ui.cancel();
        returnToParent.close();
        actions.abandon();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public Component getNarrationMessage() {
        List<Component> details = new ArrayList<>();
        details.add(getTitle());
        details.add(label(anySubmittable ? "auto_craft.details" : "auto_craft.unavailable"));
        details.addAll(displayLines());
        return CommonComponents.joinLines(details);
    }

    private static net.minecraft.network.chat.MutableComponent label(String key) {
        return Component.translatable("gt_shanhai.shop.confirmation." + key);
    }
}
