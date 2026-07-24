package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.common.RepoSlot;
import appeng.menu.me.common.GridInventoryEntry;

import com.dishanhai.gt_shanhai.client.ae.AeTerminalFavorites;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MEStorageScreen.class, remap = false)
public abstract class AeTerminalFavoritesScreenMixin {

    @Inject(method = "m_280092_", at = @At("RETURN"), remap = false)
    private void gtShanhai$renderFavoriteStar(GuiGraphics guiGraphics, Slot slot, CallbackInfo ci) {
        if (!(slot instanceof RepoSlot repoSlot)) return;
        GridInventoryEntry entry = repoSlot.getEntry();
        if (entry == null || !AeTerminalFavorites.contains(entry.getWhat())) return;
        guiGraphics.drawString(Minecraft.getInstance().font, "★", slot.x + 9, slot.y - 2, 0xFFFF55, true);
    }
}
