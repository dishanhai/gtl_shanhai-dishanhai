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
import org.spongepowered.asm.mixin.Final;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CraftingCpuLogic.class, priority = 1500, remap = false)
public abstract class CraftingCpuLogicStellarContextMixin {

    @Shadow
    @Nullable
    private ExecutingCraftingJob job;
    @Shadow @Final private appeng.me.cluster.implementations.CraftingCPUCluster cluster;

    @Redirect(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"),
            remap = false)
    private boolean gtShanhai$pushPatternWithStellarContext(ICraftingProvider provider, IPatternDetails details,
            KeyCounter[] inputHolder) {
        ExecutingCraftingJobAccessor current = job == null ? null : (ExecutingCraftingJobAccessor) job;
        StellarPatternCraftingContext.pushOrder(current == null ? null : new StellarPatternCraftingContext.Order(
                current.gtShanhai$getLink(), current.gtShanhai$getFinalOutput(), current::gtShanhai$stellarRemainingAmount,
                current.gtShanhai$getPlayerId(), cluster.getName() == null ? "AE CPU" : cluster.getName().getString()));
        try {
            return provider.pushPattern(details, inputHolder);
        } finally {
            StellarPatternCraftingContext.pop();
        }
    }

    @WrapMethod(method = "finishJob", remap = false)
    private void gtShanhai$observeActualOrderOutcome(boolean success, Operation<Void> original) {
        ExecutingCraftingJobAccessor current = job == null ? null : (ExecutingCraftingJobAccessor) job;
        try { original.call(success); }
        finally {
            if (current != null && cluster.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                com.dishanhai.gt_shanhai.common.machine.part.StellarPatternWarningHistory.observeTerminal(
                        level.getServer(), current.gtShanhai$getLink(), current.gtShanhai$stellarRemainingAmount());
            }
        }
    }

    @Mixin(value = ExecutingCraftingJob.class, remap = false)
    public interface ExecutingCraftingJobAccessor {

        @Accessor("playerId")
        @Nullable
        Integer gtShanhai$getPlayerId();

        @Accessor("link") appeng.crafting.CraftingLink gtShanhai$getLink();
        @Accessor("finalOutput") appeng.api.stacks.GenericStack gtShanhai$getFinalOutput();
        @Accessor("remainingAmount") long gtShanhai$stellarRemainingAmount();
    }
}
