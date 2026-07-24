package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;

import com.dishanhai.gt_shanhai.common.item.CraftingPlanVirtualMarkerAccess;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = CraftConfirmTableRenderer.class, remap = false)
public class CraftConfirmTableRendererVirtualMarkerMixin {

    @Inject(method = "getEntryDescription", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$addVirtualPresenceDescription(CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> cir) {
        if (!gtShanhai$isVirtualPresence(entry)) return;
        List<Component> lines = new ArrayList<>(cir.getReturnValue());
        lines.add(0, Component.translatable("gui.gt_shanhai.crafting_plan.virtual_presence")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        cir.setReturnValue(lines);
    }

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$addVirtualPresenceTooltip(CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> cir) {
        if (!gtShanhai$isVirtualPresence(entry)) return;
        List<Component> lines = new ArrayList<>(cir.getReturnValue());
        lines.add(Component.translatable("gui.gt_shanhai.crafting_plan.virtual_presence.detail")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        cir.setReturnValue(lines);
    }

    private static boolean gtShanhai$isVirtualPresence(CraftingPlanSummaryEntry entry) {
        return entry instanceof CraftingPlanVirtualMarkerAccess access
                && access.gtShanhai$isVirtualPresence();
    }
}
