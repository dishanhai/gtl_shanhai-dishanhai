package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.helpers.InterestManager;
import com.dishanhai.gt_shanhai.api.ae2.INetworkStorageDeltaSink;
import com.dishanhai.gt_shanhai.api.ae2.IStorageServiceDeltaRecorder;
import com.dishanhai.gt_shanhai.api.ae2.IStorageServiceRevisionAccess;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Mixin(targets = "appeng.me.service.StorageService", remap = false)
public abstract class StorageServiceDeltaCacheMixin implements IStorageServiceDeltaRecorder, IStorageServiceRevisionAccess {

    @Shadow private boolean cachedStacksNeedUpdate;
    @Shadow @Final private KeyCounter cachedAvailableStacks;
    @Shadow @Final private Object2LongMap<AEKey> cachedAvailableAmounts;
    @Shadow @Final private appeng.me.storage.NetworkStorage storage;
    @Shadow @Final private InterestManager<?> interestManager;

    @Shadow protected abstract void postWatcherUpdate(AEKey what, long newAmount);

    @Unique
    private Object2LongOpenHashMap<AEKey> gtShanhai$pendingDeltas;
    @Unique
    private long gtShanhai$inventoryRevision;
    @Unique
    private Set<AEKey> gtShanhai$lastChangedKeys;

    @Unique
    private void gtShanhai$ensureState() {
        if (this.gtShanhai$pendingDeltas == null) {
            this.gtShanhai$pendingDeltas = new Object2LongOpenHashMap<>();
        }
        if (this.gtShanhai$lastChangedKeys == null) {
            this.gtShanhai$lastChangedKeys = Collections.emptySet();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtShanhai$attachDeltaRecorder(CallbackInfo ci) {
        gtShanhai$ensureState();
        if (this.storage instanceof INetworkStorageDeltaSink sink) {
            sink.gtShanhai$setDeltaRecorder(this);
        }
    }

    @Inject(method = "onServerEndTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$keepCacheWarmWithoutWatchers(CallbackInfo ci) {
        gtShanhai$ensureState();
        if (!this.interestManager.isEmpty()) {
            return;
        }

        if (!this.cachedStacksNeedUpdate) {
            if (!gtShanhai$pendingDeltas.isEmpty()) {
                gtShanhai$applyPendingDeltas();
            } else {
                gtShanhai$publishRevision(null);
            }
        }
        ci.cancel();
    }

    @Inject(method = "updateCachedStacks", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$updateCachedStacksIncrementally(CallbackInfo ci) {
        gtShanhai$ensureState();
        if (this.cachedStacksNeedUpdate) {
            gtShanhai$fullSyncUpdate();
            ci.cancel();
            return;
        }

        if (!gtShanhai$pendingDeltas.isEmpty()) {
            gtShanhai$applyPendingDeltas();
        } else {
            gtShanhai$publishRevision(null);
        }
        ci.cancel();
    }

    @Unique
    private void gtShanhai$fullSyncUpdate() {
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = false;
        synchronized (gtShanhai$pendingDeltas) {
            gtShanhai$pendingDeltas.clear();
        }

        this.cachedAvailableStacks.clear();
        this.storage.getAvailableStacks(this.cachedAvailableStacks);
        this.cachedAvailableStacks.removeEmptySubmaps();

        LinkedHashSet<AEKey> changedKeys = null;
        for (Object2LongMap.Entry<AEKey> entry : this.cachedAvailableStacks) {
            AEKey what = entry.getKey();
            long newAmount = entry.getLongValue();
            if (newAmount != this.cachedAvailableAmounts.getLong(what)) {
                if (changedKeys == null) changedKeys = new LinkedHashSet<>();
                changedKeys.add(what);
                postWatcherUpdate(what, newAmount);
            }
        }

        for (AEKey what : this.cachedAvailableAmounts.keySet()) {
            long newAmount = this.cachedAvailableStacks.get(what);
            if (newAmount == 0L) {
                if (changedKeys == null) changedKeys = new LinkedHashSet<>();
                changedKeys.add(what);
                postWatcherUpdate(what, 0L);
            }
        }

        this.cachedAvailableAmounts.clear();
        for (Object2LongMap.Entry<AEKey> entry : this.cachedAvailableStacks) {
            this.cachedAvailableAmounts.put(entry.getKey(), entry.getLongValue());
        }
        gtShanhai$publishRevision(changedKeys);
    }

    @Unique
    private void gtShanhai$applyPendingDeltas() {
        gtShanhai$ensureState();
        LinkedHashSet<AEKey> changedKeys = null;
        synchronized (gtShanhai$pendingDeltas) {
            for (Object2LongMap.Entry<AEKey> entry : gtShanhai$pendingDeltas.object2LongEntrySet()) {
                AEKey what = entry.getKey();
                long oldAmount = this.cachedAvailableAmounts.getLong(what);
                long newAmount = gtShanhai$saturatedAdd(oldAmount, entry.getLongValue());
                if (newAmount == oldAmount) continue;

                if (newAmount > 0L) {
                    this.cachedAvailableAmounts.put(what, newAmount);
                    this.cachedAvailableStacks.set(what, newAmount);
                } else {
                    this.cachedAvailableAmounts.removeLong(what);
                    this.cachedAvailableStacks.remove(what);
                }

                if (changedKeys == null) changedKeys = new LinkedHashSet<>();
                changedKeys.add(what);
                postWatcherUpdate(what, newAmount);
            }
            gtShanhai$pendingDeltas.clear();
        }
        gtShanhai$publishRevision(changedKeys);
    }

    @Unique
    private static long gtShanhai$saturatedAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return b > 0L ? Long.MAX_VALUE : 0L;
        }
        return Math.max(0L, result);
    }

    @Override
    public void gtShanhai$recordDelta(AEKey key, long delta) {
        if (key == null || delta == 0L) return;
        gtShanhai$ensureState();
        synchronized (gtShanhai$pendingDeltas) {
            gtShanhai$pendingDeltas.mergeLong(key, delta, StorageServiceDeltaCacheMixin::gtShanhai$saturatedDeltaMerge);
        }
    }

    @Unique
    private static long gtShanhai$saturatedDeltaMerge(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return b > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return result;
    }

    @Unique
    private void gtShanhai$publishRevision(Set<AEKey> changedKeys) {
        gtShanhai$ensureState();
        if (changedKeys == null || changedKeys.isEmpty()) {
            this.gtShanhai$lastChangedKeys = Collections.emptySet();
            return;
        }
        this.gtShanhai$inventoryRevision++;
        this.gtShanhai$lastChangedKeys = Collections.unmodifiableSet(changedKeys);
    }

    @Override
    public long gtShanhai$getInventoryRevision() {
        gtShanhai$ensureState();
        return gtShanhai$inventoryRevision;
    }

    @Override
    public Set<AEKey> gtShanhai$getLastChangedKeys() {
        gtShanhai$ensureState();
        return gtShanhai$lastChangedKeys;
    }
}
