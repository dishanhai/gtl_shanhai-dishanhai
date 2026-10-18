package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.misc.RecipeManagerReflectionUtil;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeTableHook;
import com.google.common.collect.ImmutableMap;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.UnificationEntry;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分子解构的反向配方：1 粉 → 144mb 流体，逆熵构成则是 144mb 流体 → 1 粉。
 * 电压和耗时沿用原配方。沙子、方块这类不是粉的分子解构不生成。
 */
public final class PrimordialReverseEntropyRecipes {
    public static final long MB_PER_DUST = 144L;
    private static final String SOURCE_PATH = "molecular_deconstruction";
    private static final String TYPE_PATH = "primordial_reverse_entropy_constitution";

    private PrimordialReverseEntropyRecipes() {}

    public static void install(RecipeManager manager) {
        if (manager == null) return;
        GTRecipeType target = DShanhaiRecipeTypes.PRIMORDIAL_REVERSE_ENTROPY_CONSTITUTION;
        GTRecipeType source = GTLAddRecipesTypes.MOLECULAR_DECONSTRUCTION;
        if (target == null || source == null) return;

        List<GTRecipe> created = new ArrayList<>();
        for (Recipe<?> recipe : manager.getAllRecipesFor(source)) {
            if (!(recipe instanceof GTRecipe gt)) continue;
            GTRecipe inverse = tryInvert(gt, target);
            if (inverse != null) created.add(inverse);
        }
        if (created.isEmpty()) return;

        writeManager(manager, target, created);
        writeLookup(target, created);
        GTDishanhaiMod.LOGGER.info("[逆熵构成] 已由分子解构反向生成 {} 条配方", created.size());
    }

    /** KubeJS 配方窗口里的全名表。后面的 apply 会把它收进配方管理器。 */
    public static void addToNameMap(Map<ResourceLocation, Recipe<?>> recipesByName) {
        if (recipesByName == null || recipesByName.isEmpty()) return;
        GTRecipeType target = DShanhaiRecipeTypes.PRIMORDIAL_REVERSE_ENTROPY_CONSTITUTION;
        if (target == null) return;
        List<GTRecipe> created = new ArrayList<>();
        for (Recipe<?> recipe : new ArrayList<>(recipesByName.values())) {
            if (!(recipe instanceof GTRecipe gt)) continue;
            GTRecipe inverse = tryInvert(gt, target);
            if (inverse != null) created.add(inverse);
        }
        for (GTRecipe recipe : created) {
            if (recipe.getId() != null) recipesByName.put(recipe.getId(), recipe);
        }
        if (!created.isEmpty()) {
            GTDishanhaiMod.LOGGER.info("[逆熵构成] 写入配方名表 {} 条", created.size());
        }
    }

    static boolean isInverseRatio(long dustCount, long milliBuckets) {
        return dustCount > 0 && milliBuckets == dustCount * MB_PER_DUST;
    }

    private static GTRecipe tryInvert(GTRecipe source, GTRecipeType target) {
        if (source == null || source.getId() == null || !isMolecularDeconstruction(source)) return null;
        List<Content> items = source.getInputContents(ItemRecipeCapability.CAP);
        List<Content> itemOutputs = source.getOutputContents(ItemRecipeCapability.CAP);
        List<Content> fluidInputs = source.getInputContents(FluidRecipeCapability.CAP);
        List<Content> fluids = source.getOutputContents(FluidRecipeCapability.CAP);
        if (items.size() != 1 || fluids.size() != 1 || !itemOutputs.isEmpty() || !fluidInputs.isEmpty()) {
            return null;
        }
        long dustCount = dustCount(items.get(0));
        long fluidAmount = fluidAmount(fluids.get(0));
        if (!isInverseRatio(dustCount, fluidAmount)) return null;

        ResourceLocation id = new ResourceLocation("gt_shanhai",
                TYPE_PATH + "/" + source.getId().getNamespace() + "/" + source.getId().getPath());
        return new GTRecipe(
                target,
                id,
                copy(source, source.outputs),
                copy(source, source.inputs),
                copy(source, source.tickInputs),
                copy(source, source.tickOutputs),
                copyChances(source.outputChanceLogics),
                copyChances(source.inputChanceLogics),
                copyChances(source.tickInputChanceLogics),
                copyChances(source.tickOutputChanceLogics),
                new ArrayList<>(),
                new ArrayList<>(),
                new CompoundTag(),
                source.duration,
                false);
    }

    private static boolean isMolecularDeconstruction(GTRecipe recipe) {
        return recipe.recipeType != null
                && recipe.recipeType.registryName != null
                && SOURCE_PATH.equals(recipe.recipeType.registryName.getPath());
    }

    private static long dustCount(Content content) {
        if (content == null || content.getContent() == null) return -1L;
        Object raw = content.getContent();
        if (raw instanceof SizedIngredient sized) {
            if (!isDust(sized.getInner())) return -1L;
            return sized.getAmount();
        }
        if (raw instanceof Ingredient ingredient) {
            if (!isDust(ingredient)) return -1L;
            return 1L;
        }
        return -1L;
    }

    private static boolean isDust(Ingredient ingredient) {
        if (ingredient == null) return false;
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length == 0) return false;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) return false;
            UnificationEntry entry = ChemicalHelper.getUnificationEntry(stack.getItem());
            if (entry == null || entry.tagPrefix != TagPrefix.dust) return false;
        }
        return true;
    }

    private static long fluidAmount(Content content) {
        if (content == null || !(content.getContent() instanceof FluidIngredient fluid)) return -1L;
        return fluid.getAmount();
    }

    private static Map<RecipeCapability<?>, List<Content>> copy(GTRecipe source,
                                                                Map<RecipeCapability<?>, List<Content>> contents) {
        if (contents == null || contents.isEmpty()) return new HashMap<>();
        Map<RecipeCapability<?>, List<Content>> copied = source.copyContents(contents, null);
        return copied == null ? new HashMap<>() : copied;
    }

    private static Map<RecipeCapability<?>, ChanceLogic> copyChances(Map<RecipeCapability<?>, ChanceLogic> chances) {
        if (chances == null || chances.isEmpty()) return new HashMap<>();
        return new HashMap<>(chances);
    }

    private static void writeLookup(GTRecipeType target, List<GTRecipe> created) {
        if (target.getLookup() == null) return;
        target.getLookup().removeAllRecipes();
        for (GTRecipe recipe : created) target.getLookup().addRecipe(recipe);
    }

    private static void writeManager(RecipeManager manager, GTRecipeType target, List<GTRecipe> created) {
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = RecipeManagerReflectionUtil.resolve(manager);
        if (maps == null) return;

        Map<ResourceLocation, Recipe<?>> replacement = new HashMap<>();
        for (GTRecipe recipe : created) {
            if (recipe.getId() != null) replacement.put(recipe.getId(), recipe);
        }

        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType = new HashMap<>(maps.recipesMap());
        Map<ResourceLocation, Recipe<?>> old = byType.put(target, replacement);
        Map<ResourceLocation, Recipe<?>> byName = new HashMap<>(maps.byNameMap());
        if (old != null) {
            for (ResourceLocation id : old.keySet()) byName.remove(id);
        }
        byName.putAll(replacement);
        try {
            maps.recipesField().set(manager, ImmutableMap.copyOf(byType));
            maps.byNameField().set(manager, ImmutableMap.copyOf(byName));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("无法写入逆熵构成配方", e);
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.getRecipeManager() == manager) {
            ShanhaiRecipeTableHook.invalidateCaches(server);
        }
    }
}
