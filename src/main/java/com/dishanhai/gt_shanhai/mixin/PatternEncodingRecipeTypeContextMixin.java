package com.dishanhai.gt_shanhai.mixin;

import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps GTLCore's JEI/REI recipe-type selection available during the later AE encoding click.
 *
 * <p>priority 必须高于 GTLCore PatternEncodingTermMenuMixin(900)：注入目标 gTLCore$setQuickUploadRecipeType(FromClient)
 * 是该 mixin 用 @Unique 新增的方法，且 encode() 被其 @Overwrite——山海必须在其之后应用，否则注入静默失效。
 */
@Mixin(value = PatternEncodingTermMenu.class, priority = 1500, remap = false)
public class PatternEncodingRecipeTypeContextMixin {

    @Unique
    private String gtShanhai$encodingRecipeTypeId = "";

    @Unique
    private boolean gtShanhai$pushedEncodingRecipeType;

    @Inject(method = "gTLCore$setQuickUploadRecipeType", at = @At("HEAD"), require = 0, remap = false)
    private void gtShanhai$captureClientRecipeType(ResourceLocation recipeTypeId, CallbackInfo ci) {
        gtShanhai$encodingRecipeTypeId = recipeTypeId == null ? "" : recipeTypeId.toString();
    }

    @Inject(method = "gTLCore$setQuickUploadRecipeTypeFromClient", at = @At("HEAD"), require = 0, remap = false)
    private void gtShanhai$captureServerRecipeType(String recipeTypeId, CallbackInfo ci) {
        ResourceLocation parsed = ResourceLocation.tryParse(recipeTypeId == null ? "" : recipeTypeId);
        gtShanhai$encodingRecipeTypeId = parsed == null ? "" : parsed.toString();
    }

    @Inject(method = "encodeProcessingPattern", at = @At("HEAD"), require = 0, remap = false)
    private void gtShanhai$pushRecipeTypeForEncoding(CallbackInfoReturnable<ItemStack> cir) {
        if (gtShanhai$encodingRecipeTypeId.isEmpty()) return;
        PatternRecipeTypeHelper.pushEncodingRecipeType(gtShanhai$encodingRecipeTypeId);
        gtShanhai$pushedEncodingRecipeType = true;
    }

    @Inject(method = "encodeProcessingPattern", at = @At("RETURN"), require = 0, remap = false)
    private void gtShanhai$popRecipeTypeAfterEncoding(CallbackInfoReturnable<ItemStack> cir) {
        if (!gtShanhai$pushedEncodingRecipeType) return;
        PatternRecipeTypeHelper.popEncodingRecipeType();
        gtShanhai$pushedEncodingRecipeType = false;
    }

    @Inject(method = "encode", at = @At("RETURN"), require = 0, remap = false)
    private void gtShanhai$clearRecipeTypeAfterMenuEncode(CallbackInfo ci) {
        gtShanhai$encodingRecipeTypeId = "";
    }
}
