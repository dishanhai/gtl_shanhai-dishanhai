package com.dishanhai.gt_shanhai.mixin;

import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.parts.encoding.PatternEncodingLogic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PatternEncodingTermMenu.class, remap = false)
public interface PatternEncodingTermMenuAccessor {

    @Accessor(value = "blankPatternSlot", remap = false)
    RestrictedInputSlot gtShanhai$getBlankPatternSlot();

    @Accessor(value = "encodingLogic", remap = false)
    PatternEncodingLogic gtShanhai$getEncodingLogic();
}
