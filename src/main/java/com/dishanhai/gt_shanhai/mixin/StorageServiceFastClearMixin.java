package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.*;

/**
 * AE2 StorageService 混合优化：增量更新（主线程）+ 异步全量扫描（后台线程）
 * 
 * 优化前：每 tick 全量调用 storage.getAvailableStacks() 遍历所有 ME 存储（9.97%）
 * 优化后：
 * 1. 主线程仅处理增量变化（insert/extract 记录的 delta）
 * 2. 异步线程定期全量扫描，主线程读取上一轮结果
 * 3. 强制更新时（cachedStacksNeedUpdate=true）走同步全量扫描
 */
@Mixin(targets = "appeng.me.service.StorageService", remap = false)
public abstract class StorageServiceFastClearMixin implements IStorageServiceDeltaRecorder, IStorageServiceRevisionAccess {

    @Shadow private boolean cachedStacksNeedUpdate;
    @Shadow @Final private KeyCounter cachedAvailableStacks;
    @Shadow @Final private Object2LongMap<AEKey> cachedAvailableAmounts;
    @Shadow @Final private appeng.me.storage.NetworkStorage storage;

    @Shadow protected abstract void postWatcherUpdate(AEKey what, long newAmount);

    // 双缓冲快照
    @Unique
    private Object2LongOpenHashMap<AEKey> gtShanhai$prevAmounts = new Object2LongOpenHashMap<>();
    @Unique
    private Object2LongOpenHashMap<AEKey> gtShanhai$currAmounts = new Object2LongOpenHashMap<>();
    
    // 增量更新：记录变化的 key 及其数量差值
    @Unique
    private Object2LongOpenHashMap<AEKey> gtShanhai$pendingDeltas = new Object2LongOpenHashMap<>();
    
    // 并发保护：读写 currAmounts 时加锁
    @Unique
    private Object gtShanhai$amountsLock = new Object();
    
    // 异步全量扫描
    @Unique
    private static final ScheduledExecutorService ASYNC_SCANNER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AE2-StorageScanner");
        t.setDaemon(true);
        return t;
    });
    @Unique
    private static final int ASYNC_SCAN_INTERVAL_TICKS = 100; // 5秒扫描一次
    @Unique
    private int gtShanhai$ticksSinceLastAsyncScan = 0;
    @Unique
    private Future<KeyCounter> gtShanhai$asyncScanResult = null;
    
    // 误差监控（可选，调试用）
    @Unique
    private static final boolean ENABLE_DRIFT_MONITOR = false;
    @Unique
    private long gtShanhai$lastDriftCheckTick = 0;

    @Unique
    private long gtShanhai$inventoryRevision = 0L;

    @Unique
    private Set<AEKey> gtShanhai$lastChangedKeys = Collections.emptySet();

    @Unique
    private void gtShanhai$ensureState() {
        if (this.gtShanhai$prevAmounts == null) {
            this.gtShanhai$prevAmounts = new Object2LongOpenHashMap<>();
        }
        if (this.gtShanhai$currAmounts == null) {
            this.gtShanhai$currAmounts = new Object2LongOpenHashMap<>();
        }
        if (this.gtShanhai$pendingDeltas == null) {
            this.gtShanhai$pendingDeltas = new Object2LongOpenHashMap<>();
        }
        if (this.gtShanhai$amountsLock == null) {
            this.gtShanhai$amountsLock = new Object();
        }
        if (this.gtShanhai$lastChangedKeys == null) {
            this.gtShanhai$lastChangedKeys = Collections.emptySet();
        }
    }

    @Inject(method = "updateCachedStacks", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$updateCachedStacksFast(CallbackInfo ci) {
        gtShanhai$ensureState();
        // 强制更新：同步全量扫描（保证准确性）
        if (this.cachedStacksNeedUpdate) {
            gtShanhai$fullSyncUpdate();
            ci.cancel();
            return;
        }
        
        // 增量更新：应用累积的 delta
        if (!gtShanhai$pendingDeltas.isEmpty()) {
            gtShanhai$applyIncrementalUpdate();
        }
        
        // 定期触发异步全量扫描
        gtShanhai$ticksSinceLastAsyncScan++;
        if (gtShanhai$ticksSinceLastAsyncScan >= ASYNC_SCAN_INTERVAL_TICKS) {
            gtShanhai$triggerAsyncScan();
            gtShanhai$ticksSinceLastAsyncScan = 0;
        }
        
        // 检查异步扫描结果
        gtShanhai$checkAsyncScanResult();
        
        ci.cancel();
    }
    
    /** 增量更新：应用 delta 变化 */
    @Unique
    private void gtShanhai$applyIncrementalUpdate() {
        gtShanhai$ensureState();
        LinkedHashSet<AEKey> changedKeys = null;
        synchronized (gtShanhai$pendingDeltas) {
            if (gtShanhai$pendingDeltas.isEmpty()) return;
            
            synchronized (gtShanhai$amountsLock) {
                for (Object2LongMap.Entry<AEKey> entry : gtShanhai$pendingDeltas.object2LongEntrySet()) {
                    AEKey key = entry.getKey();
                    long delta = entry.getLongValue();
                    long oldAmount = gtShanhai$currAmounts.getLong(key);
                    // 饱和运算：防止溢出
                    long newAmount = gtShanhai$saturatedAdd(oldAmount, delta);
                    
                    if (newAmount != oldAmount) {
                        if (newAmount > 0) {
                            gtShanhai$currAmounts.put(key, newAmount);
                            cachedAvailableAmounts.put(key, newAmount);
                            cachedAvailableStacks.add(key, delta);
                        } else {
                            gtShanhai$currAmounts.removeLong(key);
                            cachedAvailableAmounts.removeLong(key);
                            cachedAvailableStacks.remove(key);
                        }
                        if (changedKeys == null) changedKeys = new LinkedHashSet<>();
                        changedKeys.add(key);
                        postWatcherUpdate(key, newAmount);
                    }
                }
                gtShanhai$pendingDeltas.clear();
                gtShanhai$publishRevision(changedKeys);
            }
        }
    }
    
    /** 饱和加法：防止溢出，结果限制在 [0, Long.MAX_VALUE] */
    @Unique
    private static long gtShanhai$saturatedAdd(long a, long b) {
        long result = a + b;
        // 检测溢出：符号位翻转
        if (((a ^ result) & (b ^ result)) < 0) {
            return b > 0 ? Long.MAX_VALUE : 0;
        }
        return Math.max(0, result);
    }
    
    /** 同步全量扫描（强制更新时使用）*/
    @Unique
    private void gtShanhai$fullSyncUpdate() {
        gtShanhai$ensureState();
        this.cachedStacksNeedUpdate = false;
        synchronized (gtShanhai$pendingDeltas) {
            gtShanhai$pendingDeltas.clear();
        }
        gtShanhai$clearKeyCounterFast(this.cachedAvailableStacks);
        this.storage.getAvailableStacks(this.cachedAvailableStacks);
        synchronized (gtShanhai$amountsLock) {
            gtShanhai$applyFullScan(this.cachedAvailableStacks);
        }
    }
    
    /** 触发异步全量扫描 */
    @Unique
    private void gtShanhai$triggerAsyncScan() {
        // 如果上一次扫描还未完成，跳过
        if (gtShanhai$asyncScanResult != null && !gtShanhai$asyncScanResult.isDone()) {
            return;
        }
        
        // 捕获当前 storage 引用，避免异步线程访问时网络已重构
        final appeng.me.storage.NetworkStorage capturedStorage = this.storage;
        
        // 异步执行全量扫描
        gtShanhai$asyncScanResult = ASYNC_SCANNER.submit(() -> {
            KeyCounter result = new KeyCounter();
            try {
                // 检查 storage 是否仍有效（简单的 null 检查，避免访问已失效对象）
                if (capturedStorage != null) {
                    capturedStorage.getAvailableStacks(result);
                }
            } catch (Throwable t) {
                // 异常时返回空，主线程继续用增量数据
                // 可选：记录警告日志
                if (ENABLE_DRIFT_MONITOR) {
                    System.err.println("[gt_shanhai] Async storage scan failed: " + t.getMessage());
                }
            }
            return result;
        });
    }
    
    /** 检查并应用异步扫描结果 */
    @Unique
    private void gtShanhai$checkAsyncScanResult() {
        if (gtShanhai$asyncScanResult == null || !gtShanhai$asyncScanResult.isDone()) {
            return;
        }
        
        try {
            KeyCounter asyncResult = gtShanhai$asyncScanResult.get(1, TimeUnit.MILLISECONDS);
            if (asyncResult != null && !asyncResult.isEmpty()) {
                // 用异步结果修正当前缓存
                gtShanhai$mergeAsyncResult(asyncResult);
            }
        } catch (Throwable ignored) {
            // 异常忽略，继续用增量数据
        } finally {
            gtShanhai$asyncScanResult = null;
        }
    }
    
    /** 合并异步扫描结果（修正增量误差）*/
    @Unique
    private void gtShanhai$mergeAsyncResult(KeyCounter asyncResult) {
        gtShanhai$ensureState();
        synchronized (gtShanhai$amountsLock) {
            // 误差监控（可选）
            if (ENABLE_DRIFT_MONITOR) {
                gtShanhai$checkDrift(asyncResult);
            }
            
            // 简化版：直接替换当前快照（完整版需要 diff）
            gtShanhai$clearKeyCounterFast(cachedAvailableStacks);
            cachedAvailableStacks.addAll(asyncResult);
            gtShanhai$applyFullScan(asyncResult);
        }
    }

    @Unique
    private void gtShanhai$applyFullScan(KeyCounter fullScan) {
        gtShanhai$ensureState();
        Object2LongOpenHashMap<AEKey> oldAmounts = this.gtShanhai$currAmounts;
        Object2LongOpenHashMap<AEKey> nextAmounts = this.gtShanhai$prevAmounts;
        LinkedHashSet<AEKey> changedKeys = null;
        nextAmounts.clear();
        this.cachedAvailableAmounts.clear();

        for (Object2LongMap.Entry<AEKey> entry : fullScan) {
            AEKey what = entry.getKey();
            long newAmount = entry.getLongValue();
            if (newAmount <= 0) continue;

            long oldAmount = oldAmounts.removeLong(what);
            if (newAmount != oldAmount) {
                if (changedKeys == null) changedKeys = new LinkedHashSet<>();
                changedKeys.add(what);
                postWatcherUpdate(what, newAmount);
            }
            nextAmounts.put(what, newAmount);
            this.cachedAvailableAmounts.put(what, newAmount);
        }

        if (!oldAmounts.isEmpty()) {
            for (Object2LongMap.Entry<AEKey> removed : oldAmounts.object2LongEntrySet()) {
                if (changedKeys == null) changedKeys = new LinkedHashSet<>();
                changedKeys.add(removed.getKey());
                postWatcherUpdate(removed.getKey(), 0L);
            }
            oldAmounts.clear();
        }

        this.gtShanhai$currAmounts = nextAmounts;
        this.gtShanhai$prevAmounts = oldAmounts;
        gtShanhai$publishRevision(changedKeys);
    }
    
    /** 误差监控：对比增量 vs 全量扫描的差异 */
    @Unique
    private void gtShanhai$checkDrift(KeyCounter fullScan) {
        int driftCount = 0;
        long maxDrift = 0;
        
        // 检查增量缓存中的 key
        for (Object2LongMap.Entry<AEKey> entry : gtShanhai$currAmounts.object2LongEntrySet()) {
            AEKey key = entry.getKey();
            long incrementalAmount = entry.getLongValue();
            long fullScanAmount = fullScan.get(key);
            long drift = Math.abs(incrementalAmount - fullScanAmount);
            
            if (drift > 0) {
                driftCount++;
                maxDrift = Math.max(maxDrift, drift);
            }
        }
        
        // 检查全量扫描中有但增量缓存中没有的 key
        for (var entry : fullScan) {
            AEKey key = entry.getKey();
            if (!gtShanhai$currAmounts.containsKey(key) && entry.getLongValue() > 0) {
                driftCount++;
                maxDrift = Math.max(maxDrift, entry.getLongValue());
            }
        }
        
        if (driftCount > 0) {
            System.err.println("[gt_shanhai] Storage drift detected: " + driftCount + 
                             " keys, max drift: " + maxDrift);
        }
    }

    @Redirect(method = "updateCachedStacks", at = @At(value = "INVOKE",
            target = "Lappeng/api/stacks/KeyCounter;clear()V"), remap = false)
    private void gtShanhai$clearCachedStacksFast(KeyCounter counter) {
        gtShanhai$clearKeyCounterFast(counter);
    }

    @Unique
    private static void gtShanhai$clearKeyCounterFast(KeyCounter counter) {
        counter.clear();
    }
    
    // ══════════════════════════════════════════════════════════════════
    // 增量更新 Hook：拦截 NetworkStorage 的 insert/extract 记录 delta
    // ══════════════════════════════════════════════════════════════════
    
    /** 记录存储变化的 delta */
    @Unique
    public void gtShanhai$recordDelta(AEKey key, long delta) {
        if (delta == 0 || key == null) return;
        gtShanhai$ensureState();
        synchronized (gtShanhai$pendingDeltas) {
            gtShanhai$pendingDeltas.mergeLong(key, delta, Long::sum);
        }
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
        return this.gtShanhai$inventoryRevision;
    }

    @Override
    public Set<AEKey> gtShanhai$getLastChangedKeys() {
        gtShanhai$ensureState();
        return this.gtShanhai$lastChangedKeys;
    }
}
