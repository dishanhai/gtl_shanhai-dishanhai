package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.helpers.InterestManager;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.dishanhai.gt_shanhai.api.ae2.INetworkStorageDeltaSink;
import com.dishanhai.gt_shanhai.api.ae2.IStorageServiceDeltaRecorder;
import com.dishanhai.gt_shanhai.api.ae2.IStorageServiceRevisionAccess;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
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
    private long gtShanhai$inventoryRevision;
    @Unique
    private Set<AEKey> gtShanhai$lastChangedKeys;

    @Unique
    private void gtShanhai$ensureState() {
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

        if (!DShanhaiConfig.COMMON.aeStorageDeltaCacheEnabled.get()) {
            return;
        }

        if (!this.cachedStacksNeedUpdate) {
            gtShanhai$publishRevision(null);
        }
        ci.cancel();
    }

    @Inject(method = "updateCachedStacks", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$updateCachedStacksIncrementally(CallbackInfo ci) {
        gtShanhai$ensureState();
        if (this.cachedStacksNeedUpdate || !DShanhaiConfig.COMMON.aeStorageDeltaCacheEnabled.get()) {
            gtShanhai$fullSyncUpdate();
            ci.cancel();
            return;
        }

        gtShanhai$publishRevision(null);
        ci.cancel();
    }

    @Unique
    private void gtShanhai$fullSyncUpdate() {
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = false;

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

    @Override
    public void gtShanhai$recordDelta(AEKey key, long delta) {
        if (key == null || delta == 0L) return;
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = true;
    }

    // addNode/removeNode/refresh*StorageProvider 改变的是挂载的 MEStorage 拓扑结构，不是单个 key 的数量——
    // NetworkStorageDeltaTrackerMixin 只盯 insert/extract，挂载变化不会翻动 cachedStacksNeedUpdate，
    // getCachedInventory() 就会一直吐挂载变化之前的旧快照（AE2 的 NetworkCraftingSimulationState
    // 直接拿 getCachedInventory() 播种合成计划计算，这就是"材料不足"要等别的操作恰好触发一次
    // delta 才会消失的根因）。
    @Inject(method = "addNode", at = @At("TAIL"), remap = false)
    private void gtShanhai$dirtyOnAddNode(CallbackInfo ci) {
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = true;
    }

    @Inject(method = "removeNode", at = @At("TAIL"), remap = false)
    private void gtShanhai$dirtyOnRemoveNode(CallbackInfo ci) {
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = true;
    }

    @Inject(method = "refreshNodeStorageProvider", at = @At("TAIL"), remap = false)
    private void gtShanhai$dirtyOnRefreshNodeProvider(CallbackInfo ci) {
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = true;
    }

    @Inject(method = "refreshGlobalStorageProvider", at = @At("TAIL"), remap = false)
    private void gtShanhai$dirtyOnRefreshGlobalProvider(CallbackInfo ci) {
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = true;
    }

    @Inject(method = "addGlobalStorageProvider", at = @At("TAIL"), remap = false)
    private void gtShanhai$dirtyOnAddGlobalProvider(CallbackInfo ci) {
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = true;
    }

    @Inject(method = "removeGlobalStorageProvider", at = @At("TAIL"), remap = false)
    private void gtShanhai$dirtyOnRemoveGlobalProvider(CallbackInfo ci) {
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = true;
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
