package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ShanhaiRecipeQuery {

    public record Card(String recipeTypeId, String recipeId, int duration, long eut, String fingerprint) {}

    public record Result(List<Card> cards, int total, long revision) {}

    private ShanhaiRecipeQuery() {}

    public static Result query(String typeFilter, String text, int page, int pageSize) {
        String type = typeFilter == null ? "" : typeFilter;
        String needle = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(256, pageSize));
        List<Card> all = new ArrayList<>();
        java.util.LinkedHashSet<String> typeIds =
                new java.util.LinkedHashSet<>(RecipeOriginalSnapshotStore.typeIds());
        typeIds.addAll(DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds());
        for (String typeId : typeIds) {
            if (!type.isEmpty() && !type.equals(typeId)) continue;
            for (var recipe : RecipeOriginalSnapshotStore.copiesOf(typeId)) {
                String recipeId = recipe.getId() == null ? "" : recipe.getId().toString();
                if (!needle.isEmpty() && !recipeId.toLowerCase(java.util.Locale.ROOT).contains(needle)
                        && !typeId.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    continue;
                }
                ShanhaiRecipeBase base = ShanhaiRecipeBase.from(recipe);
                all.add(new Card(typeId, recipeId, base.duration(), base.eut(),
                        ShanhaiRecipeFingerprint.of(base)));
            }
        }
        int from = Math.min(all.size(), safePage * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        return new Result(List.copyOf(all.subList(from, to)), all.size(),
                DShanhaiRecipeModifierAPI.getRecipeRevision());
    }

    public static Optional<ShanhaiRecipeBase> get(String typeId, String recipeId) {
        var recipe = RecipeOriginalSnapshotStore.copyOf(typeId, recipeId);
        return recipe == null ? Optional.empty() : Optional.ofNullable(ShanhaiRecipeBase.from(recipe));
    }

    public static long currentRevision() {
        return DShanhaiRecipeModifierAPI.getRecipeRevision();
    }
}
