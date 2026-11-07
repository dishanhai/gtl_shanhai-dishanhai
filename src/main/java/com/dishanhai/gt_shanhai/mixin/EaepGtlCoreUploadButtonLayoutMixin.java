package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.Icon;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.IconButton;
import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.ae2.CloudPatternUploadMenu;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

@Mixin(value = PatternEncodingTermScreen.class, priority = 100)
public abstract class EaepGtlCoreUploadButtonLayoutMixin extends MEStorageScreen<PatternEncodingTermMenu> {

    @Unique
    private IconButton gtShanhai$cloudUploadButton;

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

    protected EaepGtlCoreUploadButtonLayoutMixin(PatternEncodingTermMenu menu, Inventory inventory,
            Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void gtShanhai$moveUndoButtonAfterEaep(CallbackInfo ci) {
        AbstractWidget quickUploadButton = gtShanhai$getButton(this, "gtlcore$quickUploadButton", 1);
        gtShanhai$placeCloudUploadButton(quickUploadButton);
        AbstractWidget eaepButton = gtShanhai$getButton(this, "eap$uploadBtn", 0);
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
    private void gtShanhai$placeCloudUploadButton(AbstractWidget quickUpload) {
        if (gtShanhai$cloudUploadButton == null) {
            gtShanhai$cloudUploadButton = new CloudUploadButton(button -> {
                if (this.menu instanceof CloudPatternUploadMenu cloudMenu) {
                    cloudMenu.gtShanhai$requestCloudUpload("");
                }
            });
        }
        if (!this.renderables.contains(gtShanhai$cloudUploadButton)) {
            addRenderableWidget(gtShanhai$cloudUploadButton);
        }
        gtShanhai$cloudUploadButton.setVisibility(quickUpload != null && quickUpload.visible);
        if (quickUpload != null) {
            gtShanhai$cloudUploadButton.setX(quickUpload.getX());
            gtShanhai$cloudUploadButton.setY(quickUpload.getY() - gtShanhai$cloudUploadButton.getHeight() - 2);
        }
    }

    @Unique
    private static final class CloudUploadButton extends IconButton {
        private CloudUploadButton(Button.OnPress onPress) {
            super(onPress);
            setHalfSize(true);
            setWidth(8);
            setHeight(8);
        }

        @Override
        public List<Component> getTooltipMessage() {
            return List.of(Component.translatable("gt_shanhai.cloud.ui.upload_from_terminal"));
        }

        @Override
        protected Icon getIcon() {
            return Icon.AUTO_EXPORT_ON;
        }
    }

    @Unique
    private static AbstractWidget gtShanhai$getButton(Object screen, String fieldName, int fieldIndex) {
        if (gtShanhai$reflectionUnavailable && (fieldIndex != 1 || gtShanhai$quickUploadButtonField == null)) {
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
