package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.wave.GravitationalWaveBroadcastManager;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 setupRecipe 中用 ContentModifier 翻倍产出 — 与终焉矩阵 ×15 同一机制
 * ContentModifier 正确处理 ItemStack / LongIngredient / FluidStack 等全部类型
 */
@Mixin(value = RecipeLogic.class, remap = false)
public abstract class BroadcastEffectBuildRecipeMixin {

    @Unique
    private MetaMachine gtShanhai$getMachine() {
        return ((MachineTrait) (Object) this).getMachine();
    }

    @Inject(method = "setupRecipe", at = @At("HEAD"))
    private void gtShanhai$multiplyWithModifier(GTRecipe recipe, CallbackInfo ci) {
        MetaMachine machine = gtShanhai$getMachine();
        if (machine == null || !(machine.getLevel() instanceof ServerLevel level)) return;
        if (recipe == null) return;

        int multiplier = GravitationalWaveBroadcastManager.INSTANCE.getFixedOutputMultiplier(level, machine.getPos());
        if (multiplier <= 1) {
            int power = GravitationalWaveBroadcastManager.INSTANCE.getPowerLevel(level, machine.getPos());
            int lensCount = GravitationalWaveBroadcastManager.INSTANCE.getLensCount(level, machine.getPos());
            if (lensCount <= 0 && power <= 0) return;

            if (lensCount > 0) {
                float chance3x = Math.min(1.0f, lensCount * (1.0f / 16));
                multiplier = level.random.nextFloat() < chance3x ? 3 : 2;
            } else {
                if (level.random.nextDouble() >= Math.min(0.5, power * 0.005)) return;
                multiplier = 2;
            }
        }

        ContentModifier modifier = ContentModifier.multiplier(multiplier);
        modifyContents(recipe.outputs, modifier);
        modifyContents(recipe.tickOutputs, modifier);
    }

    @Unique
    private static void modifyContents(java.util.Map<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>, java.util.List<Content>> map, ContentModifier modifier) {
        if (map == null) return;
        for (var entry : map.entrySet()) {
            var cap = entry.getKey();
            var list = entry.getValue();
            if (list == null || list.isEmpty()) continue;
            // 部分配方(如虚拟样板配方)的输出列表是 ImmutableList,直接 set 会抛
            // UnsupportedOperationException。改为构造新的可变列表整体替换,兼容不可变列表。
            java.util.List<Content> replaced = new java.util.ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                replaced.add(list.get(i).copy(cap, modifier));
            }
            try {
                entry.setValue(replaced);
            } catch (UnsupportedOperationException immutableMap) {
                // 极少数外层 map 也不可变的情况:退回逐个 set(内层若可变仍可翻倍),
                // 再不行则跳过该 cap,保证引力波不因不可变结构崩溃(不崩优先于翻倍)。
                try {
                    for (int i = 0; i < replaced.size(); i++) {
                        list.set(i, replaced.get(i));
                    }
                } catch (UnsupportedOperationException immutableList) {
                    // 内外皆不可变,无法翻倍此 cap,跳过。
                }
            }
        }
    }
}
