package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;

import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternCraftingContext;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CraftingCpuLogic.class, priority = 1500, remap = false)
public abstract class CraftingCpuLogicStellarContextMixin {

    @Shadow
    @Nullable
    private ExecutingCraftingJob job;

    @Redirect(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"),
            remap = false)
    private boolean gtShanhai$pushPatternWithStellarContext(ICraftingProvider provider, IPatternDetails details,
            KeyCounter[] inputHolder) {
        Integer playerId = job == null ? null : ((ExecutingCraftingJobAccessor) job).gtShanhai$getPlayerId();
        StellarPatternCraftingContext.push(playerId);
        try {
            return provider.pushPattern(details, inputHolder);
        } finally {
            StellarPatternCraftingContext.pop();
        }
    }

    @Mixin(value = ExecutingCraftingJob.class, remap = false)
    public interface ExecutingCraftingJobAccessor {

        @Accessor("playerId")
        @Nullable
        Integer gtShanhai$getPlayerId();
    }
}
