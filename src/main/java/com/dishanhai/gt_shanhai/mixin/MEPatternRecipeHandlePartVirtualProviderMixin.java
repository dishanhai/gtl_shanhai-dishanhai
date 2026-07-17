package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeExecutionGuard;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferMachineAccess;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart", remap = false)
public class MEPatternRecipeHandlePartVirtualProviderMixin {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai:virtual_pattern");

    @Inject(method = "handleRecipe", at = @At("RETURN"), remap = false)
    private void gtShanhai$stripVirtualTargetsAfterPatternRecipe(GTRecipe recipe,
            Reference2ObjectMap<RecipeCapability<?>, List<Object>> contents, boolean simulate, boolean setSlotCache,
            CallbackInfoReturnable<Integer> cir) {
        if (simulate || cir.getReturnValueI() < 0 || PatternRecipeExecutionGuard.isAuxiliaryIORecipe(recipe)) {
            return;
        }
        VirtualPatternBufferMachineAccess access = gtShanhai$getPatternBufferAccess();
        if (access != null) {
            access.gtShanhai$stripVirtualTargetsInSlot(cir.getReturnValueI());
        }
    }

    private VirtualPatternBufferMachineAccess gtShanhai$getPatternBufferAccess() {
        try {
            Object meTrait = gtShanhai$getInheritedField(this, "meTrait");
            if (meTrait == null) {
                return null;
            }
            Method method = gtShanhai$findMethod(meTrait.getClass(), "getMachine");
            Object machine = method.invoke(meTrait);
            if (machine instanceof VirtualPatternBufferMachineAccess access) {
                return access;
            }
        } catch (ReflectiveOperationException e) {
            // GTLCore 内部字段/方法名变化导致反射失配：配方执行后虚拟目标(催化剂)不会被剥离，
            // 静默失败会让问题很难定位，这里至少留一条 debug 日志。
            LOG.debug("[VirtualPatternProvider] 反射获取样板总成失败", e);
        }
        return null;
    }

    private Object gtShanhai$getInheritedField(Object target, String name) throws ReflectiveOperationException {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Method gtShanhai$findMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }
}
