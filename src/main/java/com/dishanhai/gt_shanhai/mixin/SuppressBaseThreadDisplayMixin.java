package com.dishanhai.gt_shanhai.mixin;

import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic;

import com.gtladd.gtladditions.api.machine.multiblock.GTLAddWorkableElectricMultipleRecipesMachine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 覆写 getMultipleThreads()：去掉 128 基线，使用机器的 getAdditionalThread() 值 + 1。
 * 线程值来自原初模块的线程倍率槽或 IThreadModifierPart。
 */
@Mixin(value = GTLAddMultipleRecipesLogic.class, remap = false)
public class SuppressBaseThreadDisplayMixin {

    @Overwrite
    public int getMultipleThreads() {
        GTLAddWorkableElectricMultipleRecipesMachine machine = ((GTLAddMultipleRecipesLogic)(Object)this).getMachine();
        return Math.max(1, machine.getAdditionalThread());
    }
}
