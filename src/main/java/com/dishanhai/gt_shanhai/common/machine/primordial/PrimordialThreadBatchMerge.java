package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeExtensionCopier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;

/** 山海：合并已结算概率、扣料完成的批次输出。进度、各源并行池与输出容纳由调用方检查。 */
public final class PrimordialThreadBatchMerge {
    private PrimordialThreadBatchMerge() {}

    @Nullable
    public static GTRecipe merge(GTRecipe first, GTRecipe second) {
        if (!isPaidDeterministicBatch(first) || !isPaidDeterministicBatch(second)
                || first.recipeType != second.recipeType || !Objects.equals(first.id, second.id)
                || first.duration != second.duration || first.parallels != second.parallels
                || first.ocTier != second.ocTier || first.isFuel != second.isFuel
                || ((WirelessGTRecipe) first).getWirelessEuTickInputs().signum()
                        != ((WirelessGTRecipe) second).getWirelessEuTickInputs().signum()
                || !first.inputChanceLogics.equals(second.inputChanceLogics)
                || !first.outputChanceLogics.equals(second.outputChanceLogics)
                || !first.tickInputChanceLogics.equals(second.tickInputChanceLogics)
                || !first.tickOutputChanceLogics.equals(second.tickOutputChanceLogics)
                || !sameExtensions(first, second)) return null;

        var firstSources = PrimordialThreadRecipeIdentity.sourcesOf(first);
        var secondSources = PrimordialThreadRecipeIdentity.sourcesOf(second);
        if (firstSources.isEmpty() || secondSources.isEmpty()) return null;
        var sources = new LinkedHashSet<>(firstSources);
        sources.addAll(secondSources);
        for (var source : sources) {
            if (source.recipeType().isBlank() || source.recipeId().isBlank()
                    || ResourceLocation.tryParse(source.recipeType()) == null
                    || ResourceLocation.tryParse(source.recipeId()) == null) return null;
        }

        CompoundTag firstData = first.data.copy();
        CompoundTag secondData = second.data.copy();
        firstData.remove(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY);
        secondData.remove(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY);
        firstData.remove(PrimordialThreadRecipeIdentity.KEY);
        secondData.remove(PrimordialThreadRecipeIdentity.KEY);
        if (!firstData.equals(secondData)) return null;
        long parallel;
        try {
            parallel = Math.addExact(first.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY),
                    second.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY));
        } catch (ArithmeticException overflow) {
            if (sources.size() == 1) return null;
            // 多源独立池的汇总仅用于显示，各池的精确预留保存在运行 frame 中。
            parallel = Long.MAX_VALUE;
        }

        GTRecipe copied = first.copy();
        copied.data = first.data.copy();
        copied.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, parallel);
        PrimordialThreadRecipeIdentity.store(copied, sources);
        // 保留各项 long 数量，避免同物品累计输出时溢出。
        second.copyContents(second.outputs, null).forEach((capability, contents) ->
                copied.outputs.computeIfAbsent(capability, ignored -> new ArrayList<>()).addAll(contents));
        var merged = new WirelessGTRecipe(copied,
                ((WirelessGTRecipe) first).getWirelessEuTickInputs()
                        .add(((WirelessGTRecipe) second).getWirelessEuTickInputs()));
        merged.parallels = first.parallels;
        merged.ocTier = first.ocTier;
        RecipeExtensionCopier.copy(first, merged);
        if (first instanceof IGTRecipe original && merged instanceof IGTRecipe target) {
            target.setRealParallels(original.getRealParallels());
            target.setBatchSize(original.getBatchSize());
            target.setBatchProcessed(original.isBatchProcessed());
            target.setSubTickParallelized(original.isSubTickParallelized());
            target.setHasTick(false);
        }
        return merged;
    }

    private static boolean isPaidDeterministicBatch(GTRecipe recipe) {
        if (recipe == null || recipe.getClass() != WirelessGTRecipe.class || recipe.duration <= 0
                || ((WirelessGTRecipe) recipe).getWirelessEuTickInputs() == null
                || !recipe.inputs.isEmpty() || !recipe.tickInputs.isEmpty() || !recipe.tickOutputs.isEmpty()
                || !recipe.conditions.isEmpty() || !recipe.ingredientActions.isEmpty()
                || recipe.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY) <= 0L) return false;
        for (var contents : recipe.outputs.values()) {
            for (var content : contents) {
                if (content.maxChance <= 0 || content.chance != content.maxChance || content.tierChanceBoost != 0
                        || content.content instanceof IntProviderIngredient) return false;
            }
        }
        return true;
    }

    private static boolean sameExtensions(GTRecipe first, GTRecipe second) {
        if (first instanceof IGTRecipe firstExtension && second instanceof IGTRecipe secondExtension) {
            return firstExtension.getRealParallels() == secondExtension.getRealParallels()
                    && firstExtension.getBatchSize() == secondExtension.getBatchSize()
                    && firstExtension.isBatchProcessed() == secondExtension.isBatchProcessed()
                    && firstExtension.isSubTickParallelized() == secondExtension.isSubTickParallelized()
                    && firstExtension.getEuTier() == secondExtension.getEuTier();
        }
        return true;
    }
}
