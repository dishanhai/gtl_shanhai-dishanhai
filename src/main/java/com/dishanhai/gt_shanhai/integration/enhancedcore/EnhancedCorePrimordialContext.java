package com.dishanhai.gt_shanhai.integration.enhancedcore;

// Author: dishanhai
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gtl.enhancedcore.common.recipe.iv.*;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.api.recipe.RecipeExtensionCopier;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;

import java.util.*;
import java.util.function.UnaryOperator;

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
        if (io == IO.OUT) {
            if (!simulate) replaceCompletion(job.completion, IvRecipeInputs.outputs(contents));
            return RecipeResult.SUCCESS;
        }
        if (!admission) return recipe.inputs.isEmpty() ? RecipeResult.SUCCESS : RecipeResult.fail(null);
        Map<AEKey, Long> payment = IvRecipeInputs.plan(contents, inventory, virtual, 1);
        if (payment == null) return RecipeResult.fail(null);
        if (!simulate) {
            var rolled = rollConsumedInputs(contents, consumable -> {
                GTRecipe inputRoll = recipe.copy();
                RecipeExtensionCopier.copy(recipe, inputRoll);
                inputRoll.outputs.clear();
                inputRoll.outputs.putAll(consumable);
                return IParallelLogic.getRecipeOutputChance(logic.getMachine(), inputRoll).outputs;
            });
            payment = IvRecipeInputs.plan(rolled, inventory, Map.of(), 1);
            if (payment == null) return RecipeResult.fail(null);
            IvRecipeInputs.subtract(inventory, payment);
            committed = true;
        }
        return RecipeResult.SUCCESS;
    }

    static Map<RecipeCapability<?>, List<Content>> rollConsumedInputs(
            Map<RecipeCapability<?>, List<Content>> contents,
            UnaryOperator<Map<RecipeCapability<?>, List<Content>>> nativeRoll) {
        Map<RecipeCapability<?>, List<Content>> consumable = new LinkedHashMap<>();
        contents.forEach((capability, values) -> {
            var positive = values.stream().filter(content -> content.chance > 0).toList();
            if (!positive.isEmpty()) consumable.put(capability, positive);
        });
        return nativeRoll.apply(consumable);
    }

    static <K> void replaceCompletion(Map<K, Long> completion, Map<K, Long> finalOutputs) {
        Map<K, Long> validated = new LinkedHashMap<>(finalOutputs);
        completion.clear();
        completion.putAll(validated);
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
