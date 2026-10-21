package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import com.lowdragmc.lowdraglib.gui.widget.Widget;

/** One recipe-type heading above the cards that share it. A long title wraps and the heading grows. */
public final class ShanhaiRecipeGroupHeaderWidget extends Widget {

    public static final int CH = 14;

    private static final long ENTER_MS = 200L;
    private static final int TEXT_X = 6;

    private final String title;
    private final int order;
    private final long born = ShanhaiRecipeEditorAnimation.nowMs();

    public ShanhaiRecipeGroupHeaderWidget(int x, int y, int width, String title) {
        this(x, y, width, title, 0);
    }

    public ShanhaiRecipeGroupHeaderWidget(int x, int y, int width, String title, int order) {
        super(x, y, width, heightFor(title, width));
        this.title = title == null ? "" : title;
        this.order = Math.max(0, order);
    }

    private static int heightFor(String title, int width) {
        int lines = GlassWrapLabel.lineCount(title, textWidth(width));
        return Math.max(CH, lines * RecipeEditorLayout.LINE_H + 4);
    }

    private static int textWidth(int width) {
        return Math.max(8, width - TEXT_X - 2);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        float enter = enterProgress();
        int x = getPositionX();
        int y = getPositionY();
        int width = getSizeWidth();
        int height = getSizeHeight();
        graphics.pose().pushPose();
        graphics.pose().translate(0f, (1f - enter) * 8f, 0f);
        RenderSystem.setShaderColor(1f, 1f, 1f, 0.2f + 0.8f * enter);
        graphics.fill(x, y, x + width, y + height, GlassPalette.GROUP_FILL);
        graphics.fill(x, y, x + 2, y + height, GlassPalette.ACCENT);
        var font = Minecraft.getInstance().font;
        int lineY = y + 3;
        for (FormattedCharSequence line : GlassWrapLabel.lines(title, textWidth(width))) {
            graphics.drawString(font, line, x + TEXT_X, lineY, GlassPalette.TEXT_HEAD, false);
            lineY += RecipeEditorLayout.LINE_H;
        }
        graphics.pose().popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private float enterProgress() {
        long elapsed = ShanhaiRecipeEditorAnimation.nowMs() - born - order * 28L;
        if (elapsed <= 0L) return 0f;
        float linear = Math.min(1f, elapsed / (float) ENTER_MS);
        return linear * linear * (3f - 2f * linear);
    }
}
