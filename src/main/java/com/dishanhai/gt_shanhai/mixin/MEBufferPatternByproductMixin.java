package com.dishanhai.gt_shanhai.mixin;

import appeng.crafting.pattern.AEProcessingPattern;

import com.dishanhai.gt_shanhai.common.ae2.PatternByproductOrder;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 樣板總成預設裁掉副產物後再掛上網路。有標識的樣板改保留全部產出。
 */
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.handler.MEBufferPatternHelper", remap = false)
public class MEBufferPatternByproductMixin {

    @ModifyVariable(method = "createPatternWithoutCircuit", at = @At("HEAD"), argsOnly = true, remap = false)
    private boolean gtShanhai$keepMarkedByproducts(boolean keepByProduct, AEProcessingPattern pattern) {
        return keepByProduct || PatternByproductOrder.isMarked(pattern);
    }

    @ModifyExpressionValue(method = "createPatternWithoutCircuit", at = @At(value = "INVOKE",
            target = "Lappeng/api/crafting/PatternDetailsHelper;encodeProcessingPattern([Lappeng/api/stacks/GenericStack;[Lappeng/api/stacks/GenericStack;)Lnet/minecraft/world/item/ItemStack;"),
            remap = false)
    private ItemStack gtShanhai$copyPublishedPatternTags(ItemStack encoded, AEProcessingPattern source) {
        if (PatternByproductOrder.isMarked(source)) PatternByproductOrder.copyDefinitionTags(source, encoded);
        return encoded;
    }

    @ModifyExpressionValue(method = "rebuildPatternCircuit", at = @At(value = "INVOKE",
            target = "Lappeng/api/crafting/PatternDetailsHelper;encodeProcessingPattern([Lappeng/api/stacks/GenericStack;[Lappeng/api/stacks/GenericStack;)Lnet/minecraft/world/item/ItemStack;"),
            remap = false)
    private ItemStack gtShanhai$copyEditedPatternTags(ItemStack encoded, ItemStack source) {
        PatternByproductOrder.copyExtraTags(source.getTag(), encoded);
        return encoded;
    }
}
