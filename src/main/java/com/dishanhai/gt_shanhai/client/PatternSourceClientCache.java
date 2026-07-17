package com.dishanhai.gt_shanhai.client;

import appeng.api.stacks.AEKey;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端缓存："配方类型 + 供应它的样板总成坐标" 按 AEKey 索引，供合成状态详情页的提示/点击传送用。
 * 数据来自 {@link com.dishanhai.gt_shanhai.network.PatternSourceResponsePacket}，只增不减
 * （AEKey 集合有限，跨网络/跨会话复用缓存无害；玩家切世界不需要清，读到的坐标本就该按当前世界解释）。
 */
public final class PatternSourceClientCache {

    public record Info(BlockPos pos, String recipeTypeId) {}

    private static final Map<AEKey, Info> CACHE = new HashMap<>();
    private static final Set<AEKey> PENDING = new HashSet<>();

    private PatternSourceClientCache() {}

    /** 是否已经有结果，或者已经问过服务端还没回来——两种情况都不需要重复发请求。 */
    public static boolean isKnownOrPending(AEKey key) {
        return key != null && (CACHE.containsKey(key) || PENDING.contains(key));
    }

    public static void markPending(Collection<AEKey> keys) {
        PENDING.addAll(keys);
    }

    public static void putAll(List<AEKey> keys, List<BlockPos> positions, List<String> recipeTypeIds) {
        for (int i = 0; i < keys.size(); i++) {
            AEKey key = keys.get(i);
            CACHE.put(key, new Info(positions.get(i), recipeTypeIds.get(i)));
            PENDING.remove(key);
        }
    }

    @Nullable
    public static Info get(@Nullable AEKey key) {
        return key == null ? null : CACHE.get(key);
    }
}
