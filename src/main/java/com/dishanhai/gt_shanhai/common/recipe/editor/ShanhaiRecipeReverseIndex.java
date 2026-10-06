package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side reverse indexes used by the editor query screen.
 *
 * <p>Both input and output indexes are built from the live RecipeManager
 * snapshot. Fluid lookup is intentionally keyed by the registered Fluid
 * instance, so tags and alternate ingredient forms remain visible through
 * their concrete candidates.</p>
 */
public final class ShanhaiRecipeReverseIndex {

    private static final List<GTRecipe> RECIPES = new ArrayList<>();
    private static final Map<Item, int[]> ITEM_INPUTS = new HashMap<>();
    private static final Map<Item, int[]> ITEM_OUTPUTS = new HashMap<>();
    private static final Map<Fluid, int[]> FLUID_INPUTS = new HashMap<>();
    private static final Map<Fluid, int[]> FLUID_OUTPUTS = new HashMap<>();
    private static volatile boolean built;
    private static volatile boolean verified;
    private static volatile boolean verifiedOutput;
    private static volatile boolean verifiedFluidInput;
    private static volatile boolean verifiedFluidOutput;

    private ShanhaiRecipeReverseIndex() {}

    public static synchronized void build(MinecraftServer server) {
        invalidate();
        if (server == null || server.getRecipeManager() == null) return;
        for (var value : server.getRecipeManager().getRecipes()) {
            if (!(value instanceof GTRecipe recipe)) continue;
            int index = RECIPES.size();
            RECIPES.add(recipe);
            indexItems(ITEM_INPUTS, itemsOf(recipe, false), index);
            indexItems(ITEM_OUTPUTS, itemsOf(recipe, true), index);
            indexFluids(FLUID_INPUTS, fluidsOf(recipe, false), index);
            indexFluids(FLUID_OUTPUTS, fluidsOf(recipe, true), index);
        }
        built = true;
        Item inputProbe = ITEM_INPUTS.keySet().stream().findFirst().orElse(null);
        Item outputProbe = ITEM_OUTPUTS.keySet().stream().findFirst().orElse(null);
        Fluid fluidInputProbe = FLUID_INPUTS.keySet().stream().findFirst().orElse(null);
        Fluid fluidOutputProbe = FLUID_OUTPUTS.keySet().stream().findFirst().orElse(null);
        verified = inputProbe == null
                || queryIndexOnly(inputProbe).size() == linearQueryByItems(server, inputProbe).size();
        verifiedOutput = outputProbe == null
                || queryOutputIndexOnly(outputProbe).size() == linearQueryByOutputItems(server, outputProbe).size();
        verifiedFluidInput = fluidInputProbe == null
                || queryFluidInputIndexOnly(fluidInputProbe).size()
                == linearQueryByFluids(server, fluidInputProbe, false).size();
        verifiedFluidOutput = fluidOutputProbe == null
                || queryFluidOutputIndexOnly(fluidOutputProbe).size()
                == linearQueryByFluids(server, fluidOutputProbe, true).size();
    }

    public static synchronized void invalidate() {
        built = false;
        RECIPES.clear();
        ITEM_INPUTS.clear();
        ITEM_OUTPUTS.clear();
        FLUID_INPUTS.clear();
        FLUID_OUTPUTS.clear();
        verified = false;
        verifiedOutput = false;
        verifiedFluidInput = false;
        verifiedFluidOutput = false;
    }

    public static void ensure(MinecraftServer server) {
        if (!built) {
            synchronized (ShanhaiRecipeReverseIndex.class) {
                if (!built) build(server);
            }
        }
    }

    public static boolean isBuilt() {
        return built;
    }

    public static boolean isVerified() {
        return verified;
    }

    public static boolean isOutputVerified() {
        return verifiedOutput;
    }

    public static boolean isFluidInputVerified() {
        return verifiedFluidInput;
    }

    public static boolean isFluidOutputVerified() {
        return verifiedFluidOutput;
    }

    public static List<GTRecipe> query(MinecraftServer server, Item item) {
        ensure(server);
        return verified ? fromIndex(ITEM_INPUTS, item) : linearQueryByItems(server, item);
    }

    public static List<GTRecipe> queryByOutput(MinecraftServer server, Item item) {
        ensure(server);
        return verifiedOutput ? fromIndex(ITEM_OUTPUTS, item) : linearQueryByOutputItems(server, item);
    }

    public static List<GTRecipe> queryFluidInput(MinecraftServer server, Fluid fluid) {
        ensure(server);
        return verifiedFluidInput
                ? fromIndex(FLUID_INPUTS, fluid)
                : linearQueryByFluids(server, fluid, false);
    }

    public static List<GTRecipe> queryFluidOutput(MinecraftServer server, Fluid fluid) {
        ensure(server);
        return verifiedFluidOutput
                ? fromIndex(FLUID_OUTPUTS, fluid)
                : linearQueryByFluids(server, fluid, true);
    }

    public static List<GTRecipe> queryIndexOnly(Item item) {
        return fromIndex(ITEM_INPUTS, item);
    }

    public static List<GTRecipe> queryOutputIndexOnly(Item item) {
        return fromIndex(ITEM_OUTPUTS, item);
    }

    public static List<GTRecipe> linearQueryByItems(MinecraftServer server, Item item) {
        return linearQueryByItems(server, item, false);
    }

    public static List<GTRecipe> linearQueryByOutputItems(MinecraftServer server, Item item) {
        return linearQueryByItems(server, item, true);
    }

    public static List<GTRecipe> queryFluidInputIndexOnly(Fluid fluid) {
        return fromIndex(FLUID_INPUTS, fluid);
    }

    public static List<GTRecipe> queryFluidOutputIndexOnly(Fluid fluid) {
        return fromIndex(FLUID_OUTPUTS, fluid);
    }

    public static List<GTRecipe> linearQueryByFluids(
            MinecraftServer server, Fluid fluid, boolean output) {
        List<GTRecipe> result = new ArrayList<>();
        if (server == null || fluid == null) return result;
        for (var value : server.getRecipeManager().getRecipes()) {
            if (!(value instanceof GTRecipe recipe)) continue;
            Set<Fluid> fluids = fluidsOf(recipe, output);
            if (fluids.contains(fluid)) result.add(recipe);
        }
        return result;
    }

    public static int fluidInputEntryCount() {
        return entryCount(FLUID_INPUTS);
    }

    public static int fluidOutputEntryCount() {
        return entryCount(FLUID_OUTPUTS);
    }

    public static int outputEntryCount() {
        return entryCount(ITEM_OUTPUTS);
    }

    public static String fluidIdOf(Fluid fluid) {
        if (fluid == null) return "?";
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
        return id == null ? "?" : id.toString();
    }

    public static String statsLine() {
        return "reverse_index recipes=" + RECIPES.size()
                + " item_in=" + entryCount(ITEM_INPUTS)
                + " item_out=" + entryCount(ITEM_OUTPUTS)
                + " fluid_in=" + entryCount(FLUID_INPUTS)
                + " fluid_out=" + entryCount(FLUID_OUTPUTS);
    }

    public static String fluidDiagnosisLine() {
        return "input_verified=" + verifiedFluidInput
                + " output_verified=" + verifiedFluidOutput
                + " input_entries=" + entryCount(FLUID_INPUTS)
                + " output_entries=" + entryCount(FLUID_OUTPUTS);
    }

    private static int entryCount(Map<?, int[]> index) {
        int total = 0;
        for (int[] values : index.values()) total += values == null ? 0 : values.length;
        return total;
    }

    private static List<GTRecipe> linearQueryByItems(
            MinecraftServer server, Item item, boolean output) {
        List<GTRecipe> result = new ArrayList<>();
        if (server == null || item == null) return result;
        for (var value : server.getRecipeManager().getRecipes()) {
            if (!(value instanceof GTRecipe recipe)) continue;
            if (itemsOf(recipe, output).contains(item)) result.add(recipe);
        }
        return result;
    }

    private static <K> List<GTRecipe> fromIndex(Map<K, int[]> index, K key) {
        if (key == null) return List.of();
        int[] positions = index.get(key);
        if (positions == null || positions.length == 0) return List.of();
        List<GTRecipe> result = new ArrayList<>(positions.length);
        for (int position : positions) {
            if (position >= 0 && position < RECIPES.size()) result.add(RECIPES.get(position));
        }
        return List.copyOf(result);
    }

    private static Set<Item> itemsOf(GTRecipe recipe, boolean output) {
        Set<Item> result = new LinkedHashSet<>();
        List<Content> contents = output
                ? recipe.getOutputContents(ItemRecipeCapability.CAP)
                : recipe.getInputContents(ItemRecipeCapability.CAP);
        if (contents == null) return result;
        for (Content content : contents) {
            if (content == null) continue;
            try {
                Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
                if (ingredient == null) continue;
                for (ItemStack stack : ingredient.getItems()) {
                    if (stack != null && !stack.isEmpty()) result.add(stack.getItem());
                }
            } catch (Throwable ignored) {
                // A malformed single ingredient must not invalidate the whole index.
            }
        }
        return result;
    }

    private static Set<Fluid> fluidsOf(GTRecipe recipe, boolean output) {
        Set<Fluid> result = new LinkedHashSet<>();
        List<Content> contents = output
                ? recipe.getOutputContents(FluidRecipeCapability.CAP)
                : recipe.getInputContents(FluidRecipeCapability.CAP);
        if (contents == null) return result;
        for (Content content : contents) {
            if (content == null) continue;
            try {
                FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
                if (ingredient == null) continue;
                for (com.lowdragmc.lowdraglib.side.fluid.FluidStack stack : ingredient.getStacks()) {
                    if (stack != null && !stack.isEmpty() && stack.getFluid() != null) {
                        result.add(stack.getFluid());
                    }
                }
            } catch (Throwable ignored) {
                // Keep the index usable when one custom ingredient cannot be decoded.
            }
        }
        return result;
    }

    private static <K> void indexItems(Map<K, int[]> index, Set<K> keys, int recipeIndex) {
        for (K key : keys) {
            int[] old = index.get(key);
            if (old == null) {
                index.put(key, new int[] {recipeIndex});
            } else {
                int[] next = java.util.Arrays.copyOf(old, old.length + 1);
                next[old.length] = recipeIndex;
                index.put(key, next);
            }
        }
    }

    private static <K> void indexFluids(Map<K, int[]> index, Set<K> keys, int recipeIndex) {
        indexItems(index, keys, recipeIndex);
    }
}
