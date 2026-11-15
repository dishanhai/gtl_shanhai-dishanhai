package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import org.cgse.core.GraphCompiler;
import org.cgse.core.GraphRecipe;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.MissingCraftingPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.AeGraphPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/** Keeps externally available virtual targets outside GRAPH's owned inventory. */
public final class GraphVirtualPresence {

    public interface SnapshotAccess {
        void gtShanhai$setExternalPresence(ToLongFunction<AEKey> presence);
        long gtShanhai$getExternalPresence(AEKey key);
    }

    public static GraphCompiler<AEKey> externalize(GraphCompiler<AEKey> compiler,
            Map<String, IPatternDetails> bindings, ToLongFunction<AEKey> available) {
        List<GraphRecipe<AEKey>> recipes = new ArrayList<>(compiler.catalog().size());
        boolean changed = false;
        for (GraphRecipe<AEKey> recipe : compiler.catalog()) {
            GraphRecipe<AEKey> physical = externalize(recipe, bindings.get(recipe.binding()), available);
            recipes.add(physical);
            changed |= physical != recipe;
        }
        return changed ? new GraphCompiler<>(recipes) : compiler;
    }

    static GraphRecipe<AEKey> externalize(GraphRecipe<AEKey> recipe, IPatternDetails pattern,
            ToLongFunction<AEKey> available) {
        if (pattern == null || !VirtualPatternEncodingHelper.containsVirtualProviderPattern(pattern)) return recipe;
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        List<GraphRecipe.Slot<AEKey>> slots = new ArrayList<>();
        Map<AEKey, Long> outputs = new LinkedHashMap<>(recipe.outputs());
        boolean changed = false;
        for (int index = 0; index < recipe.slots().size(); index++) {
            GraphRecipe.Slot<AEKey> slot = recipe.slots().get(index);
            int inputSlot = slot.inputSlot() < 0 ? index : slot.inputSlot();
            if (inputSlot < inputs.length && slot.reusable()
                    && VirtualPatternEncodingHelper.isPresenceInput(inputs[inputSlot])
                    && available.applyAsLong(slot.key()) >= inputs[inputSlot].getMultiplier()) {
                long remaining = Math.subtractExact(outputs.get(slot.key()), slot.amount());
                if (remaining == 0L) outputs.remove(slot.key());
                else outputs.put(slot.key(), remaining);
                changed = true;
            } else {
                // Preserve the original pattern slot after removing an earlier presence slot.
                slots.add(new GraphRecipe.Slot<>(slot.key(), slot.amount(), inputSlot,
                        slot.configuration(), slot.reusable()));
            }
        }
        return changed ? new GraphRecipe<>(recipe.id(), recipe.binding(), slots, outputs) : recipe;
    }

    public static boolean isExternalInput(IPatternDetails pattern, GraphRecipe<AEKey> recipe, int inputSlot) {
        if (!VirtualPatternEncodingHelper.isPresenceInput(pattern.getInputs()[inputSlot])) return false;
        for (int index = 0; index < recipe.slots().size(); index++) {
            GraphRecipe.Slot<AEKey> slot = recipe.slots().get(index);
            if ((slot.inputSlot() < 0 ? index : slot.inputSlot()) == inputSlot) return false;
        }
        return true;
    }

    public static GenericStack missingExternalPresence(ICraftingPlan supplied, IGrid grid, IActionSource source) {
        ICraftingPlan plan = supplied instanceof MissingCraftingPlan missing ? missing.delegate() : supplied;
        if (!(plan instanceof AeGraphPlan graph)) return null;
        Map<AEKey, Long> requirements = new LinkedHashMap<>();
        for (String id : graph.graph().patternTimes().keySet()) {
            GraphRecipe<AEKey> recipe = graph.graph().recipes().get(id);
            collectExternalRequirements(graph.bindings().get(recipe.binding()), recipe, requirements);
        }
        return missing(requirements, grid, source, Map.of());
    }

    /** fundedRuns has already been limited by the job's real owned inventory. */
    public static GenericStack missingExternalPresence(IPatternDetails pattern, GraphRecipe<AEKey> recipe,
            long fundedRuns, IGrid grid, IActionSource source) {
        Map<AEKey, Long> requirements = new LinkedHashMap<>();
        collectExternalRequirements(pattern, recipe, requirements);
        return missing(requirements, grid, source, recipe.dispatchInputs(fundedRuns));
    }

    private static void collectExternalRequirements(IPatternDetails pattern, GraphRecipe<AEKey> recipe,
            Map<AEKey, Long> requirements) {
        if (pattern == null) return;
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        for (int index = 0; index < inputs.length; index++) {
            if (!isExternalInput(pattern, recipe, index)) continue;
            GenericStack target = inputs[index].getPossibleInputs()[0];
            requirements.merge(target.what(), inputs[index].getMultiplier(), Math::max);
        }
    }

    private static GenericStack missing(Map<AEKey, Long> requirements, IGrid grid, IActionSource source,
            Map<AEKey, Long> fundedInputs) {
        if (requirements.isEmpty()) return null;
        boolean supplyMode = VirtualProviderSoftLocks.usesSupplyMachineMode();
        ToLongFunction<AEKey> supply = VirtualProviderSoftLocks.snapshotSupplyPresence(grid);
        var storage = grid.getStorageService().getInventory();
        for (Map.Entry<AEKey, Long> requirement : requirements.entrySet()) {
            long needed = requirement.getValue();
            long available = supplyMode ? supply.applyAsLong(requirement.getKey())
                    : storage.extract(requirement.getKey(), needed, Actionable.SIMULATE, source);
            long deficit = needed - available;
            long held = supplyMode ? 0L : fundedInputs.getOrDefault(requirement.getKey(), 0L);
            if (held < deficit) return new GenericStack(requirement.getKey(), deficit - held);
        }
        return null;
    }

    private GraphVirtualPresence() {}
}
