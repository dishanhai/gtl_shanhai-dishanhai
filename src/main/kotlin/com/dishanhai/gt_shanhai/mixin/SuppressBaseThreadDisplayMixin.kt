package com.dishanhai.gt_shanhai.mixin

import com.gtladd.gtladditions.api.machine.IThreadModifierMachine
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Overwrite

/**
 * 覆写 getMultipleThreads()：去掉 128 基线，使用机器的 getAdditionalThread() 值 + 1。
 * 线程值来自原初模块的线程倍率槽或 IThreadModifierPart。
 *
 * Kotlin 注意：类和方法都声明为 open，避免 Kotlin 默认 final 影响 Mixin 合并后的方法修饰符。
 */
@Mixin(GTLAddMultipleRecipesLogic::class, remap = false)
open class SuppressBaseThreadDisplayMixin {

    @Overwrite
    open fun getMultipleThreads(): Int {
        // Mixin 类的静态类型不是目标类，需要先转 Any 再向下转型
        val logic = (this as Any) as GTLAddMultipleRecipesLogic
        val machine = logic.machine as IThreadModifierMachine
        return maxOf(1, machine.getAdditionalThread())
    }
}
