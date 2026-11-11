package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import com.dishanhai.gt_shanhai.common.ae2.GtlCoreCraftingPlans;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import org.gtlcore.gtlcore.utils.NumberUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/** Extracts only real initial materials; virtual presence never becomes CPU-owned inventory. */
public final class VirtualCraftingInitialItemExtractor {

    @Nullable
    public static GenericStack preparePresence(ICraftingPlan plan, IGrid grid, ListCraftingInventory inventory,
            IActionSource src) {
        KeyCounter used = plan.usedItems();
        return preparePresence(plan, grid, inventory, src, used, realInitialItems(plan, used, grid, src));
    }

    @Nullable
    private static GenericStack preparePresence(ICraftingPlan plan, IGrid grid, ListCraftingInventory inventory,
            IActionSource src, KeyCounter used, KeyCounter real) {
        MEStorage storage = grid.getStorageService().getInventory();
        Object2LongMap<AEKey> requirements = VirtualPatternEncodingHelper.collectPresenceRequirements(plan);
        Object2LongMap<AEKey> networkRequirements = minimumExternalPresence(plan, requirements);
        Object2LongOpenHashMap<AEKey> externalPresence = new Object2LongOpenHashMap<>();
        externalPresence.defaultReturnValue(0L);
        ToLongFunction<AEKey> supplyPresence = VirtualProviderSoftLocks.snapshotSupplyPresence(grid);
        if (plan instanceof VirtualProviderSoftLocks.RequirementsAccess access) {
            GenericStack missing = VirtualProviderSoftLocks.missingSupplyRequirement(
                    access.gtShanhai$getSupplyRequirements(), supplyPresence);
            if (missing != null) return missing;
        }

        VirtualCraftingPresenceState.clear(inventory);
        for (Object2LongMap.Entry<AEKey> requirement : requirements.object2LongEntrySet()) {
            AEKey key = requirement.getKey();
            long needed = requirement.getLongValue();
            long available = Math.max(supplyPresence.applyAsLong(key),
                    storage.extract(key, needed, Actionable.SIMULATE, src));
            long external = Math.min(needed, Math.max(0L, available));
            long plannedExternal = Math.max(networkRequirements.getLong(key),
                    Math.min(needed, Math.max(0L, used.get(key)) - Math.max(0L, real.get(key))));
            if (plannedExternal > external) return new GenericStack(key, plannedExternal - external);
            if (external > 0L) externalPresence.put(key, external);
        }
        VirtualCraftingPresenceState.begin(inventory, externalPresence);
        return null;
    }

    private static Object2LongMap<AEKey> minimumExternalPresence(ICraftingPlan plan,
            Object2LongMap<AEKey> presence) {
        Object2LongMap<AEKey> required = new Object2LongOpenHashMap<>();
        if (presence.isEmpty() || plan.simulation()) return required;
        required.putAll(presence);
        for (Object2LongMap.Entry<AEKey> emitted : plan.emittedItems()) {
            subtractPossibleSupply(required, emitted.getKey(), emitted.getLongValue());
        }

        // An upper bound on planned production gives the minimum quantity needed from existing stock.
        Map<AEKey, DependentSupply> dependent = new HashMap<>();
        Set<AEKey> returned = new HashSet<>();
        for (Map.Entry<IPatternDetails, Long> task : plan.patternTimes().entrySet()) {
            if (required.isEmpty()) break;
            if (task.getValue() <= 0L) continue;
            IPatternDetails details = task.getKey();
            long times = task.getValue();
            for (GenericStack output : details.getOutputs()) {
                countPlannedSupply(required, dependent, details, output.what(),
                        NumberUtils.saturatedMultiply(output.amount(), times));
            }
            for (IPatternDetails.IInput input : details.getInputs()) {
                if (required.isEmpty()) break;
                if (VirtualPatternEncodingHelper.isPresenceInput(input)) continue;
                long returns = NumberUtils.saturatedMultiply(input.getMultiplier(), times);
                for (GenericStack template : input.getPossibleInputs()) {
                    AEKey key = input.getRemainingKey(template.what());
                    if (key != null && returned.add(key)) {
                        countPlannedSupply(required, dependent, details, key, returns);
                    }
                }
                returned.clear();
            }
        }
        for (Object2LongMap.Entry<AEKey> entry : required.object2LongEntrySet()) {
            DependentSupply supply = dependent.get(entry.getKey());
            if (supply == null) continue;
            long withoutSelfProduction = entry.getLongValue();
            long independent = presence.getLong(entry.getKey()) - withoutSelfProduction;
            long deficit = Math.max(withoutSelfProduction - supply.amount(), supply.seed() - independent);
            entry.setValue(Math.max(0L, deficit));
        }
        return required;
    }

    private static void countPlannedSupply(Object2LongMap<AEKey> required, Map<AEKey, DependentSupply> dependent,
            IPatternDetails pattern, AEKey key, long amount) {
        if (required.getLong(key) <= 0L || amount <= 0L) return;
        long seed = 0L;
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            if (!VirtualPatternEncodingHelper.isPresenceInput(input)) continue;
            for (GenericStack template : input.getPossibleInputs()) {
                if (key.equals(template.what())) seed = Math.max(seed, input.getMultiplier());
            }
        }
        if (seed > 0L) {
            dependent.merge(key, new DependentSupply(seed, amount), DependentSupply::merge);
        } else {
            subtractPossibleSupply(required, key, amount);
        }
    }

    private record DependentSupply(long seed, long amount) {
        private DependentSupply merge(DependentSupply other) {
            return new DependentSupply(Math.min(seed, other.seed), NumberUtils.saturatedAdd(amount, other.amount));
        }
    }

    private static void subtractPossibleSupply(Object2LongMap<AEKey> required, AEKey key, long amount) {
        long needed = required.getLong(key);
        if (needed <= 0L || amount <= 0L) return;
        if (amount >= needed) required.removeLong(key);
        else required.put(key, needed - amount);
    }

    @Nullable
    public static GenericStack extract(ICraftingPlan plan, IGrid grid, ListCraftingInventory inventory,
            IActionSource src) {
        KeyCounter used = plan.usedItems();
        KeyCounter real = realInitialItems(plan, used, grid, src);
        GenericStack missing = preparePresence(plan, grid, inventory, src, used, real);
        if (missing != null) return missing;

        MEStorage storage = grid.getStorageService().getInventory();
        for (Object2LongMap.Entry<AEKey> entry : real) {
            AEKey what = entry.getKey();
            long required = entry.getLongValue();
            if (required <= 0L) continue;
            long available = storage.extract(what, required, Actionable.SIMULATE, src);
            if (available < required) {
                VirtualCraftingPresenceState.clear(inventory);
                return new GenericStack(what, required - available);
            }
        }
        for (Object2LongMap.Entry<AEKey> entry : real) {
            AEKey what = entry.getKey();
            long realAmount = entry.getLongValue();
            if (realAmount <= 0L) continue;

            long extracted = storage.extract(what, realAmount, Actionable.MODULATE, src);
            inventory.insert(what, extracted, Actionable.MODULATE);
            if (extracted < realAmount) {
                rollback(storage, inventory, src);
                VirtualCraftingPresenceState.clear(inventory);
                return new GenericStack(what, realAmount - extracted);
            }
        }
        return null;
    }

    /** Uses the same physical initial inputs for preview reservation and CPU submission. */
    public static KeyCounter realInitialItems(ICraftingPlan plan, KeyCounter used, IGrid grid, IActionSource src) {
        Player player = src.player().orElse(null);
        IGridNode pivot = grid.getPivot();
        Level level = player != null ? player.level() : pivot == null ? null : pivot.getLevel();
        return realInitialItems(plan, used, level);
    }

    public static KeyCounter realInitialItems(ICraftingPlan plan, KeyCounter used, @Nullable Level level) {
        if (GtlCoreCraftingPlans.isGraphPlan(plan)) return used;
        Object2LongMap<AEKey> presence = VirtualPatternEncodingHelper.collectPresenceRequirements(plan);
        if (presence.isEmpty()) return used;
        KeyCounter presenceKeys = new KeyCounter();
        for (Object2LongMap.Entry<AEKey> requirement : presence.object2LongEntrySet()) {
            AEKey key = requirement.getKey();
            if (used.get(key) > 0L) presenceKeys.add(key, requirement.getLongValue());
        }
        if (presenceKeys.isEmpty()) return used;

        Object2LongMap<AEKey> consumable =
                VirtualPatternEncodingHelper.collectConsumableRequirements(plan, presenceKeys, level);
        KeyCounter real = new KeyCounter();
        for (Object2LongMap.Entry<AEKey> entry : used) {
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            if (presence.containsKey(key)) {
                amount = realInitialAmount(amount, consumable.getLong(key));
            }
            if (amount > 0L) real.add(key, amount);
        }
        return real;
    }

    static long realInitialAmount(long usedAmount, long consumableRequirement) {
        return Math.min(Math.max(0L, usedAmount), Math.max(0L, consumableRequirement));
    }

    private static void rollback(MEStorage storage, ListCraftingInventory inventory, IActionSource source) {
        for (Object2LongMap.Entry<AEKey> stored : inventory.list) {
            storage.insert(stored.getKey(), stored.getLongValue(), Actionable.MODULATE, source);
        }
        inventory.clear();
    }

    private VirtualCraftingInitialItemExtractor() {}
}
