package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.CompositeStorage;
import appeng.me.storage.DelegatingMEInventory;
import appeng.me.storage.DriveWatcher;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.NetworkStorage;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gtlcore.gtlcore.integration.ae2.storage.PreciseStorageAmount;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;
import org.gtlcore.gtlcore.mixin.ae2.storage.MEInventoryHandlerDisplayAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * gtlcore 精确库存查询的低分配实现。
 *
 * <p>原实现对每个递归节点都创建 Stream pipeline 和临时 List，并反复查找
 * visited/otherStorages 集合。无线终端每次刷新
 * 都会走这条路径，导致 profiler 中 {@code Stream.toList} 和 {@code HashSet.add}
 * 成为服务端热点。这里保留原查询契约，只改成预分配 ArrayList 与显式 map 查找。
 */
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.storage.PreciseInventoryDisplayService$Query",
        remap = false)
public abstract class PreciseInventoryDisplayQueryMixin {

    @Shadow private IActionSource source;
    @Shadow private boolean exhausted;
    @Shadow @Final private Map<AEKey, BigInteger> amounts;
    @Shadow @Final private Map<MEStorage, Set<AEKey>> visited;
    @Shadow @Final private Map<MEStorage, Set<AEKey>> otherStorages;
    @Shadow private int remaining;

    /**
     * @author dishanhai
     * @reason 消除精确库存显示查询中每个递归节点的 Stream/List/集合构造开销
     */
    @Overwrite(remap = false)
    private void visit(MEStorage storage, List<AEKey> requested, int depth) {
        if (storage == null || requested.isEmpty() || this.exhausted) {
            return;
        }
        if (depth <= 64) {
            int left = --this.remaining;
            if (left >= 0) {
                Set<AEKey> seen = this.visited.get(storage);
                if (seen == null) {
                    seen = new HashSet<>();
                    this.visited.put(storage, seen);
                }

                List<AEKey> keys = new ArrayList<>(requested.size());
                for (AEKey key : requested) {
                    if (seen.add(key)) {
                        keys.add(key);
                    }
                }
                if (keys.isEmpty()) {
                    return;
                }

                if (storage instanceof PreciseStorageAmount precise) {
                    for (AEKey key : keys) {
                        BigInteger amount = precise.getExactStoredAmount(key);
                        if (amount.signum() > 0) {
                            if (this.source != null) {
                                long extractable = storage.extract(key, Long.MAX_VALUE,
                                        appeng.api.config.Actionable.SIMULATE, this.source);
                                if (extractable < Long.MAX_VALUE) {
                                    amount = BigInteger.valueOf(Math.max(0L, extractable));
                                }
                            }
                            BigInteger previous = this.amounts.get(key);
                            this.amounts.put(key,
                                    previous == null ? amount : previous.add(amount));
                        }
                    }
                    return;
                }

                Class<?> type = storage.getClass();
                boolean traversable = type == NetworkStorage.class
                        || type == CompositeStorage.class
                        || type == DelegatingMEInventory.class
                        || type == MEInventoryHandler.class
                        || type == DriveWatcher.class;
                if (traversable && storage instanceof ThroughputStorageView view) {
                    if (storage instanceof MEInventoryHandlerDisplayAccessor filter) {
                        if (this.source != null) {
                            if (!filter.gtlcore$allowsDisplayExtraction()) {
                                return;
                            }
                            if (filter.gtlcore$filtersDisplayExtraction()) {
                                keys = filterKeys(keys, filter);
                            }
                        } else if (filter.gtlcore$filtersDisplayContents()) {
                            keys = filterKeys(keys, filter);
                        }
                    }
                    if (keys.isEmpty()) {
                        return;
                    }
                    for (MEStorage child : view.gtlcore$getChildStorages()) {
                        visit(child, keys, depth + 1);
                    }
                    return;
                }

                Set<AEKey> other = this.otherStorages.get(storage);
                if (other == null) {
                    other = new HashSet<>();
                    this.otherStorages.put(storage, other);
                }
                other.addAll(keys);
                return;
            }
        }
        this.exhausted = true;
    }

    private static List<AEKey> filterKeys(List<AEKey> keys, MEInventoryHandlerDisplayAccessor filter) {
        List<AEKey> filtered = new ArrayList<>(keys.size());
        for (AEKey key : keys) {
            if (filter.gtlcore$canDisplay(key)) {
                filtered.add(key);
            }
        }
        return filtered;
    }
}
