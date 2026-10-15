package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.NativeVirtualSetupState;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 虚拟执行链启动宿主拥有、但当前没选中的配方类型时，
 * 这一轮 {@code getRecipeType()} 回报该类型。持久化的选中下标不动。
 */
@Mixin(value = WorkableMultiblockMachine.class, remap = false)
public class VirtualOwnedRecipeTypeMixin {

    @Inject(method = "getRecipeType", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$reportOwnedInactiveType(CallbackInfoReturnable<GTRecipeType> cir) {
        GTRecipeType override = NativeVirtualSetupState.ownedInactiveType();
        if (override == null) return;
        GTRecipeType[] types = ((WorkableMultiblockMachine) (Object) this).getRecipeTypes();
        if (types == null) return;
        for (int i = 0; i < types.length; i++) {
            GTRecipeType type = types[i];
            if (type == override || (type != null && override.registryName != null
                    && override.registryName.equals(type.registryName))) {
                cir.setReturnValue(type);
                return;
            }
        }
    }
}
