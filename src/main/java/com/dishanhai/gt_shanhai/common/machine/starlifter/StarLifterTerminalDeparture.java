package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.preview.StarLifterTerminalPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.dishanhai.sh_better_ui.client.api.ShBetterUiApi.motion;

/** Passive HUD effect. The container and its input have already been released. */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class StarLifterTerminalDeparture {
    private static final int DURATION = 220;
    private static Departure active;

    record Bounds(int x, int y, int width, int height) {
        Bounds contract(int centerX, int centerY, float t) {
            int left = Mth.lerpInt(t, x, centerX);
            int top = Mth.lerpInt(t, y, centerY);
            int right = Mth.lerpInt(t, x + width, centerX);
            int bottom = Mth.lerpInt(t, y + height, centerY);
            return new Bounds(left, top, right - left, bottom - top);
        }

        void fill(GuiGraphics g, int color) { g.fill(x, y, x + width, y + height, color); }
    }

    private record Departure(ClientLevel level, long start, Bounds shell, Bounds viewport, boolean preview) {}

    private StarLifterTerminalDeparture() {}

    static boolean begin(Bounds shell, Bounds viewport, boolean preview) {
        var level = Minecraft.getInstance().level;
        if (level == null) return false;
        active = new Departure(level, motion().nowMs(), shell, viewport, preview);
        return true;
    }

    static void clear() {
        if (active == null) return;
        active = null;
        StarLifterTerminalPreview.closeFrame();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        var state = active;
        if (state != null && event.phase == TickEvent.Phase.END) {
            var client = Minecraft.getInstance();
            if (client.level != state.level || client.screen != null || motion().nowMs() - state.start >= DURATION) clear();
        }
    }

    @SubscribeEvent
    public static void draw(RenderGuiEvent.Post event) {
        var state = active;
        if (state == null) return;
        var client = Minecraft.getInstance();
        if (client.screen != null || client.level != state.level) return;
        float elapsed = (float) (motion().nowMs() - state.start) / DURATION;
        if (elapsed >= 1) {
            clear();
            return;
        }
        var g = event.getGuiGraphics();
        var viewport = state.viewport;
        int cx = viewport.x + viewport.width / 2, cy = viewport.y + viewport.height / 2;
        float vanishing = StarLifterTerminal.phase(elapsed, .12f, 1);
        var shell = state.shell.contract(cx, cy, vanishing);
        shell.fill(g, Math.round(171 * (1 - vanishing)) << 24 | 0x11172a);
        g.renderOutline(shell.x, shell.y, shell.width, shell.height, Math.round(195 * (1 - vanishing)) << 24 | 0x597594);
        float retreat = StarLifterTerminal.phase(elapsed, 0, .86f);
        var image = viewport.contract(cx, cy, retreat);
        if (state.preview && image.width > 1 && image.height > 1) {
            StarLifterTerminalPreview.drawRetained(g, image.x, image.y, image.width, image.height, 1 - retreat);
        }
    }
}
