package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;

import com.dishanhai.gt_shanhai.common.item.CraftingPlanVirtualMarkerAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.network.FriendlyByteBuf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class CraftingPlanVirtualMarkerMixins {

    private CraftingPlanVirtualMarkerMixins() {}

    @Mixin(value = CraftingPlanSummaryEntry.class, remap = false)
    public static class Entry implements CraftingPlanVirtualMarkerAccess {

        @Unique
        private boolean gtShanhai$virtualPresence;

        @Override
        public boolean gtShanhai$isVirtualPresence() {
            return gtShanhai$virtualPresence;
        }

        @Override
        public void gtShanhai$setVirtualPresence(boolean virtualPresence) {
            gtShanhai$virtualPresence = virtualPresence;
        }

        @Inject(method = "write", at = @At("TAIL"), remap = false)
        private void gtShanhai$writeVirtualPresence(FriendlyByteBuf buffer, CallbackInfo ci) {
            buffer.writeBoolean(gtShanhai$virtualPresence);
        }

        @Inject(method = "read", at = @At("RETURN"), remap = false)
        private static void gtShanhai$readVirtualPresence(FriendlyByteBuf buffer,
                CallbackInfoReturnable<CraftingPlanSummaryEntry> cir) {
            CraftingPlanSummaryEntry entry = cir.getReturnValue();
            if (entry instanceof CraftingPlanVirtualMarkerAccess access) {
                access.gtShanhai$setVirtualPresence(buffer.readBoolean());
            }
        }
    }

    @Mixin(value = CraftingPlanSummary.class, remap = false)
    public static class Summary {

        @Inject(method = "fromJob", at = @At("RETURN"), remap = false)
        private static void gtShanhai$markVirtualPresenceEntries(IGrid grid, IActionSource actionSource,
                ICraftingPlan job, CallbackInfoReturnable<CraftingPlanSummary> cir) {
            Object2LongMap<AEKey> requirements = VirtualPatternEncodingHelper.collectPresenceRequirements(job);
            if (requirements.isEmpty()) return;
            CraftingPlanSummary summary = cir.getReturnValue();
            if (summary == null) return;
            for (CraftingPlanSummaryEntry entry : summary.getEntries()) {
                if (requirements.containsKey(entry.getWhat()) &&
                        entry instanceof CraftingPlanVirtualMarkerAccess access) {
                    access.gtShanhai$setVirtualPresence(true);
                }
            }
        }
    }
}
