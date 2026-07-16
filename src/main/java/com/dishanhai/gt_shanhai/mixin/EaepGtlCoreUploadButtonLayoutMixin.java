package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(AEBaseScreen.class)
public abstract class EaepGtlCoreUploadButtonLayoutMixin {

    @Unique
    private static Field gtShanhai$eaepUploadButtonField;

    @Unique
    private static Field gtShanhai$quickUploadButtonField;

    @Unique
    private static Field gtShanhai$undoButtonField;

    @Unique
    private static Field gtShanhai$undoHitXField;

    @Unique
    private static Field gtShanhai$undoHitYField;

    @Unique
    private static boolean gtShanhai$reflectionUnavailable;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/client/gui/AEBaseScreen;updateBeforeRender()V",
                    shift = At.Shift.AFTER,
                    remap = false))
    private void gtShanhai$moveUndoButtonAfterPositionUpdates(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!((Object) this instanceof PatternEncodingTermScreen<?>)) {
            return;
        }
        AbstractWidget eaepButton = gtShanhai$getButton(this, "eap$uploadBtn", 0);
        AbstractWidget quickUploadButton = gtShanhai$getButton(this, "gtlcore$quickUploadButton", 1);
        AbstractWidget undoButton = gtShanhai$getButton(this, "gtlcore$quickUploadUndoButton", 2);
        if (eaepButton == null || quickUploadButton == null || undoButton == null
                || !gtShanhai$overlaps(eaepButton, undoButton)) {
            return;
        }
        int targetX = quickUploadButton.getX();
        int targetY = quickUploadButton.getY() + quickUploadButton.getHeight() + 2;
        if (!gtShanhai$setUndoHitPosition(this, targetX, targetY)) {
            return;
        }
        undoButton.setX(targetX);
        undoButton.setY(targetY);
    }

    @Unique
    private static AbstractWidget gtShanhai$getButton(Object screen, String fieldName, int fieldIndex) {
        if (gtShanhai$reflectionUnavailable) {
            return null;
        }
        try {
            Field field;
            if (fieldIndex == 0) {
                if (gtShanhai$eaepUploadButtonField == null) {
                    gtShanhai$eaepUploadButtonField = gtShanhai$findField(screen.getClass(), fieldName);
                }
                field = gtShanhai$eaepUploadButtonField;
            } else if (fieldIndex == 1) {
                if (gtShanhai$quickUploadButtonField == null) {
                    gtShanhai$quickUploadButtonField = gtShanhai$findField(screen.getClass(), fieldName);
                }
                field = gtShanhai$quickUploadButtonField;
            } else {
                if (gtShanhai$undoButtonField == null) {
                    gtShanhai$undoButtonField = gtShanhai$findField(screen.getClass(), fieldName);
                }
                field = gtShanhai$undoButtonField;
            }
            Object value = field.get(screen);
            return value instanceof AbstractWidget widget ? widget : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            gtShanhai$disableReflection(fieldName, exception);
            return null;
        }
    }

    @Unique
    private static boolean gtShanhai$overlaps(AbstractWidget left, AbstractWidget right) {
        return left.getX() < right.getX() + right.getWidth()
                && left.getX() + left.getWidth() > right.getX()
                && left.getY() < right.getY() + right.getHeight()
                && left.getY() + left.getHeight() > right.getY();
    }

    @Unique
    private static boolean gtShanhai$setUndoHitPosition(Object screen, int x, int y) {
        if (gtShanhai$reflectionUnavailable) {
            return false;
        }
        try {
            if (gtShanhai$undoHitXField == null || gtShanhai$undoHitYField == null) {
                gtShanhai$undoHitXField = gtShanhai$findField(
                        screen.getClass(), "gtlcore$quickUploadUndoHitX");
                gtShanhai$undoHitYField = gtShanhai$findField(
                        screen.getClass(), "gtlcore$quickUploadUndoHitY");
            }
            gtShanhai$undoHitXField.setInt(screen, x);
            gtShanhai$undoHitYField.setInt(screen, y);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            gtShanhai$disableReflection("gtlcore$quickUploadUndoHitX/Y", exception);
            return false;
        }
    }

    @Unique
    private static void gtShanhai$disableReflection(String fieldName, Exception exception) {
        if (!gtShanhai$reflectionUnavailable) {
            GTDishanhaiMod.LOGGER.warn(
                    "[EAEP布局] 无法读取字段 {}，样板回收按钮兼容布局已停用",
                    fieldName,
                    exception);
        }
        gtShanhai$reflectionUnavailable = true;
    }

    @Unique
    private static Field gtShanhai$findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
