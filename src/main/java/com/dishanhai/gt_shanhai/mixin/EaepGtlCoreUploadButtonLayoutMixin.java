package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;

import com.extendedae_plus.mixin.minecraft.accessor.ScreenAccessor;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

@Mixin(value = AEBaseScreen.class, priority = 900)
public abstract class EaepGtlCoreUploadButtonLayoutMixin {

    @Unique
    private static final String GT_SHANHAI$EAEP_UPLOAD_BUTTON =
            "com.extendedae_plus.mixin.ae2.client.gui.PatternEncodingTermScreenMixin$1";

    @Unique
    private static final String GT_SHANHAI$GTL_UPLOAD_BUTTON =
            "org.gtlcore.gtlcore.mixin.ae2.gui.PatternEncodingTermScreenMixin$QuickUploadButton";

    @Unique
    private static final String GT_SHANHAI$GTL_UNDO_BUTTON =
            "org.gtlcore.gtlcore.mixin.ae2.gui.PatternEncodingTermScreenMixin$QuickUploadUndoButton";

    @Unique
    private static Field gtShanhai$undoHitXField;

    @Unique
    private static Field gtShanhai$undoHitYField;

    @Unique
    private static boolean gtShanhai$reflectionUnavailable;

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void gtShanhai$moveUndoButtonAfterGtlCore(CallbackInfo ci) {
        if (!((Object) this instanceof PatternEncodingTermScreen<?>)) {
            return;
        }
        List<GuiEventListener> children = ((ScreenAccessor) (Object) this).eap$getChildren();
        AbstractWidget eaepButton = gtShanhai$findButton(children, GT_SHANHAI$EAEP_UPLOAD_BUTTON);
        AbstractWidget quickUploadButton = gtShanhai$findButton(children, GT_SHANHAI$GTL_UPLOAD_BUTTON);
        AbstractWidget undoButton = gtShanhai$findButton(children, GT_SHANHAI$GTL_UNDO_BUTTON);
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
    private static AbstractWidget gtShanhai$findButton(List<GuiEventListener> children, String className) {
        for (GuiEventListener child : children) {
            if (child instanceof AbstractWidget widget && child.getClass().getName().equals(className)) {
                return widget;
            }
        }
        return null;
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
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            gtShanhai$reflectionUnavailable = true;
            return false;
        }
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
