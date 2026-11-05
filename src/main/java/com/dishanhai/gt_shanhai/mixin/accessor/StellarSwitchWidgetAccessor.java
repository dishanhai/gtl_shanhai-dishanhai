package com.dishanhai.gt_shanhai.mixin.accessor;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 山海：唯讀取得 LDLib 開關的兩態底圖，星律換膚時保留原開關與回調。 */
@Mixin(value = SwitchWidget.class, remap = false)
public interface StellarSwitchWidgetAccessor {
    @Accessor("baseTexture")
    IGuiTexture gtShanhai$getBaseTexture();

    @Accessor("pressedTexture")
    IGuiTexture gtShanhai$getPressedTexture();
}
