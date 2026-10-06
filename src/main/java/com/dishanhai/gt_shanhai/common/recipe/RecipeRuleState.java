package com.dishanhai.gt_shanhai.common.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RecipeRuleState {

    public record StripRule(String targetItem, boolean input, boolean fluid, String recipeId) {}

    public record ReplaceRule(
            String oldItem,
            String newItem,
            boolean oldFluid,
            boolean newFluid,
            String recipeId,
            int count,
            int circuitNumber) {}

    public record DeleteRule(String recipeRegex) {}

    private final Map<String, List<StripRule>> stripRules;
    private final Map<String, List<ReplaceRule>> replaceRules;
    private final Map<String, List<DeleteRule>> deleteRules;
    private final Map<String, Boolean> toggles;
    private final List<String> activePresets;

    private RecipeRuleState(
            Map<String, List<StripRule>> stripRules,
            Map<String, List<ReplaceRule>> replaceRules,
            Map<String, List<DeleteRule>> deleteRules,
            Map<String, Boolean> toggles,
            List<String> activePresets) {
        this.stripRules = freeze(stripRules);
        this.replaceRules = freeze(replaceRules);
        this.deleteRules = freeze(deleteRules);
        this.toggles = Collections.unmodifiableMap(new LinkedHashMap<>(toggles));
        this.activePresets = Collections.unmodifiableList(new ArrayList<>(activePresets));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, List<StripRule>> stripRules() {
        return stripRules;
    }

    public Map<String, List<ReplaceRule>> replaceRules() {
        return replaceRules;
    }

    public Map<String, List<DeleteRule>> deleteRules() {
        return deleteRules;
    }

    public Map<String, Boolean> toggles() {
        return toggles;
    }

    public List<String> activePresets() {
        return activePresets;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RecipeRuleState that)) return false;
        return stripRules.equals(that.stripRules)
                && replaceRules.equals(that.replaceRules)
                && deleteRules.equals(that.deleteRules)
                && toggles.equals(that.toggles)
                && activePresets.equals(that.activePresets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stripRules, replaceRules, deleteRules, toggles, activePresets);
    }

    private static <T> Map<String, List<T>> freeze(Map<String, List<T>> source) {
        Map<String, List<T>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<T>> entry : source.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    public static final class Builder {
        private final Map<String, List<StripRule>> stripRules = new LinkedHashMap<>();
        private final Map<String, List<ReplaceRule>> replaceRules = new LinkedHashMap<>();
        private final Map<String, List<DeleteRule>> deleteRules = new LinkedHashMap<>();
        private final Map<String, Boolean> toggles = new LinkedHashMap<>();
        private final List<String> activePresets = new ArrayList<>();

        public Builder addStrip(String typeId, String targetItem, boolean input, boolean fluid, String recipeId) {
            stripRules.computeIfAbsent(typeId, key -> new ArrayList<>())
                    .add(new StripRule(targetItem, input, fluid, recipeId));
            return this;
        }

        public Builder addReplace(
                String typeId,
                String oldItem,
                String newItem,
                boolean oldFluid,
                boolean newFluid,
                String recipeId,
                int count,
                int circuitNumber) {
            replaceRules.computeIfAbsent(typeId, key -> new ArrayList<>())
                    .add(new ReplaceRule(oldItem, newItem, oldFluid, newFluid, recipeId, count, circuitNumber));
            return this;
        }

        public Builder addDelete(String typeId, String recipeRegex) {
            deleteRules.computeIfAbsent(typeId, key -> new ArrayList<>())
                    .add(new DeleteRule(recipeRegex));
            return this;
        }

        public Builder setToggle(String recipeId, boolean enabled) {
            toggles.put(recipeId, enabled);
            return this;
        }

        public Builder addActivePreset(String presetName) {
            if (presetName != null && !presetName.isEmpty() && !activePresets.contains(presetName)) {
                activePresets.add(presetName);
            }
            return this;
        }

        public RecipeRuleState build() {
            return new RecipeRuleState(stripRules, replaceRules, deleteRules, toggles, activePresets);
        }
    }
}
