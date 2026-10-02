package com.dishanhai.gt_shanhai.client.shop;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;

/** 客户端展示用的玩家提交解锁状态。服务端购买校验仍是最终权威。 */
public final class ClientShopUnlockState {
    private static final Set<String> KEYS = new LinkedHashSet<>();
    private static final Map<String, List<ExchangeEntry.Ingredient>> STAGE_REQUIREMENTS = new LinkedHashMap<>();

    private ClientShopUnlockState() {}

    public static void replace(Iterable<String> keys) {
        replace(keys, null);
    }

    public static void replace(Iterable<String> keys, Map<String, List<ExchangeEntry.Ingredient>> stageRequirements) {
        KEYS.clear();
        ClientCostPreview.clearStage();
        if (keys != null) for (String key : keys) if (key != null && !key.isBlank()) KEYS.add(key);
        STAGE_REQUIREMENTS.clear();
        if (stageRequirements != null) {
            for (Map.Entry<String, List<ExchangeEntry.Ingredient>> entry : stageRequirements.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                STAGE_REQUIREMENTS.put(entry.getKey(), List.copyOf(entry.getValue() == null ? List.of() : entry.getValue()));
            }
        }
    }

    public static void add(String key) {
        if (key != null && !key.isBlank()) KEYS.add(key);
    }

    public static boolean has(String key) {
        return key != null && KEYS.contains(key);
    }

    public static boolean hasEntry(String stableId) {
        return has("entry:" + stableId);
    }

    public static boolean hasStage(String path) {
        return has("stage:" + path);
    }

    public static boolean hasStageRequirement(String path) {
        return has("stage_req:" + path);
    }

    public static List<ExchangeEntry.Ingredient> stageRequirements(String path) {
        if (path == null || path.isBlank()) return List.of();
        return STAGE_REQUIREMENTS.getOrDefault(path, List.of());
    }

    public static void clear() {
        KEYS.clear();
        STAGE_REQUIREMENTS.clear();
    }
}
