package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.me.items.PatternEncodingTermScreen;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.client.gui.components.AbstractWidget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = PatternEncodingTermScreen.class, priority = 100)
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

    @Unique
    private static boolean gtShanhai$missingButtonLogged;

    @Unique
    private static boolean gtShanhai$moveLogged;

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void gtShanhai$moveUndoButtonAfterEaep(CallbackInfo ci) {
        AbstractWidget eaepButton = gtShanhai$getButton(this, "eap$uploadBtn", 0);
        AbstractWidget quickUploadButton = gtShanhai$getButton(this, "gtlcore$quickUploadButton", 1);
        AbstractWidget undoButton = gtShanhai$getButton(this, "gtlcore$quickUploadUndoButton", 2);
        if (eaepButton == null || quickUploadButton == null || undoButton == null) {
            if (!gtShanhai$missingButtonLogged && !gtShanhai$reflectionUnavailable) {
                gtShanhai$missingButtonLogged = true;
                GTDishanhaiMod.LOGGER.warn(
                        "[EAEP布局] 编码终端按钮未就绪: eaep={}, upload={}, undo={}",
                        eaepButton != null,
                        quickUploadButton != null,
                        undoButton != null);
            }
            return;
        }
        int oldX = undoButton.getX();
        int oldY = undoButton.getY();
        int targetX = quickUploadButton.getX();
        int targetY = quickUploadButton.getY() + quickUploadButton.getHeight() + 2;
        if (!gtShanhai$setUndoHitPosition(this, targetX, targetY)) {
            return;
        }
        undoButton.setX(targetX);
        undoButton.setY(targetY);
        if (!gtShanhai$moveLogged) {
            gtShanhai$moveLogged = true;
            GTDishanhaiMod.LOGGER.info(
                    "[EAEP布局] 已移动 GTLCore 回收按钮: ({}, {}) -> ({}, {}), EAEP=({}, {}, {}x{}), 上传=({}, {}, {}x{})",
                    oldX,
                    oldY,
                    targetX,
                    targetY,
                    eaepButton.getX(),
                    eaepButton.getY(),
                    eaepButton.getWidth(),
                    eaepButton.getHeight(),
                    quickUploadButton.getX(),
                    quickUploadButton.getY(),
                    quickUploadButton.getWidth(),
                    quickUploadButton.getHeight());
        }
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
