package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Avoids GTLCore preview-only pattern searches whose recursive backtracking can stall JEI startup.
 */
@Mixin(targets = "org.gtlcore.gtlcore.api.gui.PatternPreviewWidget", remap = false)
public abstract class GTLCorePatternPreviewCompatibilityMixin {

    @Redirect(method = "loadControllerFormed", at = @At(value = "INVOKE",
            target = "Lcom/gregtechceu/gtceu/api/pattern/BlockPattern;checkPatternAt(Lcom/gregtechceu/gtceu/api/pattern/MultiblockState;Z)Z"),
            remap = false)
    private boolean gtShanhai$guardExpensivePreviewSearch(BlockPattern pattern, MultiblockState state,
                                                           boolean savePredicate) {
        if (PatternPreviewSearchGuard.shouldSkip(pattern.aisleRepetitions)) {
            return false;
        }
        return pattern.checkPatternAt(state, savePredicate);
    }
}
