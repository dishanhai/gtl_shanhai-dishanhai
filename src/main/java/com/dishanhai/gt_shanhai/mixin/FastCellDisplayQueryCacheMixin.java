package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.menu.me.common.MEStorageMenu;
import com.dishanhai.gt_shanhai.api.ae2.FastCellDisplayQueryCacheEntry;
import com.dishanhai.gt_shanhai.api.ae2.IStorageServiceRevisionAccess;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.gtlcore.gtlcore.integration.ae2.storage.PreciseInventoryDisplayService;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 精確大數量顯示查詢的 revision 快取。
 *
 * <p>FastCellDisplayPackets.push 每次終端廣播都會重新遞迴查詢精確庫存，即使 AE
 * 網路 revision 與可見的 Long.MAX_VALUE key 集合完全沒有變化。只在山海
 * StorageService revision 可用時重用查詢結果；普通單元件/非山海存儲沒有 revision
 * 時仍完整走 gtlcore 原流程。
 */
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.wireless.FastCellDisplayPackets", remap = false)
public abstract class FastCellDisplayQueryCacheMixin {

    @Unique
    private static final Map<MEStorageMenu, FastCellDisplayQueryCacheEntry> gtShanhai$QUERY_CACHE = new WeakHashMap<>();
    @Unique
    private static final ThreadLocal<MEStorageMenu> gtShanhai$CURRENT_MENU = new ThreadLocal<>();

    @org.spongepowered.asm.mixin.injection.Inject(method = "push", at = @At("HEAD"), remap = false)
    private static void gtShanhai$captureMenu(MEStorageMenu menu, MEStorage storage,
                                               appeng.api.stacks.KeyCounter snapshot, CallbackInfo ci) {
        gtShanhai$CURRENT_MENU.set(menu);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "push", at = @At("RETURN"), remap = false)
    private static void gtShanhai$releaseMenu(MEStorageMenu menu, MEStorage storage,
                                               appeng.api.stacks.KeyCounter snapshot, CallbackInfo ci) {
        gtShanhai$CURRENT_MENU.remove();
    }

    @Redirect(method = "push", at = @At(value = "INVOKE",
            target = "Lorg/gtlcore/gtlcore/integration/ae2/storage/PreciseInventoryDisplayService;query(Lappeng/api/storage/MEStorage;Ljava/util/List;)Ljava/util/Map;"),
            remap = false)
    private static Map<AEKey, BigInteger> gtShanhai$reuseQuery(MEStorage storage, List<AEKey> keys) {
        MEStorageMenu menu = gtShanhai$CURRENT_MENU.get();
        try {
            long revision = gtShanhai$revision(menu, storage);
            long topology = gtShanhai$topology(storage);
            if (revision == Long.MIN_VALUE) {
                return PreciseInventoryDisplayService.query(storage, keys);
            }

            FastCellDisplayQueryCacheEntry cache = gtShanhai$QUERY_CACHE.get(menu);
            if (cache != null && cache.storage == storage && cache.revision == revision
                    && cache.topology == topology
                    && cache.keys.equals(keys)) {
                return cache.amounts;
            }

            Map<AEKey, BigInteger> amounts = PreciseInventoryDisplayService.query(storage, keys);
            if (cache == null) {
                cache = new FastCellDisplayQueryCacheEntry();
                gtShanhai$QUERY_CACHE.put(menu, cache);
            }
            cache.storage = storage;
            cache.revision = revision;
            cache.topology = topology;
            cache.keys = List.copyOf(keys);
            cache.amounts = amounts;
            return amounts;
        } finally {
            gtShanhai$CURRENT_MENU.remove();
        }
    }

    @Unique
    private static long gtShanhai$revision(MEStorageMenu menu, MEStorage storage) {
        if (menu == null) {
            return Long.MIN_VALUE;
        }
        IGridNode node = menu.getNetworkNode();
        if (node == null && menu.getHost() instanceof IActionHost actionHost) {
            node = actionHost.getActionableNode();
        }
        if (node == null || node.getGrid() == null) {
            return Long.MIN_VALUE;
        }
        IStorageService service = node.getGrid().getStorageService();
        if (!(service instanceof IStorageServiceRevisionAccess revisionAccess)
                || storage != service.getInventory()) {
            return Long.MIN_VALUE;
        }
        return revisionAccess.gtShanhai$getInventoryRevision();
    }

    @Unique
    private static long gtShanhai$topology(MEStorage storage) {
        return storage instanceof ThroughputStorageView view
                ? view.gtlcore$getTopologyVersion()
                : 0L;
    }

}
