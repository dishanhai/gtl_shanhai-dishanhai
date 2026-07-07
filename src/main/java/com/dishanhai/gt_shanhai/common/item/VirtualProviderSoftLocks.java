package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import com.dishanhai.gt_shanhai.common.machine.misc.VirtualItemSupplyMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

/**
 * Presence checks for virtual providers that should not consume their real target items.
 */
public final class VirtualProviderSoftLocks {

    public static synchronized Reservation tryReserve(Object owner, IGrid grid, List<GenericStack> targets,
            IActionSource source) {
        if (grid == null || targets == null || targets.isEmpty()) {
            return Reservation.empty();
        }
        Map<AEKey, Long> required = condenseTargets(targets);
        if (required.isEmpty()) {
            return Reservation.empty();
        }
        if (usesSupplyMachineMode()) {
            return tryReserveFromSupplyMachines(grid, required);
        }
        if (!usesAETargetCheckMode()) {
            return Reservation.empty();
        }
        MEStorage storage = grid.getStorageService().getInventory();
        for (Map.Entry<AEKey, Long> entry : required.entrySet()) {
            long needed = entry.getValue();
            long available = countAvailableTarget(storage, entry.getKey(), needed, source);
            if (available < needed) {
                return Reservation.missing(new GenericStack(entry.getKey(), needed));
            }
        }
        return Reservation.empty();
    }

    public static synchronized void bind(Reservation reservation, UUID craftId) {
    }

    public static synchronized void release(Reservation reservation) {
    }

    public static synchronized void cleanup(Object owner, Set<UUID> liveCraftIds) {
    }

    private VirtualProviderSoftLocks() {}

    private static boolean usesAETargetCheckMode() {
        return DShanhaiConfig.COMMON.virtualProviderMode.get()
                == DShanhaiConfig.ConfigValues.VirtualProviderMode.AE_TARGET_CHECK;
    }

    private static boolean usesSupplyMachineMode() {
        return DShanhaiConfig.COMMON.virtualProviderMode.get()
                == DShanhaiConfig.ConfigValues.VirtualProviderMode.SUPPLY_MACHINE;
    }

    private static Reservation tryReserveFromSupplyMachines(IGrid grid, Map<AEKey, Long> required) {
        for (Map.Entry<AEKey, Long> entry : required.entrySet()) {
            long available = 0;
            for (VirtualItemSupplyMachine machine : grid.getMachines(VirtualItemSupplyMachine.class)) {
                if (machine != null && machine.isOnline()) {
                    available += machine.countProvidedTarget(entry.getKey());
                    if (available >= entry.getValue()) {
                        break;
                    }
                }
            }
            if (available < entry.getValue()) {
                return Reservation.missing(new GenericStack(entry.getKey(), entry.getValue()));
            }
        }
        return Reservation.empty();
    }

    private static long countAvailableTarget(MEStorage storage, AEKey targetKey, long needed, IActionSource source) {
        long available = storage.extract(targetKey, needed, Actionable.SIMULATE, source);
        if (available >= needed) return available;

        AEItemKey providerKey = VirtualItemProviderHelper.createProviderKeyForTarget(targetKey);
        if (providerKey != null && !providerKey.equals(targetKey)) {
            available += storage.extract(providerKey, needed - available, Actionable.SIMULATE, source);
            if (available >= needed) return available;
        }

        KeyCounter stacks = storage.getAvailableStacks();
        if (stacks == null || stacks.isEmpty()) return available;
        for (Object2LongMap.Entry<AEKey> stack : stacks) {
            AEKey availableKey = stack.getKey();
            if (availableKey == null || availableKey.equals(targetKey) || availableKey.equals(providerKey)) continue;
            if (!VirtualItemProviderHelper.matchesTargetKey(availableKey, targetKey)) continue;
            available += stack.getLongValue();
            if (available >= needed) return available;
        }
        return available;
    }

    private static Map<AEKey, Long> condenseTargets(List<GenericStack> targets) {
        Map<AEKey, Long> required = new LinkedHashMap<>();
        for (GenericStack target : targets) {
            if (target == null || target.what() == null || target.amount() <= 0) continue;
            long amount = target.amount();
            required.put(target.what(), required.getOrDefault(target.what(), 0L) + amount);
        }
        return required;
    }

    public static final class Reservation {
        private final GenericStack missing;

        private Reservation(GenericStack missing) {
            this.missing = missing;
        }

        private static Reservation empty() {
            return new Reservation(null);
        }

        private static Reservation missing(GenericStack missing) {
            return new Reservation(missing);
        }

        public boolean isEmpty() {
            return missing == null;
        }

        public GenericStack missing() {
            return missing;
        }
    }
}
