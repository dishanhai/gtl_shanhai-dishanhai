package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterVisual;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import com.dishanhai.sh_better_ui.client.gui.scaled.SpringTable;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/** Paints the existing player inventory slots and keeps the original item renderer and hit areas. */
final class StarLifterInventorySkin {
    private final SpringTable<Integer> hover = new SpringTable<>(Spring.HOVER);
    private long now;
    private boolean animated;

    IGuiTexture panel() { return this::drawPanel; }

    IGuiTexture slot() { return this::drawSlot; }

    private void drawPanel(GuiGraphics g, int mouseX, int mouseY, float x, float y, int width, int height) {
        now = System.currentTimeMillis();
        var preferences = UiPreferences.configured();
        animated = StarLifterVisual.TERMINAL_MOTION.enabled() && !preferences.reducedMotion();
        hover.sweep(now);
        int left = Math.round(x), top = Math.round(y);
        int rail = (StarLifterTerminal.WIDTH - width) / 2;
        int start = left - rail, end = left + width + rail;
        g.fill(start, top + 4, end, top + height - 3, 0xa4121931);
        g.fill(start, top + 4, end, top + 5, 0xff3d496b);
        g.fill(left + 2, top + 4, left + width - 2, top + 59, 0xdd0d1224);
        g.fill(left + 2, top + 62, left + width - 2, top + 81, 0xd9192037);

        rail(g, start + 6, left - 4, top, 0xff8fcbdc, 0);
        rail(g, left + width + 4, end - 6, top, 0xffbc9cd8, 17);
        StarLifterTerminalGlyphs.draw(g, "inventory", start + 7, top + 12, 13, 0xffa6d4df);
        var font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().translate(start + 7, top + 30, 0);
        g.pose().scale(.83f, .83f, 1);
        g.drawString(font, Component.translatable(StarLifterTerminal.TEXT + "inventory"), 0, 0, 0xffc5cce4, false);
        g.pose().popPose();
    }

    private void rail(GuiGraphics g, int left, int right, int top, int color, int phase) {
        g.fill(left, top + 51, right, top + 52, 0xff333e5c);
        g.fill(left, top + 61, right, top + 62, 0xff272f48);
        int run = right - left - 7;
        int offset = animated ? (int) ((now / 90 + phase) % (run * 2)) : phase % run;
        int x = left + (offset > run ? 2 * run - offset : offset);
        g.fill(x, top + 51, x + 6, top + 52, color);
        g.fill(x - 1, top + 50, x + 3, top + 51, color & 0x00ffffff | 0x38000000);
    }

    private void drawSlot(GuiGraphics g, int mouseX, int mouseY, float x, float y, int width, int height) {
        int left = Math.round(x), top = Math.round(y);
        boolean over = mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + height;
        float focused = animated ? hover.follow(left * 65_537 + top, over ? 1 : 0, now) : over ? 1 : 0;
        focused = Mth.clamp(focused, 0, 1);
        int ground = FastColor.ARGB32.lerp(focused, 0xff1a233a, 0xff273a54);
        int border = FastColor.ARGB32.lerp(focused, 0xff374560, 0xff98d4e0);
        g.fill(left + 1, top + 1, left + width - 1, top + height - 1, ground);
        g.renderOutline(left, top, width, height, border);
        if (focused > 0.02f) {
            int reach = 2 + (int) (focused * 4);
            g.fill(left, top, left + reach, top + 1, 0xffcaedf4);
            g.fill(left + width - reach, top + height - 1, left + width, top + height, 0xffcaedf4);
        }
    }
}
