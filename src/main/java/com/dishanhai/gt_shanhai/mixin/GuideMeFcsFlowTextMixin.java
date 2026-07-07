package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.GuideMeFcsSupport;
import guideme.document.flow.LytFlowText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = LytFlowText.class, remap = false)
public abstract class GuideMeFcsFlowTextMixin {

    @ModifyVariable(method = "setText(Ljava/lang/String;)V", at = @At("HEAD"), ordinal = 0, argsOnly = true, remap = false)
    private String gtShanhai$cleanGuideMeFcsText(String text) {
        return GuideMeFcsSupport.prepareLayoutText(text).layoutText();
    }
}
