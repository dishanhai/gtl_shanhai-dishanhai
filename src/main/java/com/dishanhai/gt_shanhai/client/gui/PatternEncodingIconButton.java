package com.dishanhai.gt_shanhai.client.gui;

import appeng.client.gui.Icon;
import com.dishanhai.sh_better_ui.client.gui.display.GraphicIcon;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 山海：沿用 AE2 底板，使用共享图标和原生按钮输入。 */
public final class PatternEncodingIconButton extends Button {
    private static final int SIZE = 8;
    private static final float ICON_SCALE = (float) SIZE / GraphicIcon.PIXELS;

    private final Supplier<Appearance> appearance;
    private final int backgroundColor;

    public PatternEncodingIconButton(Appearance on, Appearance off, BooleanSupplier selected, OnPress onPress) {
        this(() -> selected.getAsBoolean() ? on : off, onPress);
    }

    public PatternEncodingIconButton(Supplier<Appearance> appearance, OnPress onPress) {
        this(appearance, 0xFFFFFF, onPress);
    }

    public PatternEncodingIconButton(Supplier<Appearance> appearance, int backgroundColor, OnPress onPress) {
        super(0, 0, SIZE, SIZE, Component.empty(), onPress, DEFAULT_NARRATION);
        this.appearance = appearance;
        this.backgroundColor = backgroundColor;
        updateLabel(appearance.get().label());
    }

    @Override
    public void onPress() {
        super.onPress();
        updateLabel(appearance.get().label());
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Appearance current = appearance.get();
        updateLabel(current.label());
        int x = getX();
        int y = getY();
        Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter()
                .colorRgb(backgroundColor)
                .dest(x, y, SIZE, SIZE)
                .blit(graphics);

        if (isHoveredOrFocused()) {
            graphics.renderOutline(x, y, SIZE, SIZE, isFocused() ? 0xFFFFFFFF : 0xFFB4D8EA);
        }
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0);
            pose.scale(ICON_SCALE, ICON_SCALE, 1);
            current.icon().draw(graphics, 0, 0, 1, current.tint());
        } finally {
            pose.popPose();
        }
    }

    private void updateLabel(Component label) {
        if (getMessage() != label) {
            setMessage(label);
            setTooltip(Tooltip.create(label));
        }
    }

    public record Appearance(GraphicIcon icon, int tint, Component label) {}
}
