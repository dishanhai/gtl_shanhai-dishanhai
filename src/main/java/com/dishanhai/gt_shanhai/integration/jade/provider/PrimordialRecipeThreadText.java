package com.dishanhai.gt_shanhai.integration.jade.provider;

// Author: dishanhai
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.api.ui.IElement;
import snownee.jade.overlay.DisplayHelper;

import java.util.List;

/** 山海：固定側框文字寬度，保留翻譯與樣式，產物圖示和數量共用一列。 */
final class PrimordialRecipeThreadText implements IElement {
    private static final int WIDTH = 236;
    private final Component text;
    private final IElement icon;
    private final IElement textElement;
    private List<FormattedCharSequence> lines;
    private Vec2 cachedSize;

    PrimordialRecipeThreadText(Component text, IElement textElement, IElement icon) {
        this.text = text;
        this.textElement = textElement;
        this.icon = icon;
    }

    Component text() { return text; }

    private int iconWidth() { return icon == null ? 0 : (int) Math.ceil(icon.getCachedSize().x) + 2; }

    @Override public Vec2 getSize() {
        var font = DisplayHelper.font();
        lines = font.split(text, WIDTH - iconWidth());
        return new Vec2(WIDTH, Math.max(lines.size() * (font.lineHeight + 1),
                icon == null ? 0 : icon.getCachedSize().y));
    }

    @Override public void render(GuiGraphics graphics, float x, float y, float width, float height) {
        if (lines == null) getSize();
        if (icon != null) icon.render(graphics, x, y, icon.getCachedSize().x, icon.getCachedSize().y);
        int color = IThemeHelper.get().getNormalColor();
        int lineHeight = DisplayHelper.font().lineHeight + 1;
        for (var line : lines) {
            DisplayHelper.INSTANCE.drawText(graphics, line, x + iconWidth(), y, color);
            y += lineHeight;
        }
    }

    @Override public String getMessage() { return text.getString(); }

    @Override public Vec2 getCachedSize() {
        if (cachedSize == null) cachedSize = getSize();
        return cachedSize;
    }

    @Override public IElement size(Vec2 size) { cachedSize = size; return this; }
    @Override public IElement align(Align align) { textElement.align(align); return this; }
    @Override public Align getAlignment() { return textElement.getAlignment(); }
    @Override public IElement translate(Vec2 translation) { textElement.translate(translation); return this; }
    @Override public Vec2 getTranslation() { return textElement.getTranslation(); }
    @Override public IElement tag(ResourceLocation tag) { textElement.tag(tag); return this; }
    @Override public ResourceLocation getTag() { return textElement.getTag(); }
    @Override public String getCachedMessage() { return textElement.getCachedMessage(); }
    @Override public IElement clearCachedMessage() { textElement.clearCachedMessage(); return this; }
    @Override public IElement message(String message) { textElement.message(message); return this; }
}
