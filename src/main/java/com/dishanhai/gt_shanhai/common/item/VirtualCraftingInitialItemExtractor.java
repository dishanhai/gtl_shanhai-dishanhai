package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.api.storage.MEStorage;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import org.jetbrains.annotations.Nullable;

/** Extracts only real initial materials; virtual presence never becomes CPU-owned inventory. */
public final class VirtualCraftingInitialItemExtractor {

    @Nullable
    public static GenericStack extract(ICraftingPlan plan, IGrid grid, ListCraftingInventory inventory,
            IActionSource src) {
        MEStorage storage = grid.getStorageService().getInventory();
        Object2LongMap<AEKey> requirements = VirtualPatternEncodingHelper.collectPresenceRequirements(plan);
        Object2LongMap<AEKey> consumableRequirements =
                VirtualPatternEncodingHelper.collectConsumableRequirements(plan);
        Object2LongOpenHashMap<AEKey> externalPresence = new Object2LongOpenHashMap<>();
        externalPresence.defaultReturnValue(0L);

        VirtualCraftingPresenceState.clear(inventory);
        for (Object2LongMap.Entry<AEKey> requirement : requirements.object2LongEntrySet()) {
            AEKey key = requirement.getKey();
            long needed = requirement.getLongValue();
            long available = storage.extract(key, needed, Actionable.SIMULATE, src);
            long external = Math.min(needed, Math.max(0L, available));
            if (external > 0L) externalPresence.put(key, external);
        }
        VirtualCraftingPresenceState.begin(inventory, externalPresence);

        for (Object2LongMap.Entry<AEKey> entry : plan.usedItems()) {
            AEKey what = entry.getKey();
            long usedAmount = entry.getLongValue();
            long realAmount = realInitialAmount(usedAmount, consumableRequirements.getLong(what));
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
