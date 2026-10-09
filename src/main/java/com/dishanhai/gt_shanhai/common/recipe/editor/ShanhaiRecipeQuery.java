package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore;
import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ShanhaiRecipeQuery {

    public enum SearchMode {
        RECIPE_ID,
        INGREDIENT,
        OUTPUT
    }

    public enum IngredientKind {
        ITEM,
        FLUID
    }

    public record Card(String recipeTypeId, String recipeId, int duration, long eut, String fingerprint,
                       String iconKind, String iconId,
                       String inputBrief, String outputBrief, int inputCount, int outputCount) {
        public Card {
            if (iconKind == null) iconKind = "";
            if (iconId == null) iconId = "";
            if (inputBrief == null) inputBrief = "";
            if (outputBrief == null) outputBrief = "";
        }

        public Card(String recipeTypeId, String recipeId, int duration, long eut, String fingerprint,
                    String iconKind, String iconId) {
            this(recipeTypeId, recipeId, duration, eut, fingerprint, iconKind, iconId, "", "", 0, 0);
        }

        public Card(String recipeTypeId, String recipeId, int duration, long eut, String fingerprint) {
            this(recipeTypeId, recipeId, duration, eut, fingerprint, "", "");
        }
    }

    /** Card indexes refer to the cards array in this page's Result. */
    public record Group(String recipeTypeId, List<Integer> cardIndexes) {
        public Group {
            recipeTypeId = recipeTypeId == null ? "" : recipeTypeId;
            cardIndexes = cardIndexes == null ? List.of() : List.copyOf(cardIndexes);
        }
    }

    public record Result(List<Card> cards, int total, long revision, SearchMode mode, List<Group> groups) {
        public Result {
            cards = cards == null ? List.of() : List.copyOf(cards);
            mode = mode == null ? SearchMode.RECIPE_ID : mode;
            groups = groups == null ? groupByRecipeType(cards) : List.copyOf(groups);
        }

        public Result(List<Card> cards, int total, long revision) {
            this(cards, total, revision, SearchMode.RECIPE_ID, null);
        }
    }

    private ShanhaiRecipeQuery() {}

    public static Result query(String typeFilter, String text, int page, int pageSize) {
        return queryRecipeIds(typeFilter, text, page, pageSize);
    }

    public static Result query(
            String typeFilter,
            String text,
            int page,
            int pageSize,
            SearchMode mode,
            IngredientKind ingredientKind) {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return query(server, typeFilter, text, page, pageSize, mode, ingredientKind);
    }

    public static Result query(
            MinecraftServer server,
            String typeFilter,
            String text,
            int page,
            int pageSize,
            SearchMode mode,
            IngredientKind ingredientKind) {
        SearchMode safeMode = mode == null ? SearchMode.RECIPE_ID : mode;
        if (safeMode != SearchMode.RECIPE_ID) {
            return queryReverse(server, typeFilter, text, page, pageSize, safeMode, ingredientKind);
        }
        return queryRecipeIds(typeFilter, text, page, pageSize);
    }

    private static Result queryRecipeIds(String typeFilter, String text, int page, int pageSize) {
        String type = typeFilter == null ? "" : typeFilter;
        String needle = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(256, pageSize));
        List<Card> all = new ArrayList<>();
        java.util.LinkedHashSet<String> typeIds =
                new java.util.LinkedHashSet<>(RecipeOriginalSnapshotStore.typeIds());
        typeIds.addAll(DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds());
        typeIds.addAll(RecipeRebuildService.overrideTypeIds());
        net.minecraft.resources.ResourceLocation requestedType =
                type.isEmpty() ? null : net.minecraft.resources.ResourceLocation.tryParse(type);
        if (requestedType != null && GTRegistries.RECIPE_TYPES.get(requestedType) != null) {
            typeIds.add(type);
        }
        for (String typeId : typeIds) {
            if (!type.isEmpty() && !type.equals(typeId)) continue;
            List<GTRecipe> effectiveRecipes = RecipeRebuildService.buildCanonicalList(typeId, RecipeRebuildService.originalsOfType(typeId));
            for (var recipe : effectiveRecipes) {
                String recipeId = recipe.getId() == null ? "" : recipe.getId().toString();
                if (!needle.isEmpty() && !recipeId.toLowerCase(java.util.Locale.ROOT).contains(needle)
                        && !typeId.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    continue;
                }
                ShanhaiRecipeBase base = ShanhaiRecipeBase.from(recipe);
                all.add(card(typeId, recipeId, base));
            }
        }
        int from = Math.min(all.size(), safePage * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        return new Result(List.copyOf(all.subList(from, to)), all.size(),
                DShanhaiRecipeModifierAPI.getRecipeRevision());
    }

    private static Result queryReverse(
            MinecraftServer server,
            String typeFilter,
            String text,
            int page,
            int pageSize,
            SearchMode mode,
            IngredientKind ingredientKind) {
        ResourceLocation id = ResourceLocation.tryParse(text == null ? "" : text.trim());
        IngredientKind kind = ingredientKind == null ? IngredientKind.ITEM : ingredientKind;
        if (server == null || id == null) {
            return new Result(List.of(), 0, currentRevision(), mode, List.of());
        }

        List<GTRecipe> matches;
        if (kind == IngredientKind.FLUID) {
            Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(null);
            if (fluid == null) return new Result(List.of(), 0, currentRevision(), mode, List.of());
            matches = mode == SearchMode.OUTPUT
                    ? ShanhaiRecipeReverseIndex.queryFluidOutput(server, fluid)
                    : ShanhaiRecipeReverseIndex.queryFluidInput(server, fluid);
        } else {
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null) return new Result(List.of(), 0, currentRevision(), mode, List.of());
            matches = mode == SearchMode.OUTPUT
                    ? ShanhaiRecipeReverseIndex.queryByOutput(server, item)
                    : ShanhaiRecipeReverseIndex.query(server, item);
        }
        return pageMatches(matches, typeFilter, page, pageSize, mode);
    }

    private static Result pageMatches(
            List<GTRecipe> matches,
            String typeFilter,
            int page,
            int pageSize,
            SearchMode mode) {
        String type = typeFilter == null ? "" : typeFilter;
        List<Card> all = new ArrayList<>();
        for (GTRecipe recipe : matches) {
            ShanhaiRecipeBase base = ShanhaiRecipeBase.from(recipe);
            if (base == null || (!type.isEmpty() && !type.equals(base.recipeTypeId()))) continue;
            all.add(card(base.recipeTypeId(), base.recipeId(), base));
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(256, pageSize));
        int from = (int) Math.min(all.size(), (long) safePage * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        List<Card> pageCards = List.copyOf(all.subList(from, to));
        return new Result(pageCards, all.size(), currentRevision(), mode, null);
    }

    private static List<Group> groupByRecipeType(List<Card> cards) {
        Map<String, List<Integer>> indexesByType = new LinkedHashMap<>();
        if (cards != null) {
            for (int i = 0; i < cards.size(); i++) {
                Card card = cards.get(i);
                if (card == null) continue;
                indexesByType.computeIfAbsent(card.recipeTypeId(), ignored -> new ArrayList<>()).add(i);
            }
        }
        List<Group> groups = new ArrayList<>(indexesByType.size());
        indexesByType.forEach((typeId, indexes) -> groups.add(new Group(typeId, indexes)));
        return List.copyOf(groups);
    }

    public record Located(String typeId, String recipeId, ShanhaiRecipeBase base) {}

    public static Optional<ShanhaiRecipeBase> get(String typeId, String recipeId) {
        Located located = locate(typeId, recipeId);
        return located == null ? Optional.empty() : Optional.of(located.base());
    }

    /** Snapshot hit, or a live lookup capture for a type that has not been rebuilt yet. */
    public static Located locate(String typeId, String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return null;
        String type = typeId == null ? "" : typeId;
        ShanhaiRecipeBase direct = baseOf(RecipeRebuildService.editableOf(type, recipeId));
        if (direct != null) return new Located(type, recipeId, direct);
        String liveType = RecipeRebuildService.captureLive(type, recipeId);
        if (liveType == null || liveType.isEmpty()) return null;
        ShanhaiRecipeBase live = baseOf(RecipeRebuildService.editableOf(liveType, recipeId));
        return live == null ? null : new Located(liveType, recipeId, live);
    }

    private static ShanhaiRecipeBase baseOf(GTRecipe recipe) {
        return recipe == null ? null : ShanhaiRecipeBase.from(recipe);
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
            cards.add(card(base.recipeTypeId(), base.recipeId(), base));
        }
        return new Result(List.copyOf(cards), cards.size(), currentRevision(),
                output ? SearchMode.OUTPUT : SearchMode.INGREDIENT, null);
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
            cards.add(card(base.recipeTypeId(), base.recipeId(), base));
        }
        return new Result(List.copyOf(cards), cards.size(), currentRevision(),
                output ? SearchMode.OUTPUT : SearchMode.INGREDIENT, null);
    }

    private static Card card(String typeId, String recipeId, ShanhaiRecipeBase base) {
        String[] icon = outputIcon(base.outputs());
        return new Card(typeId, recipeId, base.duration(), base.eut(),
                ShanhaiRecipeFingerprint.of(base), icon[0], icon[1]);
    }

    /** First concrete output, so the recipe card can blit that texture. Item wins over fluid. */
    static String[] outputIcon(JsonObject outputs) {
        String item = firstId(outputs, "item", "item");
        if (!item.isEmpty()) return new String[] {"item", item};
        String tag = firstId(outputs, "item", "tag");
        if (!tag.isEmpty()) return new String[] {"tag", tag};
        String fluid = firstId(outputs, "fluid", "fluid");
        if (!fluid.isEmpty()) return new String[] {"fluid", fluid};
        return new String[] {"", ""};
    }

    private static String firstId(JsonObject table, String section, String key) {
        if (table == null || !table.has(section) || !table.get(section).isJsonArray()) return "";
        for (JsonElement element : table.getAsJsonArray(section)) {
            if (!element.isJsonObject()) continue;
            String found = findPrimitive(element.getAsJsonObject(), key);
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    private static String findPrimitive(JsonObject object, String key) {
        if (object.has(key) && object.get(key).isJsonPrimitive()) {
            String value = object.get(key).getAsString();
            if (value.indexOf(':') > 0) return value;
        }
        for (var entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                String found = findPrimitive(value.getAsJsonObject(), key);
                if (!found.isEmpty()) return found;
            } else if (value.isJsonArray()) {
                for (JsonElement child : value.getAsJsonArray()) {
                    if (!child.isJsonObject()) continue;
                    String found = findPrimitive(child.getAsJsonObject(), key);
                    if (!found.isEmpty()) return found;
                }
            }
        }
        return "";
    }

    public static long currentRevision() {
        return DShanhaiRecipeModifierAPI.getRecipeRevision();
    }
}
