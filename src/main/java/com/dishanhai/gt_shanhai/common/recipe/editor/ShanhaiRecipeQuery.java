package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

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

    /**
     * Fluid counterpart of the item query used by the first editor screen.
     * Fluid inputs and outputs are indexed separately so "source" and "use"
     * keep the same meaning as the item buttons.
     */
    public static Result byInputFluid(MinecraftServer server, Fluid fluid) {
        return byFluid(server, fluid, false);
    }

    public static Result byOutputFluid(MinecraftServer server, Fluid fluid) {
        return byFluid(server, fluid, true);
    }

    public static Result byInput(MinecraftServer server, Item item) {
        return byItem(server, item, false);
    }

    public static Result byOutput(MinecraftServer server, Item item) {
        return byItem(server, item, true);
    }

    public static String fluidIdOf(Fluid fluid) {
        return ShanhaiRecipeReverseIndex.fluidIdOf(fluid);
    }

    private static Result byFluid(MinecraftServer server, Fluid fluid, boolean output) {
        if (server == null || fluid == null) {
            return new Result(List.of(), 0, currentRevision());
        }
        List<GTRecipe> matches = output
                ? ShanhaiRecipeReverseIndex.queryFluidOutput(server, fluid)
                : ShanhaiRecipeReverseIndex.queryFluidInput(server, fluid);
        List<Card> cards = new ArrayList<>(matches.size());
        for (GTRecipe recipe : matches) {
            ShanhaiRecipeBase base = ShanhaiRecipeBase.from(recipe);
            if (base == null) continue;
            cards.add(new Card(base.recipeTypeId(), base.recipeId(), base.duration(), base.eut(),
                    ShanhaiRecipeFingerprint.of(base)));
        }
        return new Result(List.copyOf(cards), cards.size(), currentRevision());
    }

    private static Result byItem(MinecraftServer server, Item item, boolean output) {
        if (server == null || item == null) {
            return new Result(List.of(), 0, currentRevision());
        }
        List<GTRecipe> matches = output
                ? ShanhaiRecipeReverseIndex.queryByOutput(server, item)
                : ShanhaiRecipeReverseIndex.query(server, item);
        List<Card> cards = new ArrayList<>(matches.size());
        for (GTRecipe recipe : matches) {
            ShanhaiRecipeBase base = ShanhaiRecipeBase.from(recipe);
            if (base == null) continue;
            cards.add(new Card(base.recipeTypeId(), base.recipeId(), base.duration(), base.eut(),
                    ShanhaiRecipeFingerprint.of(base)));
        }
        return new Result(List.copyOf(cards), cards.size(), currentRevision());
    }

    public static long currentRevision() {
        return DShanhaiRecipeModifierAPI.getRecipeRevision();
    }
}
