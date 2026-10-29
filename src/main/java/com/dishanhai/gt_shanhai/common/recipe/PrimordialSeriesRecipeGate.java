package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialSeriesMachines;
import com.dishanhai.gt_shanhai.common.misc.RecipeManagerReflectionUtil;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.google.common.collect.ImmutableMap;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「禁用原初系列機器」的配方閘門。
 *
 * <p>被禁用的機器方塊仍在，只是從 GT 機器表移除；因此還要把它們的產出配方一起拿掉，
 * 否則 JEI / 星律樣板仍會看到「做不出來卻能查到的機器」。只移除「產出被禁機器」的配方，
 * 不動任何外部配方類型本身，KubeJS 與快取資料包不會因未知配方類型中斷。
 */
public final class PrimordialSeriesRecipeGate {

    private PrimordialSeriesRecipeGate() {}

    public static void stripLoaded(RecipeManager manager) {
        if (manager == null || !DShanhaiConfig.isPrimordialSeriesDisabled()) {
            return;
        }
        int removed = stripManager(manager);
        if (removed > 0) {
            GTDishanhaiMod.LOGGER.info("[山海原初] 已移除 {} 條產出被禁用原初機器的配方", removed);
        }
        stripLookups();
    }

    private static int stripManager(RecipeManager manager) {
        RecipeManagerReflectionUtil.RecipeManagerMaps maps = RecipeManagerReflectionUtil.resolve(manager);
        if (maps == null) {
            return 0;
        }
        List<ResourceLocation> removeIds = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Recipe<?>> entry : maps.byNameMap().entrySet()) {
            if (rejects(entry.getValue())) {
                removeIds.add(entry.getKey());
            }
        }
        if (removeIds.isEmpty()) {
            return 0;
        }
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType = new LinkedHashMap<>();
        for (Map.Entry<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> entry : maps.recipesMap().entrySet()) {
            Map<ResourceLocation, Recipe<?>> next = new LinkedHashMap<>(entry.getValue());
            for (ResourceLocation id : removeIds) {
                next.remove(id);
            }
            byType.put(entry.getKey(), next);
        }
        Map<ResourceLocation, Recipe<?>> byName = new LinkedHashMap<>(maps.byNameMap());
        for (ResourceLocation id : removeIds) {
            byName.remove(id);
        }
        try {
            maps.recipesField().set(manager, ImmutableMap.copyOf(byType));
            maps.byNameField().set(manager, ImmutableMap.copyOf(byName));
        } catch (IllegalAccessException e) {
            GTDishanhaiMod.LOGGER.warn("[山海原初] 無法寫回配方表: {}", e.getMessage());
            return 0;
        }
        return removeIds.size();
    }

    /** 配方是否產出任意一台「被配置禁用的原初機器」。 */
    static boolean rejects(Recipe<?> recipe) {
        if (!(recipe instanceof GTRecipe)) {
            return false;
        }
        for (String path : PrimordialSeriesMachines.disableableIds()) {
            ResourceLocation id = new ResourceLocation("gt_shanhai", path);
            if (PrimordialSeriesMachines.isDisabled(id)
                    && CoinForgeLoadGate.outputsMachineId(recipe, id)) {
                return true;
            }
        }
        return false;
    }

    /** 移除 lookup 中對應的配方（資料包載入後 GT 自帶查找表）。 */
    public static void stripLookups() {
        if (!DShanhaiConfig.isPrimordialSeriesDisabled()) {
            return;
        }
        int removed = 0;
        for (var type : GTRegistries.RECIPE_TYPES) {
            if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) {
                continue;
            }
            List<GTRecipe> recipes = type.getLookup().getLookup().getRecipes(false).toList();
            if (recipes.isEmpty()) {
                continue;
            }
            List<GTRecipe> kept = new ArrayList<>();
            int rejected = 0;
            for (GTRecipe recipe : recipes) {
                if (rejects(recipe)) {
                    rejected++;
                } else {
                    kept.add(recipe);
                }
            }
            if (rejected == 0) {
                continue;
            }
            type.getLookup().removeAllRecipes();
            for (GTRecipe recipe : kept) {
                type.getLookup().addRecipe(recipe);
            }
            removed += rejected;
        }
        if (removed > 0) {
            GTDishanhaiMod.LOGGER.info("[山海原初] 已從配方查找表移除 {} 條產出被禁機器的配方", removed);
        }
    }
}
