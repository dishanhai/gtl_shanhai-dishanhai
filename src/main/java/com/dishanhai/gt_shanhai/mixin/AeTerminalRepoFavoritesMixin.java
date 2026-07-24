package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.common.Repo;
import appeng.menu.me.common.GridInventoryEntry;

import com.dishanhai.gt_shanhai.client.ae.AeTerminalFavorites;

import com.google.common.collect.BiMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mixin(value = Repo.class, remap = false)
public abstract class AeTerminalRepoFavoritesMixin {

    @Shadow @Final private BiMap<Long, GridInventoryEntry> entries;
    @Shadow @Final private ArrayList<GridInventoryEntry> view;
    @Shadow @Final private ArrayList<GridInventoryEntry> pinnedRow;

    @Inject(method = "updateView", at = @At("TAIL"), remap = false)
    private void gtShanhai$promoteFavorites(CallbackInfo ci) {
        List<AEKey> orderedKeys = AeTerminalFavorites.getOrderedKeys();
        if (orderedKeys.isEmpty()) return;

        Map<AEKey, GridInventoryEntry> liveEntries = new HashMap<>();
        for (GridInventoryEntry entry : entries.values()) {
            if (entry.getWhat() != null) liveEntries.putIfAbsent(entry.getWhat(), entry);
        }

        Set<AEKey> favoriteKeys = new HashSet<>(orderedKeys);
        Set<AEKey> nativePinnedKeys = new HashSet<>();
        for (GridInventoryEntry entry : pinnedRow) {
            if (entry != null && entry.getWhat() != null) nativePinnedKeys.add(entry.getWhat());
        }

        ArrayList<GridInventoryEntry> orderedFavorites = new ArrayList<>(orderedKeys.size());
        for (AEKey key : orderedKeys) {
            if (nativePinnedKeys.contains(key)) continue;
            GridInventoryEntry entry = liveEntries.get(key);
            orderedFavorites.add(entry != null
                    ? entry
                    : new GridInventoryEntry(-1L, key, 0L, 0L, false));
        }

        ArrayList<GridInventoryEntry> nativeView = new ArrayList<>(view);
        view.clear();
        view.addAll(orderedFavorites);

        Set<Long> addedSerials = new HashSet<>();
        for (GridInventoryEntry entry : nativeView) {
            gtShanhai$appendNativeEntry(entry, favoriteKeys, addedSerials);
        }
    }

    private void gtShanhai$appendNativeEntry(GridInventoryEntry entry, Set<AEKey> favoriteKeys,
                                               Set<Long> addedSerials) {
        if (entry == null || entry.getWhat() == null || favoriteKeys.contains(entry.getWhat())) return;
        if (addedSerials.add(entry.getSerial())) view.add(entry);
    }
}
