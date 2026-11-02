package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.wave.GravitationalWaveBroadcastManager;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 山海：广播只修改当前机器的配方副本。回响的接收端仅增产／无损加速，
 * 不承担源自身的消耗倍率；GTLCore改写setupRecipe后再注入，禁止污染注册表或重试倍乘。
 */
@Mixin(value = RecipeLogic.class, priority = 1500, remap = false)
public abstract class BroadcastEffectBuildRecipeMixin {
    @Unique private GTRecipe gtShanhai$broadcastBase;
    @Unique private GTRecipe gtShanhai$broadcastRecipe;

    @Unique
    private MetaMachine gtShanhai$getMachine() {
        return ((MachineTrait) (Object) this).getMachine();
    }

    @ModifyVariable(method = "setupRecipe", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private GTRecipe gtShanhai$multiplyWithModifier(GTRecipe recipe) {
        if (recipe == null) return null;
        // 原版在连续配方时可能再次传入lastRecipe。始终回到上一次增幅前的输入副本。
        GTRecipe base = recipe == gtShanhai$broadcastRecipe ? gtShanhai$broadcastBase : recipe;
        gtShanhai$broadcastBase = base;
        gtShanhai$broadcastRecipe = null;
        // 任意回响广播配方均禁止接收广播，避免多个源互相改变广播时长／成本。
        if (base.recipeType != null && base.recipeType.registryName != null
                && "voice_of_the_faceless".equals(base.recipeType.registryName.getPath())) return base;
        MetaMachine machine = gtShanhai$getMachine();
        if (machine == null || !(machine.getLevel() instanceof ServerLevel level)) return base;

        int multiplier = GravitationalWaveBroadcastManager.INSTANCE.getFixedOutputMultiplier(level, machine.getPos());
        int echoPower = GravitationalWaveBroadcastManager.INSTANCE.getEchoPowerLevel(level, machine.getPos());
        if (multiplier <= 1) {
            int power = GravitationalWaveBroadcastManager.INSTANCE.getPowerLevel(level, machine.getPos());
            int lensCount = GravitationalWaveBroadcastManager.INSTANCE.getLensCount(level, machine.getPos());
            if (lensCount > 0) {
                float chance3x = Math.min(1.0f, lensCount * (1.0f / 16));
                multiplier = level.random.nextFloat() < chance3x ? 3 : 2;
            } else {
                multiplier = power > 0 && level.random.nextDouble() < Math.min(0.5, power * 0.005) ? 2 : 1;
            }
        }
        if (multiplier <= 1 && echoPower <= 0) return base;
        gtShanhai$broadcastRecipe = gtShanhai$applyReceiverEffects(base, multiplier, echoPower);
        return gtShanhai$broadcastRecipe;
    }

    /** 输入和tick输入保持原量；最多50%加速不等于源自身的耗时÷5。 */
    @Unique
    private static GTRecipe gtShanhai$applyReceiverEffects(GTRecipe recipe, int multiplier, int power) {
        GTRecipe copy = recipe.copy();
        // GTRecipe.copy()不复制无线耗电字段，恢复该原生子类型以免接收端丢失能源消耗。
        if (recipe instanceof IWirelessGTRecipe wireless)
            copy = new WirelessGTRecipe(copy, wireless.getWirelessEuTickInputs());
        copy.data = recipe.data.copy();
        copy.parallels = recipe.parallels;
        copy.ocTier = recipe.ocTier;
        if (recipe instanceof IGTRecipe source && copy instanceof IGTRecipe target) {
            target.setRealParallels(source.getRealParallels());
            target.setBatchSize(source.getBatchSize());
            target.setBatchProcessed(source.isBatchProcessed());
            target.setSubTickParallelized(source.isSubTickParallelized());
        }
        if (multiplier > 1) {
            ContentModifier modifier = ContentModifier.multiplier(multiplier);
            modifyContents(copy.outputs, modifier);
            modifyContents(copy.tickOutputs, modifier);
        }
        int safePower = Math.max(0, Math.min(100, power));
        if (safePower > 0) copy.duration = Math.max(1, (int) Math.ceil(recipe.duration / (1.0 + safePower * .005)));
        return copy;
    }

    @Unique
    private static void modifyContents(java.util.Map<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>, java.util.List<Content>> map, ContentModifier modifier) {
        if (map == null) return;
        for (var entry : map.entrySet()) {
            var cap = entry.getKey();
            var list = entry.getValue();
            if (list == null || list.isEmpty()) continue;
            // copy()提供可变map；重建内容列表，兼容原配方的不可变集合。
            java.util.List<Content> replaced = new java.util.ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                replaced.add(list.get(i).copy(cap, modifier));
            }
            entry.setValue(replaced);
        }
    }
}
