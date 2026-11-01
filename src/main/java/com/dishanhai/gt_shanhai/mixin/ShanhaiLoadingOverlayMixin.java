package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingProfiler;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraftforge.client.loading.ForgeLoadingOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 山海：保留 Forge 完成與錯誤回呼，只在原生繪製後接管視覺。 */
@Mixin(value = ForgeLoadingOverlay.class, remap = false)
public abstract class ShanhaiLoadingOverlayMixin {
    @Shadow @Final private ReloadInstance reload;
    @Shadow private long fadeOutStart;

    @Inject(method = {"render", "m_88315_"}, at = @At("RETURN"), remap = false)
    private void shanhai$startup(GuiGraphics graphics, int mouseX, int mouseY, float tick, CallbackInfo ci) {
        if (fadeOutStart >= 0) ShanhaiLoadingProfiler.finishStartup();
        if (Minecraft.getInstance().getOverlay() != (Object) this) return;
        float fadeTime = fadeOutStart < 0 ? -1 : (Util.getMillis() - fadeOutStart) / 1000f;
        float opacity = 1 - Math.max(0, Math.min(1, fadeTime - 1));
        ShanhaiLoadingClient.renderStartup(graphics, reload.getActualProgress(), opacity, fadeTime >= 1);
    }
}
