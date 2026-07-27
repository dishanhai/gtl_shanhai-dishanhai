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
    /** 本网格经历的服务端 tick 数（onServerEndTick 每 tick 自增），供 per-tick 去抖比对 */
    @Unique
    private long gtShanhai$tickCounter;
    /** 最近一次全量重扫发生在哪个 tick（去抖：同 tick 内至多一次全扫，向原版语义收敛） */
    @Unique
    private long gtShanhai$lastFullSyncTick = Long.MIN_VALUE;
    /** 强制全扫安全网倒计时（覆盖存储总线外部容器等不经 insert/extract 的变化） */
    @Unique
    private int gtShanhai$forceRescanCountdown;

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
        gtShanhai$tickCounter++;

        boolean enabled = DShanhaiConfig.COMMON.aeStorageDeltaCacheEnabled.get();
        // 强制全扫安全网：存储总线背后的外部容器被管道/漏斗/玩家直改时不经 insert/extract
        // 记录路径，增量缓存无法感知（原版 AE2 靠每 tick 全扫兜底、被本 mixin 取消）。
        // 每 N tick 无条件标脏一次，把陈旧窗口从无界压到 N tick。
        if (enabled && ++gtShanhai$forceRescanCountdown >= DShanhaiConfig.COMMON.aeStorageForceRescanTicks.get()) {
            gtShanhai$forceRescanCountdown = 0;
            this.cachedStacksNeedUpdate = true;
        }

        if (!this.interestManager.isEmpty()) {
            return;
        }

        if (!enabled) {
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
        boolean enabled = DShanhaiConfig.COMMON.aeStorageDeltaCacheEnabled.get();
        if (this.cachedStacksNeedUpdate || !enabled) {
            // per-tick 去抖：AE2 部件普遍在循环内读写交错（如输出总线逐槽 读→extract→读），
            // 事件驱动标脏会把全量重扫放大到 O(部件数×槽位数)/tick。同 tick 内已扫过就直接
            // 返回既有快照（脏标记保留，下一 tick 首次读取时重扫）——tick 内陈旧与原版语义
            // 一致（原版同样每 tick 至多一次全扫）。配置关闭时不去抖，行为完全回归原版。
            if (enabled && gtShanhai$lastFullSyncTick == gtShanhai$tickCounter) {
                ci.cancel();
                return;
            }
            gtShanhai$lastFullSyncTick = gtShanhai$tickCounter;
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
        // 配置关闭时必须不标脏：原版本来就每 tick 末标脏一次，这里再按写入频率标脏
        // 会让"关闭优化"比原版更慢（每次读写交错都触发全扫），管理员无法有效回滚
        if (!DShanhaiConfig.COMMON.aeStorageDeltaCacheEnabled.get()) return;
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
