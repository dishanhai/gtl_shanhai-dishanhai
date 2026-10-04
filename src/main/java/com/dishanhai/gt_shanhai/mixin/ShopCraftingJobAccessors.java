package com.dishanhai.gt_shanhai.mixin;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the native job's undelivered output (author: dishanhai). */
public final class ShopCraftingJobAccessors {
    private ShopCraftingJobAccessors() {}

    @Mixin(value = CraftingCpuLogic.class, remap = false)
    public interface CpuLogic {
        @Accessor("job")
        ExecutingCraftingJob gtShanhai$getJob();
    }

    @Mixin(value = ExecutingCraftingJob.class, remap = false)
    public interface Job {
        @Accessor("remainingAmount")
        long gtShanhai$getRemainingAmount();
    }
}
