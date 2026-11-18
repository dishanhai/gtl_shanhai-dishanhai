// Author: dishanhai
package com.dishanhai.gt_shanhai.client.time.hud;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.sh_better_ui.kernel.api.hud.TimelineBarRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only presentation of the server's anchor state. */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiAnchorBarHud {
    private ShanhaiAnchorBarHud() {}

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
        if (!VanillaGuiOverlay.EXPERIENCE_BAR.id().equals(event.getOverlay().id())) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) return;
        ShanhaiAnchorBarState state = ShanhaiAnchorBarCache.stateOf(true);
        if (!state.visible()) return;
        int width = Math.min(182, Math.max(0, event.getWindow().getGuiScaledWidth() - 8));
        int x = (event.getWindow().getGuiScaledWidth() - width) / 2;
        int y = event.getWindow().getGuiScaledHeight() - 45;
        boolean reverse = state.mode() == ShanhaiAnchorBarState.Mode.REWIND;
        Component label = switch (state.mode()) {
            case WINDOW -> Component.translatable("gt_shanhai.time.anchor.window", state.value());
            case REWIND -> Component.translatable("gt_shanhai.time.anchor.rewind", state.value());
            case EXPIRED -> Component.translatable("gt_shanhai.time.anchor.expired");
            case NONE -> null;
        };
        int fill = reverse ? 0xC83CD8E6 : 0xC8F0B95A;
        TimelineBarRenderer.draw(event.getGuiGraphics(), mc.font, x, y, width, 6,
                state.fraction(), reverse, 0x8A101014, fill, label, 0xFFFFFFFF);
    }
}
