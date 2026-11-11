package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeTableHook;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：无样板配方仓同时跟踪数据包重载及在线编辑。 */
@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MERecipePatternBufferPartMachine",
        remap = false)
public abstract class MERecipePatternBufferTableRevisionMixin {
    @Shadow
    private void refreshPatterns() {
        throw new AssertionError();
    }

    @Unique
    private boolean gtShanhai$refreshingPatterns;

    @Unique
    private int gtShanhai$observedTableVersion;

    @WrapOperation(method = "refreshPatterns", at = @At(value = "INVOKE",
            target = "Lorg/gtlcore/gtlcore/integration/ae2/handler/MERecipePatternHelper;getReloadGeneration()J"))
    private long gtShanhai$includeEditedTableVersion(Operation<Long> original) {
        long reloadGeneration = original.call();
        gtShanhai$observedTableVersion = ShanhaiRecipeTableHook.tableVersion();
        return reloadGeneration + Integer.toUnsignedLong(gtShanhai$observedTableVersion);
    }

    @WrapMethod(method = "refreshPatterns")
    private void gtShanhai$guardRefresh(Operation<Void> original) {
        if (gtShanhai$refreshingPatterns) return;
        gtShanhai$refreshingPatterns = true;
        int previousTableVersion = gtShanhai$observedTableVersion;
        try {
            original.call();
        } catch (RuntimeException | Error failure) {
            gtShanhai$observedTableVersion = previousTableVersion;
            throw failure;
        } finally {
            gtShanhai$refreshingPatterns = false;
        }
    }

    @Inject(method = {"getActiveSlots", "getAvailablePatterns"}, at = @At("HEAD"))
    private void gtShanhai$refreshBeforeReading(CallbackInfoReturnable<?> cir) {
        if (!gtShanhai$refreshingPatterns
                && gtShanhai$observedTableVersion != ShanhaiRecipeTableHook.tableVersion()) {
            refreshPatterns();
        }
    }
}
