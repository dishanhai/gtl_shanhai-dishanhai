package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeStackPickerBridge;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The shop picker closes whatever container is open. While it is covering the recipe
 * editor, keep that container so the input and output grid is still there on return.
 */
@Mixin(LocalPlayer.class)
public class RecipeEditorPickerHoldMixin {

    @Inject(method = "m_6915_", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$holdRecipeEditor(CallbackInfo ci) {
        if (ShanhaiRecipeStackPickerBridge.holding()) ci.cancel();
    }
}
