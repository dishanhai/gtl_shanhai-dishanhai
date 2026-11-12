package com.dishanhai.gt_shanhai.mixin;

import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.PatternWrapControlMenu;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

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
    private static final Logger gtShanhai$encodingContextLogger = LoggerFactory.getLogger("VirtualPatternEncoding");

    @Unique
    private static Field gtShanhai$pendingRecipeTypeField;

    @Unique
    private static boolean gtShanhai$pendingRecipeTypeReadFailureLogged;

    @WrapMethod(method = "encodeProcessingPattern", require = 0, remap = false)
    private ItemStack gtShanhai$encodeWithRecipeContext(Operation<ItemStack> original) {
        PatternRecipeTypeHelper.popSelectedEncodingRecipe();
        PatternRecipeTypeHelper.popEncodingRecipeType();
        try {
            if (this instanceof PatternWrapControlMenu control
                    && ((PatternEncodingTermMenu) (Object) this).getPlayer() instanceof ServerPlayer player) {
                String selectedRecipeId = control.gtShanhai$getSelectedRecipeId();
                GTRecipe selectedRecipe = PatternRecipeTypeHelper.resolveRecipe(player.level(), selectedRecipeId);
                if (selectedRecipe != null) {
                    PatternRecipeTypeHelper.pushSelectedEncodingRecipe(selectedRecipe);
                    gtShanhai$encodingContextLogger.debug("[VirtualPatternEncoding] using JEI-selected recipe {}",
                            selectedRecipe.id);
                }
            }
            String recipeTypeId = gtShanhai$readPendingRecipeTypeId(this);
            if (!recipeTypeId.isEmpty()) {
                PatternRecipeTypeHelper.pushEncodingRecipeType(recipeTypeId);
                gtShanhai$encodingContextLogger.debug("[VirtualPatternEncoding] using GTLCore pending recipe type {}", recipeTypeId);
            }
            return original.call();
        } finally {
            PatternRecipeTypeHelper.popSelectedEncodingRecipe();
            PatternRecipeTypeHelper.popEncodingRecipeType();
        }
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
                gtShanhai$encodingContextLogger.warn(
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
