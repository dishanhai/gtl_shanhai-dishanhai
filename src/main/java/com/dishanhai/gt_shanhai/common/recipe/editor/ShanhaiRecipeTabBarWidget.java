package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** The three-step bar. The underline follows the page-slide progress. */
public final class ShanhaiRecipeTabBarWidget extends Widget {

    private static final String[] LABELS = {
            "01 机器 / 配方",
            "02 图形化编辑",
            "03 差异审核"
    };

    private final ShanhaiRecipeEditorWidget host;

    public ShanhaiRecipeTabBarWidget(ShanhaiRecipeEditorWidget host, int x, int y, int width) {
        super(x, y, width, 20);
        this.host = host;
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int x = getPositionX();
        int y = getPositionY();
        int part = getSizeWidth() / LABELS.length;
        float marker = host.marker();
        var font = Minecraft.getInstance().font;
        for (int i = 0; i < LABELS.length; i++) {
            int left = x + i * part;
            float on = Math.max(0f, 1f - Math.abs(marker - i));
            boolean allowed = host.canEnter(i);
            graphics.fill(left, y, left + part - 3, y + 18,
                    GuiMotion.lerpColor(GlassPalette.TAB_EDGE, GlassPalette.ACCENT, on));
            graphics.fill(left + 1, y + 1, left + part - 4, y + 17,
                    GuiMotion.lerpColor(GlassPalette.TAB_FILL, GlassPalette.TAB_FILL_ON, on));
            graphics.drawString(font, LABELS[i], left + 6, y + 5,
                    allowed ? GuiMotion.lerpColor(GlassPalette.TEXT_MUTED, GlassPalette.TEXT, on)
                            : GlassPalette.TEXT_OFF, false);
        }
        int bar = Math.round(marker * part);
        graphics.fill(x + bar, y + 16, x + bar + part - 3, y + 18, GlassPalette.ACCENT);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOverElement(mouseX, mouseY)) return false;
        int part = Math.max(1, getSizeWidth() / LABELS.length);
        int index = (int) ((mouseX - getPositionX()) / part);
        if (index < 0 || index >= LABELS.length) return false;
        host.setStage(index, true);
        return true;
    }
}
