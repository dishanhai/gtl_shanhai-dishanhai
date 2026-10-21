package com.dishanhai.gt_shanhai.common.machine.part;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Arrays;

/**
 * GTLCore 的样板总成存档里带一份按槽位数定长的 cacheRecipeCount，载入时直接
 * System.arraycopy 到按当前配置分配的数组。patternsPerRow/rowsPerPage/maxPages 调小后
 * 存档数组更长，越界抛 ArrayIndexOutOfBoundsException，整个方块实体载入失败。
 * 在 super 载入前把它截到当前槽位数；变长（调大）不会越界，保持原样由父类补默认值。
 */
final class RecipeTypePatternCacheCountGuard {

    private static final String CACHE_COUNT_KEY = "cacheRecipeCount";

    private RecipeTypePatternCacheCountGuard() {}

    static void clampToSlotCount(CompoundTag tag, int slotCount) {
        if (!tag.contains(CACHE_COUNT_KEY, Tag.TAG_BYTE_ARRAY)) return;
        byte[] saved = tag.getByteArray(CACHE_COUNT_KEY);
        if (saved.length <= slotCount) return;
        tag.putByteArray(CACHE_COUNT_KEY, Arrays.copyOf(saved, Math.max(slotCount, 0)));
    }
}
