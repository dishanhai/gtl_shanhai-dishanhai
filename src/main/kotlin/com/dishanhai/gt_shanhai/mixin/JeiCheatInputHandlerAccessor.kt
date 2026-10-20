package com.dishanhai.gt_shanhai.mixin

import mezz.jei.api.runtime.IIngredientManager
import mezz.jei.gui.input.CombinedRecipeFocusSource
import mezz.jei.gui.input.handlers.CheatInputHandler
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * 读取 JEI [CheatInputHandler] 的聚焦源与 ingredient manager。
 *
 * 用 Accessor 而不是 `@Shadow`：Kotlin 没有 refmap，`@Shadow` 的字段注解需要显式
 * `@field:` 才落到字节码上，容易出现「字段被合并成私有不存在成员」的隐性失败。
 */
@Mixin(value = [CheatInputHandler::class], remap = false)
interface JeiCheatInputHandlerAccessor {

    @Accessor("focusSource", remap = false)
    fun gtShanhaiFocusSource(): CombinedRecipeFocusSource

    @Accessor("ingredientManager", remap = false)
    fun gtShanhaiIngredientManager(): IIngredientManager
}
