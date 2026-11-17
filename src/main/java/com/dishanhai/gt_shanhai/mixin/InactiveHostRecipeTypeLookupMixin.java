package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSearchHelper;
import com.dishanhai.gt_shanhai.common.item.RecipeTypeSharedSearchSets;
import com.dishanhai.gt_shanhai.common.machine.part.ProgrammableHatchPartMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup;
import com.gregtechceu.gtceu.api.recipe.lookup.RecipeIterator;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;
import org.gtlcore.gtlcore.api.recipe.IAdditionalRecipeIterator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * GTLCore 改写 getRecipeIterator 后，只把样板缓存里
 * {@code recipe.recipeType == 当前查找类型} 的配方并进候选。
 * 单配方切换机器的查找类型就是当前选中项，未选中的类型（切割机模式下的车床）会被丢掉。
 * 合金冶炼炉等 MultipleRecipesLogic 宿主还绕过了 RecipeLogic 上的星律搜索入口，
 * 因此必须先解析 AE 已下单的样板，不能只等当前模式命中后才有的配方缓存。
 * priority 高于 GTLCore 的 @Overwrite，注入落在改写后的方法上。
 */
@Mixin(value = GTRecipeLookup.class, priority = 1500, remap = false)
public class InactiveHostRecipeTypeLookupMixin {

    @Shadow(remap = false)
    @Final
    private GTRecipeType recipeType;

    @Inject(method = "getRecipeIterator", at = @At("RETURN"), remap = false)
    private void gtShanhai$appendInactiveHostRecipeTypes(IRecipeCapabilityHolder holder,
            Predicate<GTRecipe> canHandle, CallbackInfoReturnable<RecipeIterator> cir) {
        if (!(cir.getReturnValue() instanceof IAdditionalRecipeIterator additional)) return;
        if (!(holder instanceof IRecipeLogicMachine machine)) return;
        if (machine instanceof SelectableRecipeTypeSetMachine) return;
        if (!(holder instanceof IRecipeCapabilityMachine capabilityMachine)) return;

        List<MEPatternRecipeHandlePart> parts = capabilityMachine.getMEPatternRecipeHandleParts();
        if (parts == null || parts.isEmpty()) return;

        // 只解析真实激活槽，不主动下单或补入网络原料；复用按 tick 缓存的星律搜索。
        LinkedHashSet<GTRecipe> candidates = new LinkedHashSet<>();
        if (DShanhaiConfig.COMMON.recipeTypePatternSwitchMode.get()
                == DShanhaiConfig.ConfigValues.RecipeTypePatternSwitchMode.VIRTUAL_ACTIVE_TYPE
                && ProgrammableHatchPartMachine.getSelectedRecipeTypeFor(machine) == null) {
            candidates.addAll(RecipeTypePatternSearchHelper.collectActiveMarkedPatternRecipesForLookup(machine));
        }
        for (int i = 0; i < parts.size(); i++) {
            MEPatternRecipeHandlePart part = parts.get(i);
            if (part == null) continue;
            Set<GTRecipe> cached = part.getCachedGTRecipe();
            if (cached == null || cached.isEmpty()) continue;
            candidates.addAll(cached);
        }
        LinkedHashSet<GTRecipe> extras = new LinkedHashSet<>();
        for (GTRecipe recipe : candidates) {
            if (recipe == null || recipe.recipeType == null) continue;
            if (gtShanhai$sameRecipeType(this.recipeType, recipe.recipeType)) continue;
            if (!gtShanhai$hostAllows(machine, recipe.recipeType)) continue;
            extras.add(recipe);
        }
        if (extras.isEmpty()) return;

        List<GTRecipe> existing = additional.getAdditionalRecipes();
        LinkedHashSet<GTRecipe> merged = new LinkedHashSet<>();
        if (existing != null) merged.addAll(existing);
        merged.addAll(extras);
        // 原生 RecipeIterator 在迭代时继续执行 canHandle，保留材料、电压、温度等检查。
        additional.setAdditionalRecipes(new ArrayList<>(merged));
    }

    private static boolean gtShanhai$hostAllows(IRecipeLogicMachine machine, GTRecipeType recipeType) {
        if (DShanhaiConfig.COMMON.recipeTypePatternAllowUnsupportedHostRecipeTypes.get()) return true;
        GTRecipeType[] hostTypes = machine.getRecipeTypes();
        if (hostTypes == null) return false;
        for (int i = 0; i < hostTypes.length; i++) {
            if (gtShanhai$sameRecipeType(hostTypes[i], recipeType)) return true;
        }
        return RecipeTypeSharedSearchSets.isSharedWithAny(recipeType, hostTypes);
    }

    private static boolean gtShanhai$sameRecipeType(GTRecipeType left, GTRecipeType right) {
        if (left == right) return true;
        return left != null && right != null && left.registryName != null
                && left.registryName.equals(right.registryName);
    }
}
