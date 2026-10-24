package com.dishanhai.gt_shanhai.client.gui.terminal;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassPanelTexture;
import com.dishanhai.gt_shanhai.client.gui.scaled.ButtonFeedback;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;
import com.dishanhai.gt_shanhai.client.gui.scaled.SpringTable;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorAnimation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 山海終端兩個玻璃 Screen（設定／報告）共用的按鈕、換行文字與裁切；各 Screen 各持一份狀態。 */
final class TerminalGlassUi {
    final Font font;
    final ButtonFeedback feedback = new ButtonFeedback();
    private final SpringTable<String> hover = new SpringTable<>(Spring.HOVER);
    private final GlassPanelTexture page = GlassPanelTexture.page();

    TerminalGlassUi(Font font) { this.font = font; }

    static long now() { return ShanhaiRecipeEditorAnimation.nowMs(); }
    static boolean reduced() { return ClientShopUiSettings.reduceMotion(); }
    static boolean hit(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    static float spring(Spring value, float target, long at) {
        if (reduced()) value.snap(target); else { value.setTarget(target, at); value.update(at); }
        return value.value();
    }
    static float follow(SpringTable<String> table, String key, float target, long at) {
        if (reduced()) { table.get(key, at).snap(target); return target; }
        return table.follow(key, target, at);
    }

    void frame(int mx, int my, long at) { feedback.pointer(mx, my, at); feedback.tick(at); hover.sweep(at); }

    void button(GuiGraphics g, String key, int x, int y, int w, int h, Component text, int textWidth,
                boolean enabled, int mx, int my, Runnable extra) {
        long at = now(); float hot = follow(hover, key, enabled && hit(mx, my, x, y, w, h) ? 1 : 0, at);
        long id = key.hashCode(); var visual = feedback.visual(id, id, enabled, at, reduced());
        GuiRenderUtil.withButtonMotion(g, x, y, w, h, visual.scale(), visual.shakeX(), () -> {
            page.draw(g, mx, my, x, y, w, h);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, ButtonFeedback.shade(GuiMotion.lerpColor(GlassPalette.BUTTON_FILL, GlassPalette.BUTTON_FILL_HOVER, hot), visual));
            g.renderOutline(x, y, w, h, ButtonFeedback.tint(GuiMotion.lerpColor(GlassPalette.BUTTON_EDGE, GlassPalette.ACCENT, hot), visual));
            wrap(g, text, x + 6, y + 5, textWidth, enabled ? GlassPalette.TEXT : GlassPalette.TEXT_OFF);
            if (extra != null) extra.run();
        });
    }

    void wrap(GuiGraphics g, Component text, int x, int y, int width, int color) {
        for (var line : font.split(text, Math.max(1, width))) { g.drawString(font, line, x, y, color, false); y += font.lineHeight; }
    }

    int height(Component text, int width) { return Math.max(1, font.split(text, Math.max(1, width)).size()) * font.lineHeight; }

    static void scissor(GuiGraphics g, int x, int y, int w, int h) {
        var matrix = g.pose().last().pose();
        g.enableScissor((int) Math.floor(x * matrix.m00() + matrix.m30()), (int) Math.floor(y * matrix.m11() + matrix.m31()),
                (int) Math.ceil((x + w) * matrix.m00() + matrix.m30()), (int) Math.ceil((y + h) * matrix.m11() + matrix.m31()));
    }
}
