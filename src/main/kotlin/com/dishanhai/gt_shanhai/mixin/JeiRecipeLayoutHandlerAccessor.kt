package com.dishanhai.gt_shanhai.mixin

import mezz.jei.api.gui.IRecipeLayoutDrawable
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * 读取 JEI 配方槽输入处理器持有的 `recipeLayout`。
 *
 * 目标是 `RecipeLayoutWithButtons` 的私有静态内部类（record），
 * 用 targets 字符串指定，配置里写成 `RecipeLayoutWithButtons$RecipeLayoutUserInputHandler`。
 */
@Mixin(targets = ["mezz.jei.gui.recipes.RecipeLayoutWithButtons\$RecipeLayoutUserInputHandler"], remap = false)
interface JeiRecipeLayoutHandlerAccessor {

    @Accessor("recipeLayout", remap = false)
    fun gtShanhaiRecipeLayout(): IRecipeLayoutDrawable<*>
}
