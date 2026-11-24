package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.shop.ShopOperationGuideState.Step;
import com.dishanhai.sh_better_ui.client.gui.display.GraphicIcon;
import com.dishanhai.sh_better_ui.client.gui.scaled.ThemeEffects;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiButton;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** 山海：在本地练习商店操作，关闭后返回原来进入引导的界面。 */
public final class ShopOperationGuideScreen extends ShopScaledScreen {
    private record Shortcut(String key, GraphicIcon icon) {}

    private static final String PREFIX = "gt_shanhai.shop.operation_guide.";
    private static final int PANEL_WIDTH = 308;
    private static final int PADDING = 14;
    private static final int CONTENT_WIDTH = PANEL_WIDTH - 2 * PADDING;
    private static final int TITLE_WIDTH = 174;
    private static final int PROGRESS_WIDTH = CONTENT_WIDTH - TITLE_WIDTH - 8;
    private static final int LINE_HEIGHT = 11;
    private static final int GAP = 6;
    private static final int NAV_WIDTH = (CONTENT_WIDTH - 2 * GAP) / 3;
    private static final int TRAY_PADDING = 8;
    private static final int TRAY_WIDTH = CONTENT_WIDTH - 2 * TRAY_PADDING;
    private static final int STEPPER_WIDTH = (TRAY_WIDTH - GAP) / 2;
    private static final int SHORTCUT_TEXT_WIDTH = TRAY_WIDTH - 22;
    private static final GraphicIcon IRON_INGOT =
            new GraphicIcon(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/item/iron_ingot.png"));
    private static final List<Shortcut> SHORTCUTS = List.of(
            new Shortcut("shortcuts.search", GraphicIcon.SEARCH),
            new Shortcut("shortcuts.cart", GraphicIcon.SHOPPING_CART),
            new Shortcut("shortcuts.favorite", GraphicIcon.FAVORITE),
            new Shortcut("shortcuts.mode", GraphicIcon.TRADE));

    private final ShopOperationGuideState state = new ShopOperationGuideState();
    private final ShopConfirmationReturn returnToParent;
    private final ShopControlInput activationInput = new ShopControlInput();
    private int left, top, panelHeight, headerHeight, summaryHeight, stepperHeight;
    private UiButton select, decrease, increase, confirm, previous, next;
    private boolean initialized;

    ShopOperationGuideScreen(Screen parent) {
        super(label("title").withStyle(ChatFormatting.BOLD));
        returnToParent = new ShopConfirmationReturn(this, parent);
        targetWidth = PANEL_WIDTH + 2 * GAP;
        minScale = 0.01f;
        maxScale = 1.8f;
    }

    @Override
    protected void init() {
        headerHeight = textHeight(getTitle(), TITLE_WIDTH);
        for (Step step : Step.values()) {
            headerHeight = Math.max(headerHeight, textHeight(progress(step), PROGRESS_WIDTH));
        }
        int maximum = ShopOperationGuideState.MAX_QUANTITY;
        summaryHeight = textHeight(amount(maximum), TRAY_WIDTH)
                + textHeight(total(ShopOperationGuideState.costFor(maximum)), TRAY_WIDTH);
        stepperHeight = buttonHeight(22, STEPPER_WIDTH, "quantity.decrease", "quantity.increase");
        int tallestStep = 0;
        for (Step step : Step.values()) tallestStep = Math.max(tallestStep, stepHeight(step));
        int navigationHeight = buttonHeight(24, NAV_WIDTH, "exit", "previous", "next", "finish");
        panelHeight = 4 * PADDING + headerHeight + 4 + tallestStep + navigationHeight;
        targetHeight = panelHeight + 2 * GAP;
        super.init();
    }

    @Override
    protected void initScaled() {
        long window = minecraft.getWindow().getWindow();
        activationInput.reset(GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS,
                GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS,
                GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS);
        left = (vWidth - PANEL_WIDTH) / 2;
        top = (vHeight - panelHeight) / 2;

        int x = contentX() + TRAY_PADDING;
        int browseY = exerciseY(Step.BROWSE) + TRAY_PADDING;
        select = button(x, browseY, TRAY_WIDTH, browseButtonHeight(), "browse.select", false, () -> {
            state.selectExample();
            updateControls();
        });
        select.setLeadingIcon(IRON_INGOT);

        int quantityY = exerciseY(Step.QUANTITY) + TRAY_PADDING + summaryHeight + GAP;
        decrease = button(x, quantityY, STEPPER_WIDTH, stepperHeight, "quantity.decrease", false, () -> {
            state.decrease();
            updateControls();
        });
        increase = button(x + TRAY_WIDTH - STEPPER_WIDTH, quantityY, STEPPER_WIDTH, stepperHeight,
                "quantity.increase", false, () -> {
                    state.increase();
                    updateControls();
                });

        int confirmY = exerciseY(Step.CONFIRM) + TRAY_PADDING + summaryHeight + GAP;
        confirm = button(x, confirmY, TRAY_WIDTH, confirmButtonHeight(), "confirm.try", false, () -> {
            state.confirmExample();
            updateControls();
        });

        int navHeight = buttonHeight(24, NAV_WIDTH, "exit", "previous", "next", "finish");
        int navY = top + panelHeight - PADDING - navHeight;
        button(contentX(), navY, NAV_WIDTH, navHeight, "exit", false, this::onClose);
        previous = button(contentX() + NAV_WIDTH + GAP, navY, NAV_WIDTH, navHeight, "previous", false, () -> {
            if (state.previous()) updateControls();
        });
        next = button(contentX() + CONTENT_WIDTH - NAV_WIDTH, navY, NAV_WIDTH, navHeight, "next", true, () -> {
            if (state.next()) updateControls();
            else onClose();
        });
        updateControls();
        if (!initialized) {
            initialized = true;
            setInitialFocus(select);
        }
    }

    private UiButton button(int x, int y, int width, int height, String key, boolean primary, Runnable action) {
        UiButton button = addRenderableWidget(new UiButton(x, y, width, height, label(key),
                ignored -> action.run(), ShopVisuals.preferences(), primary));
        button.setSampleBackground(false);
        button.setTooltip(null);
        return button;
    }

    private void updateControls() {
        Step step = state.step();
        select.visible = select.active = step == Step.BROWSE;
        select.setSelected(state.selected());
        decrease.visible = increase.visible = step == Step.QUANTITY;
        decrease.active = decrease.visible && state.canDecrease();
        increase.active = increase.visible && state.canIncrease();
        confirm.visible = confirm.active = step == Step.CONFIRM;
        confirm.setSelected(state.confirmed());
        previous.active = state.canPrevious();
        Component nextLabel = label(state.canNext() ? "next" : "finish");
        if (!next.getMessage().equals(nextLabel)) {
            next.setMessage(nextLabel);
            next.setTooltip(null);
        }
    }

    private int contentX() { return left + PADDING; }
    private int stepY() { return top + 2 * PADDING + headerHeight + 4; }
    private int exerciseY(Step step) { return stepY() + copyHeight(step); }

    private int copyHeight(Step step) {
        return textHeight(stepTitle(step), CONTENT_WIDTH) + textHeight(stepBody(step), CONTENT_WIDTH) + 2 * GAP;
    }

    private int stepHeight(Step step) {
        return copyHeight(step) + exerciseHeight(step);
    }

    private int exerciseHeight(Step step) {
        int innerHeight = switch (step) {
            case BROWSE -> browseButtonHeight() + GAP + textHeight(label("browse.selected"), TRAY_WIDTH);
            case QUANTITY -> summaryHeight + GAP + stepperHeight;
            case CONFIRM -> summaryHeight + 2 * GAP + confirmButtonHeight()
                    + textHeight(label("confirm.success"), TRAY_WIDTH);
            case SHORTCUTS -> SHORTCUTS.stream().mapToInt(this::shortcutHeight).sum()
                    + (SHORTCUTS.size() - 1) * GAP;
        };
        return innerHeight + 2 * TRAY_PADDING;
    }

    private int shortcutHeight(Shortcut shortcut) {
        return Math.max(GraphicIcon.PIXELS, textHeight(label(shortcut.key()), SHORTCUT_TEXT_WIDTH));
    }

    private int browseButtonHeight() {
        return Math.max(32, textHeight(label("browse.select"), TRAY_WIDTH - 32) + 4);
    }

    private int confirmButtonHeight() { return buttonHeight(24, TRAY_WIDTH, "confirm.try"); }

    private int buttonHeight(int minimum, int width, String... keys) {
        int height = minimum;
        for (String key : keys) height = Math.max(height, textHeight(label(key), width - 12) + 4);
        return height;
    }

    private int textHeight(Component content, int width) {
        return Math.max(1, font.split(content, width).size()) * LINE_HEIGHT;
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        if (!ShopTheme.current().renderBackdropDim(graphics, width, height)) super.renderBackground(graphics);
        returnToParent.render(graphics);
    }

    @Override
    protected void renderScaledBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ShopTheme theme = ShopTheme.current();
        UiPreferences prefs = UiPreferences.configured();
        int accent = prefs.highContrast() ? -1 : ThemeEffects.material(theme.materialGroup()).accent(theme.frame);
        int secondary = prefs.highContrast() ? -1 : theme.tabIdle();
        Step step = state.step();
        int x = contentX();
        int y = top + PADDING;
        theme.rootPanel(graphics, left, top, PANEL_WIDTH, panelHeight, 0, 0, 0, 0);
        drawWrapped(graphics, getTitle(), x, y, TITLE_WIDTH, accent);
        drawWrapped(graphics, progress(step), x + TITLE_WIDTH + 8, y, PROGRESS_WIDTH, secondary);

        int markY = y + headerHeight + 3;
        for (int i = 0; i < ShopOperationGuideState.STEP_COUNT; i++) {
            int start = x + i * (CONTENT_WIDTH + GAP) / ShopOperationGuideState.STEP_COUNT;
            int end = x + (i + 1) * (CONTENT_WIDTH + GAP) / ShopOperationGuideState.STEP_COUNT - GAP;
            boolean current = i == step.ordinal();
            graphics.fill(start, markY + (current ? 0 : 1), end, markY + 3, current ? accent : theme.frameDark);
        }

        int stepBodyY = drawWrapped(graphics, stepTitle(step), x, stepY(), CONTENT_WIDTH, -1) + GAP;
        drawWrapped(graphics, stepBody(step), x, stepBodyY, CONTENT_WIDTH, secondary);
        drawExercise(graphics, theme, prefs, step, exerciseY(step), accent);
    }

    private void drawExercise(GuiGraphics graphics, ShopTheme theme, UiPreferences prefs,
                              Step step, int y, int accent) {
        int x = contentX();
        theme.box(graphics, x, y, CONTENT_WIDTH, exerciseHeight(step), theme.frameDark, theme.boxBg);
        x += TRAY_PADDING;
        y += TRAY_PADDING;
        switch (step) {
            case BROWSE -> {
                if (state.selected()) {
                    drawWrapped(graphics, label("browse.selected"), x, y + browseButtonHeight() + GAP, TRAY_WIDTH, accent);
                }
            }
            case QUANTITY -> drawSummary(graphics, x, y, accent);
            case CONFIRM -> {
                drawSummary(graphics, x, y, accent);
                if (state.confirmed()) {
                    int feedbackY = y + summaryHeight + confirmButtonHeight() + 2 * GAP;
                    drawWrapped(graphics, label("confirm.success"), x, feedbackY, TRAY_WIDTH, accent);
                }
            }
            case SHORTCUTS -> {
                for (Shortcut shortcut : SHORTCUTS) {
                    int height = shortcutHeight(shortcut);
                    shortcut.icon().draw(graphics, x, y + (height - GraphicIcon.PIXELS) / 2, 1, accent, prefs);
                    Component text = label(shortcut.key());
                    drawWrapped(graphics, text, x + 22, y + (height - textHeight(text, SHORTCUT_TEXT_WIDTH)) / 2,
                            SHORTCUT_TEXT_WIDTH, -1);
                    y += height + GAP;
                }
            }
        }
    }

    private void drawSummary(GuiGraphics graphics, int x, int y, int accent) {
        int totalY = drawWrapped(graphics, amount(state.quantity()), x, y, TRAY_WIDTH, -1);
        drawWrapped(graphics, total(state.total()), x, totalY, TRAY_WIDTH, accent);
    }

    private int drawWrapped(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        for (var line : font.split(text, width)) {
            graphics.drawString(font, line, x, y, color, !ShopTheme.current().glass);
            y += LINE_HEIGHT;
        }
        return y;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ShopControlInput.activationKey(keyCode)) {
            boolean enabled = getFocused() instanceof UiButton button && button.visible && button.active;
            if (!activationInput.press(keyCode, enabled)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        activationInput.release(keyCode);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public Component getNarrationMessage() {
        Step step = state.step();
        List<Component> messages = new ArrayList<>(List.of(getTitle(), progress(step), stepTitle(step), stepBody(step)));
        switch (step) {
            case BROWSE -> {
                if (state.selected()) messages.add(label("browse.selected"));
            }
            case QUANTITY, CONFIRM -> {
                messages.add(amount(state.quantity()));
                messages.add(total(state.total()));
                if (step == Step.CONFIRM && state.confirmed()) messages.add(label("confirm.success"));
            }
            case SHORTCUTS -> SHORTCUTS.forEach(shortcut -> messages.add(label(shortcut.key())));
        }
        return CommonComponents.joinLines(messages);
    }

    @Override
    public void onClose() { returnToParent.restore(); }

    @Override
    public void removed() {
        returnToParent.close();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static Component stepTitle(Step step) { return label(step.key + ".title").withStyle(ChatFormatting.BOLD); }
    private static Component stepBody(Step step) { return label(step.key + ".body"); }
    private static Component progress(Step step) { return label("progress", step.ordinal() + 1, ShopOperationGuideState.STEP_COUNT); }
    private static Component amount(int quantity) { return label("quantity.amount", quantity); }
    private static Component total(int cost) { return label("quantity.total", cost); }
    private static MutableComponent label(String key, Object... args) { return Component.translatable(PREFIX + key, args); }
}
