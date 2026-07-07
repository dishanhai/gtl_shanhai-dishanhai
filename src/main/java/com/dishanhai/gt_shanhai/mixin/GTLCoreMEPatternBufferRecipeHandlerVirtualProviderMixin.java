package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotAccess;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.world.item.crafting.Ingredient;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.lang.reflect.Method;

@Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase$MEItemInputHandlerBase", remap = false)
public abstract class GTLCoreMEPatternBufferRecipeHandlerVirtualProviderMixin {

    @Shadow
    public abstract MEPatternBufferPartMachineBase getMachine();

    @Inject(method = "meHandleRecipeInner", at = @At("RETURN"), remap = false)
    private void gtShanhai$stripVirtualTargetsAfterRecipe(GTRecipe recipe, Object2LongMap<Ingredient> left,
            boolean simulate, int trySlot, CallbackInfoReturnable<Boolean> cir) {
        if (simulate || !Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        Object slot = gtShanhai$getInternalSlotOrNull(trySlot);
        if (slot instanceof VirtualPatternBufferSlotAccess access) {
            access.gtShanhai$stripVirtualTargets();
        }
    }

    private Object gtShanhai$getInternalSlotOrNull(int trySlot) {
        try {
            MEPatternBufferPartMachineBase machine = getMachine();
            Method method = MEPatternBufferPartMachineBase.class.getDeclaredMethod("getInternalSlotOrNull", int.class);
            method.setAccessible(true);
            return method.invoke(machine, trySlot);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
