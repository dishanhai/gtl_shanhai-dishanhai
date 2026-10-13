package com.dishanhai.gt_shanhai.common.machine.part;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 自合成仓在 EAEP / 快速上传里的配方类型映射。
 * 哨兵类型让空仓也能被配方类型搜索命中，上传中的样板类型再补进同一列表。
 */
public final class SelfSynthesisUpload {

    public static final String RECIPE_TYPE_ID = "gt_shanhai:self_synthesis";
    public static final String DISPLAY_NAME = "多方块";
    public static final String MULTIBLOCK_PREFIX = "gt_shanhai:multiblock:";

    private SelfSynthesisUpload() {}

    public static List<String> mapRecipeTypes(List<String> stored, String uploadRecipeTypeId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(RECIPE_TYPE_ID);
        if (stored != null) {
            for (String id : stored) {
                if (id != null && !id.isBlank() && !RECIPE_TYPE_ID.equals(id)) {
                    ids.add(id);
                }
            }
        }
        if (uploadRecipeTypeId != null && !uploadRecipeTypeId.isBlank()) {
            ids.add(uploadRecipeTypeId);
        }
        return List.copyOf(ids);
    }

    public static int searchBonus(Collection<String> typeIds, String query) {
        if (typeIds == null || query == null || query.isBlank() || !typeIds.contains(RECIPE_TYPE_ID)) {
            return 0;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        if (normalized.contains(DISPLAY_NAME) || normalized.contains("self_synthesis")
                || normalized.contains(RECIPE_TYPE_ID)) {
            return 50;
        }
        return 0;
    }
}
