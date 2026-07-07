package com.dishanhai.gt_shanhai.common.machine.misc.workshop;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleWirelessRecipesLogic;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工坊模块配方逻辑。
 * 时长折扣通过 getMaxProgress 生效，EU 折扣通过缩放最终无线配方的 EU 内容生效。
 */
public class EternalGregTechWorkshopModuleRecipeLogic extends GTLAddMultipleWirelessRecipesLogic {

    private final EternalGregTechWorkshopModuleMachine module;

    private double cachedDurationMod = 1.0D;
    private double cachedEutDiscount = 1.0D;
    private GTRecipe originalRecipeForReport;

    public EternalGregTechWorkshopModuleRecipeLogic(EternalGregTechWorkshopModuleMachine module) {
        super(module);
        this.module = module;
    }

    @Override
    public int getMultipleThreads() {
        return 1;
    }

    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        if (isLock()) {
            GTRecipe locked = getLockRecipe();
            if (locked == null) {
                locked = findActiveWorkshopRecipe();
                setLockRecipe(locked);
            } else if (!isActiveRecipeType(locked.recipeType)) {
                setLockRecipe(null);
                locked = findActiveWorkshopRecipe();
                setLockRecipe(locked);
            } else if (!checkRecipe(locked)) {
                return Collections.emptySet();
            }
            return locked == null ? Collections.emptySet() : Collections.singleton(locked);
        }

        Set<GTRecipe> recipes = new LinkedHashSet<>();
        GTRecipeType type = module.getRecipeType();
        if (type == null) {
            module.setWorkshopRecipeStatus("未注册配方类型");
            return recipes;
        }
        GTRecipe recipe = findWorkshopRecipe(type);
        if (recipe != null) {
            recipes.add(recipe);
        }
        if (recipes.isEmpty()) {
            module.setWorkshopRecipeStatus("未找到匹配配方: " + module.getWorkshopActiveRecipeTypeName());
        }
        return recipes;
    }

    @Override
    protected GTRecipe getGTRecipe() {
        GTRecipe activeRecipe = findActiveWorkshopRecipe();
        if (!module.canRunWorkshopRecipe(activeRecipe)) {
            clearWorkshopRecipeCache();
            return null;
        }

        // 直接让基类搜索——getRecipeType() 已返回当前页的正确类型
        GTRecipe recipe = super.getGTRecipe();
        if (recipe == null) {
            clearWorkshopRecipeCache();
            return null;
        }

        // 拒绝非当前页配方（防止无线基类的 multi-type 迭代污染）
        if (!isActiveRecipeType(recipe.recipeType) && !isSyntheticWirelessRecipe(recipe)) {
            module.setWorkshopRecipeMatched(recipe, "已拒绝非当前页配方");
            clearWorkshopRecipeCache();
            return null;
        }

        cachedDurationMod = module.getEffectiveDurationModifier();
        cachedEutDiscount = module.getEffectiveEUtDiscount();
        originalRecipeForReport = activeRecipe;
        module.setWorkshopRecipeMatched(activeRecipe, "已匹配当前页配方，等待输入");

        // EU 折扣：对最终无线配方的 EU 内容进行缩放，不破坏 IO
        if (cachedEutDiscount < 1.0D) {
            recipe = applyEutDiscount(recipe, cachedEutDiscount);
        }
        return recipe;
    }

    @Override
    public int getMaxProgress() {
        int base = super.getMaxProgress();
        if (base <= 0 || cachedDurationMod >= 1.0D) {
            return base;
        }
        return Math.max(1, (int) Math.ceil(base * cachedDurationMod));
    }

    @Override
    protected boolean handleRecipeIO(GTRecipe recipe, IO io) {
        boolean handled = super.handleRecipeIO(recipe, io);
        if (handled && io == IO.IN) {
            GTRecipe reportRecipe = originalRecipeForReport != null ? originalRecipeForReport : recipe;
            module.reportRecipeStartedToWorkshop(reportRecipe);
        } else if (!handled && io == IO.IN) {
            module.setWorkshopRecipeMatched(originalRecipeForReport != null ? originalRecipeForReport : recipe,
                    "输入/无线EU不足或能力不匹配");
        } else if (!handled && io == IO.OUT) {
            module.setWorkshopRecipeMatched(originalRecipeForReport != null ? originalRecipeForReport : recipe,
                    "输出能力不足或被阻塞");
        }
        return handled;
    }

    private static GTRecipe applyEutDiscount(GTRecipe recipe, double discount) {
        if (discount >= 1.0D || discount <= 0.0D) {
            return recipe;
        }
        GTRecipe copy = recipe.copy();
        scaleEUCapability(copy.inputs, discount);
        scaleEUCapability(copy.tickInputs, discount);
        return copy;
    }

    private static void scaleEUCapability(Map<?, List<Content>> contentsMap, double multiplier) {
        if (contentsMap == null) {
            return;
        }
        List<Content> contents = contentsMap.get(EURecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) {
            return;
        }
        for (Content content : contents) {
            if (content == null || !(content.getContent() instanceof Number number)) {
                continue;
            }
            long eut = number.longValue();
            if (eut <= 0L) {
                continue;
            }
            content.content = Math.max(1L, (long) Math.ceil(eut * multiplier));
        }
    }

    private GTRecipe findActiveWorkshopRecipe() {
        GTRecipeType type = module.getRecipeType();
        if (type == null) {
            module.setWorkshopRecipeStatus("未注册配方类型");
            return null;
        }
        GTRecipe recipe = findWorkshopRecipe(type);
        if (recipe != null) {
            return recipe;
        }
        module.setWorkshopRecipeStatus("未找到匹配配方: " + module.getWorkshopActiveRecipeTypeName());
        return null;
    }

    private GTRecipe findWorkshopRecipe(GTRecipeType type) {
        if (type == null) {
            return null;
        }
        return type.getLookup().find(module, this::checkRecipe);
    }

    private boolean isActiveRecipeType(GTRecipeType type) {
        GTRecipeType active = module.getRecipeType();
        return active == type || (active != null && type != null
                && active.registryName != null
                && active.registryName.equals(type.registryName));
    }

    private static boolean isSyntheticWirelessRecipe(GTRecipe recipe) {
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) {
            return false;
        }
        return "gtceu:dummy".equals(recipe.recipeType.registryName.toString());
    }

    public double getCachedEutDiscount() {
        return cachedEutDiscount;
    }

    private void clearWorkshopRecipeCache() {
        cachedDurationMod = 1.0D;
        cachedEutDiscount = 1.0D;
        originalRecipeForReport = null;
    }
}
