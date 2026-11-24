package com.dishanhai.gt_shanhai.client.gui.shop;

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
 * AE 下单确认显示可提交的产出、预计消耗和未排单原因。
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

    private static final int TARGET_W = 310;
    private static final int TARGET_H = 300;
    private static final int ROW_H = 11;
    /** 列表下面：页码行（10）+ 间隔 + 按钮（18）+ 底边，页码不再压到按钮上。 */
    private static final int FOOTER_H = 36;
    private static final int WHEEL_ROWS = 3;

    private final boolean anySubmittable;
    private final int planned;
    private final int total;
    private final List<Component> outputLines;
    private final List<Component> useLines;
    private final List<Component> noteLines;
    private final ShopDialogUi ui = new ShopDialogUi(GOLD_DARK, GOLD, BTN_BG, BTN_HOVER);
    private final ShopConfirmationReturn returnToParent;
    private final ShopConfirmationActions actions;
    private UiButton confirmButton;
    private UiButton cancelButton;
    private boolean spaceHeld;
    private List<FormattedCharSequence> summaryRows = List.of();

    private int left, top, panelWidth, panelHeight;
    private int listX, listY, listW, listH;
    private int scroll = 0;
    // 按当前宽度换好行的清单；行高一致，heights 只给 ShopDialogRows 用
    private List<FormattedCharSequence> rows = List.of();
    private int[] heights = new int[0];

    private ShopAutoCraftConfirmScreen(Screen parentScreen, UUID planId, int planned, int total,
            List<Component> outputLines, List<Component> useLines, List<Component> noteLines) {
        super(label("title"));
        this.planned = planned;
        this.total = total;
        this.anySubmittable = planned > 0;
        this.outputLines = List.copyOf(outputLines);
        this.useLines = List.copyOf(useLines);
        this.noteLines = List.copyOf(noteLines);
        returnToParent = new ShopConfirmationReturn(this, parentScreen);
        actions = new ShopConfirmationActions(() -> {
            ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(planId, true));
            returnToParent.restore();
        }, () -> {
            if (anySubmittable) ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(planId, false));
            returnToParent.restore();
        }, () -> {
            if (anySubmittable && Minecraft.getInstance().getConnection() != null) {
                ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(planId, false));
            }
        });
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    public static void open(ShopScreen parent, UUID planId, int planned, int total,
            List<Component> outputLines, List<Component> useLines, List<Component> noteLines) {
        Minecraft.getInstance().setScreen(new ShopAutoCraftConfirmScreen(
                parent, planId, planned, total, outputLines, useLines, noteLines));
    }

    private Component summary() {
        return label("summary", planned, total).withStyle(planned == 0 ? ChatFormatting.RED
                : planned < total ? ChatFormatting.GOLD : ChatFormatting.GREEN);
    }

    private List<Component> displayLines() {
        List<Component> lines = new ArrayList<>();
        if (anySubmittable) {
            lines.add(label("outputs").withStyle(ChatFormatting.AQUA));
            lines.addAll(outputLines);
            lines.add(CommonComponents.EMPTY);
            lines.add(label("materials").withStyle(ChatFormatting.AQUA));
            if (useLines.isEmpty()) lines.add(label("no_materials").withStyle(ChatFormatting.GRAY));
            else lines.addAll(useLines);
        }
        if (!noteLines.isEmpty()) {
            if (!lines.isEmpty()) lines.add(CommonComponents.EMPTY);
            lines.add(label("notes").withStyle(ChatFormatting.GOLD));
            lines.addAll(noteLines);
        }
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
        listW = panelWidth - 16;
        summaryRows = this.font.split(summary(), listW);
        int headerH = 18 + summaryRows.size() * ROW_H;
        listY = top + headerH;
        listH = Math.max(ROW_H, panelHeight - headerH - FOOTER_H);
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
                label(anySubmittable ? (planned < total ? "confirm_partial" : "confirm") : "disabled")
                        .withStyle(anySubmittable ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY),
                button -> confirm(), ShopVisuals.preferences(), true));
        confirmButton.active = anySubmittable;
        cancelButton = addWidget(new UiButton(left + panelWidth - 8 - 52, btnY(), 52, 18,
                (anySubmittable ? CommonComponents.GUI_CANCEL.copy().withStyle(ChatFormatting.RED) : label("close")),
                button -> cancel(),
                ShopVisuals.preferences(), false));
        setInitialFocus(anySubmittable ? confirmButton : cancelButton);
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
        for (int i = 0; i < summaryRows.size(); i++) {
            g.drawString(this.font, summaryRows.get(i), left + 8, top + 15 + i * ROW_H, WHITE, true);
        }

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
        details.add(summary());
        details.addAll(displayLines());
        return CommonComponents.joinLines(details);
    }

    private static net.minecraft.network.chat.MutableComponent label(String key, Object... args) {
        return Component.translatable("gt_shanhai.shop.confirmation.auto_craft." + key, args);
    }
}
