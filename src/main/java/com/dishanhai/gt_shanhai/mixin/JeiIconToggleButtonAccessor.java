package com.dishanhai.gt_shanhai.mixin;

import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.elements.GuiIconToggleButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GuiIconToggleButton.class, remap = false)
public interface JeiIconToggleButtonAccessor {

    @Accessor("area")
    ImmutableRect2i gtShanhai$getArea();
}
