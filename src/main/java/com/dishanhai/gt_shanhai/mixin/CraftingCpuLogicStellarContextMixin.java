package com.dishanhai.gt_shanhai.mixin;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.service.CraftingService;
import appeng.api.networking.energy.IEnergyService;
import net.minecraft.world.level.Level;

import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternCraftingContext;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CraftingCpuLogic.class, priority = 1500, remap = false)
public abstract class CraftingCpuLogicStellarContextMixin {

    @Shadow
    @Nullable
    private ExecutingCraftingJob job;
    @Shadow @Final private appeng.me.cluster.implementations.CraftingCPUCluster cluster;

    // 山海：包住 CPU 本輪上下文，讓 Enhancedcore 仍可攔截 provider.pushPattern。
    @WrapMethod(method = "executeCrafting", remap = false)
    private int gtShanhai$pushPatternWithStellarContext(int budget, CraftingService service,
            IEnergyService energy, Level level, Operation<Integer> original) {
        ExecutingCraftingJobAccessor current = job == null ? null : (ExecutingCraftingJobAccessor) job;
        StellarPatternCraftingContext.pushOrder(current == null ? null : new StellarPatternCraftingContext.Order(
                current.gtShanhai$getLink(), current.gtShanhai$getFinalOutput(), current::gtShanhai$stellarRemainingAmount,
                current.gtShanhai$getPlayerId(), cluster.getName() == null ? "AE CPU" : cluster.getName().getString()));
        try {
            return original.call(budget, service, energy, level);
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
                        level.getServer(), current.gtShanhai$getLink(),
                        success ? com.dishanhai.gt_shanhai.common.machine.part.StellarPatternOrderInfo.State.COMPLETED
                                : com.dishanhai.gt_shanhai.common.machine.part.StellarPatternOrderInfo.State.CANCELLED,
                        current.gtShanhai$stellarRemainingAmount());
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
