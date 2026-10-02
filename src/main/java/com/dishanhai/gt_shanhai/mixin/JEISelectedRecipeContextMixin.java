package com.dishanhai.gt_shanhai.mixin;

import appeng.integration.modules.jei.transfer.EncodePatternTransferHandler;
import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.common.item.PatternWrapControlMenu;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 将 JEI 当前点击的 GTRecipe 传给服务端样板终端。
 *
 * <p>GTLCore 原本只同步 recipe type；同一类型存在多个共享不消耗模具的配方时，
 * 后续编码只能再次猜测。配方 ID 通过 AE 菜单动作同步，真正编码时仍由服务端验证
 * 当前输入/输出，避免用户手动改槽后继续沿用旧选择。</p>
 */
@Pseudo
@Mixin(targets = "appeng.integration.modules.jei.transfer.EncodePatternTransferHandler", remap = false)
public class JEISelectedRecipeContextMixin {

    @Inject(method = "transferRecipe(Lappeng/menu/me/items/PatternEncodingTermMenu;Ljava/lang/Object;"
            + "Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)"
            + "Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
            at = @At("HEAD"), require = 0, remap = false)
    private void gtShanhai$rememberSelectedRecipe(PatternEncodingTermMenu menu, Object recipeBase,
            IRecipeSlotsView recipeSlots, net.minecraft.world.entity.player.Player player,
            boolean maxTransfer, boolean doTransfer,
            CallbackInfoReturnable<IRecipeTransferError> cir) {
        if (!doTransfer || !(menu instanceof PatternWrapControlMenu control)) {
            return;
        }
        control.gtShanhai$rememberSelectedRecipe("");
        GTRecipe recipe = gtShanhai$extractRecipe(recipeBase);
        if (recipe == null) return;
        if (recipe.id == null) return;
        control.gtShanhai$rememberSelectedRecipe(recipe.id.toString());
    }

    @Inject(method = "transferRecipe(Lappeng/menu/me/items/PatternEncodingTermMenu;Ljava/lang/Object;"
            + "Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)"
            + "Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
            at = @At("RETURN"), require = 0, remap = false)
    private void gtShanhai$clearSelectedRecipeAfterFailedTransfer(PatternEncodingTermMenu menu,
            Object recipeBase, IRecipeSlotsView recipeSlots, net.minecraft.world.entity.player.Player player,
            boolean maxTransfer, boolean doTransfer,
            CallbackInfoReturnable<IRecipeTransferError> cir) {
        if (!doTransfer || cir.getReturnValue() == null || !(menu instanceof PatternWrapControlMenu control)) {
            return;
        }
        control.gtShanhai$rememberSelectedRecipe("");
    }

    private static GTRecipe gtShanhai$extractRecipe(Object recipeBase) {
        if (recipeBase instanceof GTRecipe recipe) {
            return recipe;
        }
        if (recipeBase instanceof GTRecipeWrapper wrapper) {
            return wrapper.recipe;
        }
        return null;
    }
}
