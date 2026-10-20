package com.dishanhai.gt_shanhai.mixin

import com.dishanhai.gt_shanhai.client.JeiShortcutInputInterceptor
import mezz.jei.common.input.IInternalKeyMappings
import mezz.jei.gui.input.IUserInputHandler
import mezz.jei.gui.input.UserInput
import net.minecraft.client.gui.screens.Screen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.Optional

/**
 * JEI 配方界面（RecipesGui）里 Ctrl+Shift+左键取无限盘。
 *
 * RecipesGui 使用自己独立的输入路由，不含全局 `CheatInputHandler`，必须单独注入配方槽输入处理器；
 * 配方槽 ingredient 也不允许作弊取物（`ClickableIngredientInternal#allowsCheating == false`），
 * 走不了原版 cheat 通道。
 *
 * 目标是 `RecipeLayoutWithButtons` 的私有 record 内部类，靠 [JeiRecipeLayoutHandlerAccessor] 读 `recipeLayout`。
 * 实现见 [JeiShortcutInputInterceptor]。
 */
@Mixin(targets = ["mezz.jei.gui.recipes.RecipeLayoutWithButtons\$RecipeLayoutUserInputHandler"], remap = false)
open class JEIRecipeGuiInfinityCellMixin {

    @Inject(method = ["handleUserInput"], at = [At("HEAD")], cancellable = true, remap = false)
    open fun gtShanhai_handleInfinityCellGive(
        screen: Screen,
        input: UserInput,
        keyMappings: IInternalKeyMappings,
        cir: CallbackInfoReturnable<Optional<IUserInputHandler>>
    ) {
        val handled = JeiShortcutInputInterceptor.handleRecipeSlotInput(
            input,
            (this as JeiRecipeLayoutHandlerAccessor).gtShanhaiRecipeLayout()
        )
        if (handled != null) cir.setReturnValue(handled)
    }
}
