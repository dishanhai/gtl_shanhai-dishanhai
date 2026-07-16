package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.util.HubMachineHelper;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.chance.boost.ChanceBoostFunction;
import com.gregtechceu.gtceu.api.recipe.content.Content;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.api.recipe.chance.LongChanceLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 在 GTL 并行概率折算之前应用终焉聚合枢纽的产出概率绕过。 */
@Mixin(value = IParallelLogic.class, remap = false)
public interface ParallelOutputChanceBypassMixin {

    /**
     * @author 山海恒长在
     * @reason 接口静态方法无法使用普通注入处理器，覆盖原算法并在入口应用枢纽概率绕过。
     */
    @Overwrite(remap = false)
    public static GTRecipe getRecipeOutputChance(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        if (DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()
                && holder instanceof IMultiController controller
                && HubMachineHelper.hasChanceBypass(controller)) {
            recipe = HubMachineHelper.forceFullOutputChance(recipe);
        }

        Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Content>> recipeContents =
                new Reference2ObjectOpenHashMap<>();
        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : recipe.outputs.entrySet()) {
            RecipeCapability<?> capability = entry.getKey();
            List<Content> chancedContents = new ObjectArrayList<>();
            List<Content> contentList = recipeContents.get(capability);
            if (contentList == null) {
                contentList = new ObjectArrayList<>();
                recipeContents.put(capability, contentList);
            }
            for (Content content : entry.getValue()) {
                if (content.chance >= content.maxChance) {
                    contentList.add(content);
                } else {
                    chancedContents.add(content);
                }
            }
            if (!chancedContents.isEmpty()) {
                ChanceBoostFunction function = recipe.getType().getChanceFunction();
                int holderTier = holder.getChanceTier();
                Object2IntMap<?> cache = ((IRecipeLogicMachine) holder).getRecipeLogic()
                        .getChanceCaches().get(capability);
                List<Content> rolledContents = LongChanceLogic.OR.roll(
                        chancedContents,
                        function,
                        ((IGTRecipe) recipe).getEuTier(),
                        holderTier,
                        cache,
                        ((IGTRecipe) recipe).getRealParallels(),
                        capability);
                if (rolledContents != null) {
                    Iterator<Content> iterator = rolledContents.iterator();
                    while (iterator.hasNext()) {
                        contentList.add(new Content(iterator.next().content, 10000, 10000, 0, null, null));
                    }
                }
            }
            if (contentList.isEmpty()) {
                recipeContents.remove(capability);
            }
        }

        GTRecipe result = new GTRecipe(
                recipe.recipeType,
                recipe.id,
                recipe.inputs,
                recipeContents,
                recipe.tickInputs,
                recipe.tickOutputs,
                recipe.inputChanceLogics,
                recipe.outputChanceLogics,
                recipe.tickInputChanceLogics,
                recipe.tickOutputChanceLogics,
                recipe.conditions,
                recipe.ingredientActions,
                recipe.data,
                recipe.duration,
                recipe.isFuel);
        ((IGTRecipe) (Object) result).setRealParallels(((IGTRecipe) recipe).getRealParallels());
        result.ocTier = recipe.ocTier;
        return result;
    }
}
