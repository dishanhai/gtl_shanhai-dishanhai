package com.dishanhai.gt_shanhai.mixin;

import appeng.client.Point;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.WidgetStyle;
import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.gui.PatternEncodingIconButton;
import com.dishanhai.gt_shanhai.client.gui.PatternEncodingIconButton.Appearance;
import com.dishanhai.gt_shanhai.common.ae2.CloudPatternUploadMenu;
import com.dishanhai.sh_better_ui.client.gui.display.GraphicIcon;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = PatternEncodingTermScreen.class, priority = 100)
public abstract class EaepGtlCoreUploadButtonLayoutMixin extends MEStorageScreen<PatternEncodingTermMenu> {

    @Unique
    private static final GraphicIcon gtShanhai$compactCloudIcon = new GraphicIcon(
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "textures/gui/cloud_upload_compact.png"));

    @Unique
    private PatternEncodingIconButton gtShanhai$cloudUploadButton;

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
        gtShanhai$placeCloudUploadButton();
        AbstractWidget quickUploadButton = gtShanhai$getButton(this, "gtlcore$quickUploadButton", 1);
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
    private void gtShanhai$placeCloudUploadButton() {
        if (!(this.menu instanceof CloudPatternUploadMenu cloudMenu)) {
            return;
        }
        if (gtShanhai$cloudUploadButton == null) {
            Component label = Component.translatable("gt_shanhai.cloud.ui.upload_from_terminal");
            Appearance appearance = new Appearance(gtShanhai$compactCloudIcon, 0xFF10151D, label);
            gtShanhai$cloudUploadButton = new PatternEncodingIconButton(() -> appearance, 0x73D4FA,
                    button -> cloudMenu.gtShanhai$requestCloudUpload(""));
        }
        if (!this.renderables.contains(gtShanhai$cloudUploadButton)) {
            addRenderableWidget(gtShanhai$cloudUploadButton);
        }
        WidgetStyle encode = getStyle().getWidget("encodePattern");
        Point anchor = encode.resolve(new Rect2i(this.leftPos, this.topPos, this.imageWidth, this.imageHeight));
        int encodeWidth = encode.getWidth() > 0 ? encode.getWidth() : 16;
        // 使用编码区右侧的空位，避开编码槽和 GTLCore 上传、回收控件。
        gtShanhai$cloudUploadButton.setX(anchor.getX() + encodeWidth + 2);
        gtShanhai$cloudUploadButton.setY(anchor.getY() - gtShanhai$cloudUploadButton.getHeight() - 6);
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
