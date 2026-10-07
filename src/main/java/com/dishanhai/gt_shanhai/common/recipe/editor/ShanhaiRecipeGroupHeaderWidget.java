package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.lowdragmc.lowdraglib.gui.widget.Widget;

/** One recipe-type heading above the cards that share it. */
public final class ShanhaiRecipeGroupHeaderWidget extends Widget {

    public static final int CH = 14;

    private static final long ENTER_MS = 200L;

    private final String title;
    private final int order;
    private final long born = ShanhaiRecipeEditorAnimation.nowMs();

    public ShanhaiRecipeGroupHeaderWidget(int x, int y, int width, String title) {
        this(x, y, width, title, 0);
    }

    public ShanhaiRecipeGroupHeaderWidget(int x, int y, int width, String title, int order) {
        super(x, y, width, CH);
        this.title = title == null ? "" : title;
        this.order = Math.max(0, order);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        float enter = enterProgress();
        int x = getPositionX();
        int y = getPositionY();
        int width = getSizeWidth();
        graphics.pose().pushPose();
        graphics.pose().translate(0f, (1f - enter) * 8f, 0f);
        RenderSystem.setShaderColor(1f, 1f, 1f, 0.2f + 0.8f * enter);
        graphics.fill(x, y, x + width, y + CH, 0xFF163044);
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, fit(font, title, width - 8), x + 4, y + 3, 0xFF9FD7FF, false);
        graphics.pose().popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private float enterProgress() {
        long elapsed = ShanhaiRecipeEditorAnimation.nowMs() - born - order * 28L;
        if (elapsed <= 0L) return 0f;
        float linear = Math.min(1f, elapsed / (float) ENTER_MS);
        return linear * linear * (3f - 2f * linear);
    }

    private static String fit(net.minecraft.client.gui.Font font, String text, int width) {
        if (text == null || text.isEmpty() || font.width(text) <= width) return text == null ? "" : text;
        String shown = text;
        while (shown.length() > 1 && font.width(shown + "…") > width) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return shown + "…";
    }
}
