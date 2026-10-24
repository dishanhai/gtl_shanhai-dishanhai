package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal;
import com.glodblock.github.extendedae.util.FCUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ExtendedAE 扩展样板终端（含山海无线样板管理终端、无线扩展样板管理终端）只对
 * {@code EncodedPatternItem} 建搜索数据，星律通配样板母槽没有 in/out，搜什么都搜不到。
 * 母槽命中就按它包含的配方取产物名/原料名，沿用终端自己的分词比较。
 */
@Mixin(value = GuiExPatternTerminal.class, remap = false)
public abstract class ExPatternTerminalBundleSearchMixin {

    @Shadow
    private Set<ItemStack> matchedStack;

    @Unique
    private final Map<Integer, SelectedRecipePattern.SearchNames> gtShanhai$bundleNames = new HashMap<>();

    @Inject(method = "itemStackMatchesSearchTerm", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$matchBundle(ItemStack stack, List<String> searchTokens, boolean output,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (!SelectedRecipePattern.isPattern(stack)) return;
        SelectedRecipePattern.SearchNames names = gtShanhai$bundleNames(stack);
        for (String name : output ? names.outputs() : names.inputs()) {
            if (FCUtil.compareTokens(searchTokens, FCUtil.tokenize(name))) {
                matchedStack.add(stack);
                cir.setReturnValue(true);
                return;
            }
        }
        cir.setReturnValue(false);
    }

    @Unique
    private SelectedRecipePattern.SearchNames gtShanhai$bundleNames(ItemStack stack) {
        int key = stack.getItem().hashCode() * 31 + (stack.hasTag() ? stack.getTag().hashCode() : 0);
        SelectedRecipePattern.SearchNames cached = gtShanhai$bundleNames.get(key);
        if (cached != null) return cached;
        SelectedRecipePattern.SearchNames names = SelectedRecipePattern.searchNames(stack, Minecraft.getInstance().level);
        if (!names.outputs().isEmpty()) gtShanhai$bundleNames.put(key, names);
        return names;
    }
}
