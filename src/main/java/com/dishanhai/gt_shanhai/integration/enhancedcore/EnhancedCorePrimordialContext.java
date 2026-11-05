package com.dishanhai.gt_shanhai.integration.enhancedcore;

// Author: dishanhai
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gtl.enhancedcore.common.recipe.iv.*;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;

import java.util.*;

/** Server-thread, order-local material view while the original primordial logic runs. */
public final class EnhancedCorePrimordialContext implements AutoCloseable {
    private static final ThreadLocal<EnhancedCorePrimordialContext> CURRENT = new ThreadLocal<>();
    public final PrimordialThreadedRecipeLogic logic;
    public final IvJob job;
    private final Map<AEKey, Long> inventory;
    private final Map<AEKey, Long> virtual;
    private final boolean admission;
    private long plannedParallel;
    private boolean committed;

    private EnhancedCorePrimordialContext(PrimordialThreadedRecipeLogic logic,
            MEPatternBufferPartMachine buffer, IvJob job, boolean admission) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested primordial cloud material scope");
        this.logic = logic;
        this.job = job;
        this.admission = admission;
        inventory = new LinkedHashMap<>(job.inventory);
        virtual = admission ? IvBuffers.availableVirtual(buffer, job) : Map.of();
        CURRENT.set(this);
    }

    public static EnhancedCorePrimordialContext begin(PrimordialThreadedRecipeLogic logic,
            MEPatternBufferPartMachine buffer, IvJob job, boolean admission) {
        return new EnhancedCorePrimordialContext(logic, buffer, job, admission);
    }

    public static EnhancedCorePrimordialContext forMachine(Object holder) {
        var context = CURRENT.get();
        return context != null && context.logic.getMachine() == holder ? context : null;
    }

    public static Set<GTRecipe> candidates(Object logic) {
        var context = CURRENT.get();
        if (context == null || context.logic != logic || !context.admission) return null;
        GTRecipe recipe = context.job.recipe;
        return recipe != null && context.logic.checkCloudCandidate(recipe) ? Set.of(recipe) : Set.of();
    }

    public long maxParallel(GTRecipe recipe, long limit) {
        if (!admission) return 0;
        long parallel = IvRecipeInputs.maxParallel(recipe, inventory, virtual, Math.min(limit, job.remaining));
        plannedParallel = parallel;
        return parallel;
    }

    /** Null means a tick capability or unrelated operation which retains native handling. */
    public RecipeResult handle(IO io, Map<RecipeCapability<?>, List<Content>> contents,
                               GTRecipe recipe, boolean simulate) {
        if (contents != recipe.inputs && contents != recipe.outputs) return null;
        if (io == IO.OUT) return RecipeResult.SUCCESS;
        if (!admission) return recipe.inputs.isEmpty() ? RecipeResult.SUCCESS : RecipeResult.fail(null);
        Map<AEKey, Long> payment = IvRecipeInputs.plan(contents, inventory, virtual, 1);
        if (payment == null) return RecipeResult.fail(null);
        if (!simulate) {
            IvRecipeInputs.subtract(inventory, payment);
            committed = true;
        }
        return RecipeResult.SUCCESS;
    }

    public long plannedParallel() { return plannedParallel; }

    public void commitInventory() {
        // Input-free recipes still own a finite number of operations from their accepted AE order.
        if (!committed && IvRecipeInputs.hasConsumedInputs(job.recipe))
            throw new IllegalStateException("Primordial batch bypassed cloud material commit");
        job.inventory.clear();
        job.inventory.putAll(inventory);
    }

    @Override public void close() { CURRENT.remove(); }
}
