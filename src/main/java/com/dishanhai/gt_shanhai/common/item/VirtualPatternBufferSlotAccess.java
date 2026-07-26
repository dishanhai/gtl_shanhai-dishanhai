package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import org.gtlcore.gtlcore.integration.ae2.handler.SlotCacheManager;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

public interface VirtualPatternBufferSlotAccess {

    void gtShanhai$addVirtualTarget(AEKey key, long amount);

    void gtShanhai$restoreVirtualTarget(AEKey key, long amount);

    boolean gtShanhai$hasVirtualTarget(AEKey key);

    void gtShanhai$syncVirtualTargetsToCatalyst();

    void gtShanhai$stripVirtualTargetsFromCatalyst();

    void gtShanhai$stripVirtualTargets();

    void gtShanhai$clearVirtualTargetsIfDepleted();

    // ===== 槽位成员桥接：给机器级 mixin 用，取代反射访问 InternalSlot =====
    // （反射版本 catch 后静默吞掉，GTLCore 改名时 pushPattern 会无声吃掉配料，见 2026-07 体检）

    Object2LongOpenHashMap<AEItemKey> gtShanhai$itemInventory();

    Object2LongOpenHashMap<AEFluidKey> gtShanhai$fluidInventory();

    Object2LongMap<AEItemKey> gtShanhai$itemCatalystInventory();

    Object2LongMap<AEFluidKey> gtShanhai$fluidCatalystInventory();

    SlotCacheManager gtShanhai$cacheManager();

    void gtShanhai$add(AEKey what, long amount);

    void gtShanhai$notifyContentsChanged();
}
