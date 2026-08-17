package com.dishanhai.gt_shanhai.mixin;

import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Keeps GTLCore's JEI/REI recipe-type selection available during the later AE encoding click.
 *
 * <p>GTLCore 已将类型同步到服务端菜单的 pending 字段。直接在编码边界读取该字段，避免对
 * GTLCore Mixin 以 @Unique 新增的 setter 做二次注入时因应用顺序或重命名而静默失效。
 */
@Mixin(value = PatternEncodingTermMenu.class, priority = 800, remap = false)
public class PatternEncodingRecipeTypeContextMixin {

    @Unique
    private static Field gtShanhai$pendingRecipeTypeField;

    @Unique
    private static boolean gtShanhai$pendingRecipeTypeReadFailureLogged;

    @Unique
    private boolean gtShanhai$pushedEncodingRecipeType;

    @Inject(method = "encodeProcessingPattern", at = @At("HEAD"), require = 0, remap = false)
    private void gtShanhai$pushRecipeTypeForEncoding(CallbackInfoReturnable<ItemStack> cir) {
        String recipeTypeId = gtShanhai$readPendingRecipeTypeId(this);
        if (recipeTypeId.isEmpty()) return;
        PatternRecipeTypeHelper.pushEncodingRecipeType(recipeTypeId);
        gtShanhai$pushedEncodingRecipeType = true;
        GTDishanhaiMod.LOGGER.debug("[VirtualPatternEncoding] using GTLCore pending recipe type {}", recipeTypeId);
    }

    @Inject(method = "encodeProcessingPattern", at = @At("RETURN"), require = 0, remap = false)
    private void gtShanhai$popRecipeTypeAfterEncoding(CallbackInfoReturnable<ItemStack> cir) {
        if (!gtShanhai$pushedEncodingRecipeType) return;
        PatternRecipeTypeHelper.popEncodingRecipeType();
        gtShanhai$pushedEncodingRecipeType = false;
    }

    @Unique
    private static String gtShanhai$readPendingRecipeTypeId(Object menu) {
        if (menu == null) return "";
        try {
            Field field = gtShanhai$pendingRecipeTypeField;
            if (field == null || !field.getDeclaringClass().isInstance(menu)) {
                field = gtShanhai$findField(menu.getClass(), "gTLCore$pendingQuickUploadRecipeTypeId");
                field.setAccessible(true);
                gtShanhai$pendingRecipeTypeField = field;
            }
            Object value = field.get(menu);
            return value instanceof ResourceLocation recipeTypeId ? recipeTypeId.toString() : "";
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!gtShanhai$pendingRecipeTypeReadFailureLogged) {
                gtShanhai$pendingRecipeTypeReadFailureLogged = true;
                GTDishanhaiMod.LOGGER.warn(
                        "[VirtualPatternEncoding] 无法读取 GTLCore pending 配方类型，自动包裹将回退到全局匹配",
                        exception);
            }
            return "";
        }
    }

    @Unique
    private static Field gtShanhai$findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
