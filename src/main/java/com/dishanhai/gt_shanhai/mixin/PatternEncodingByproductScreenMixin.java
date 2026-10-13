package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.widgets.IconButton;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;

import com.dishanhai.gt_shanhai.common.ae2.ByproductOrderMenu;
import com.dishanhai.gt_shanhai.common.ae2.PatternByproductOrder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 样板编码终端：编码按钮左侧两颗半尺寸按钮。
 * 上：给输出槽样板切换副产物标识。下：单独上传一份带标识的样板。
 */
@Mixin(AEBaseScreen.class)
public abstract class PatternEncodingByproductScreenMixin<T extends AEBaseMenu> extends AbstractContainerScreen<T> {

    @Unique
    private static final int GT_SHANHAI$ENCODE_LEFT = 147;

    @Unique
    private static final int GT_SHANHAI$ENCODE_BOTTOM = 142;

    @Unique
    private IconButton gtShanhai$byproductMarkButton;

    @Unique
    private IconButton gtShanhai$byproductUploadButton;

    private PatternEncodingByproductScreenMixin(T menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void gtShanhai$initByproductButtons(CallbackInfo ci) {
        if (!((Object) this instanceof PatternEncodingTermScreen)) return;
        this.gtShanhai$byproductMarkButton = new MarkButton(button -> {
            if (this.menu instanceof ByproductOrderMenu actions) {
                actions.gtShanhai$toggleByproductOrder();
            }
        }, this::gtShanhai$outputMarked);
        this.gtShanhai$byproductUploadButton = new UploadButton(button -> {
            if (this.menu instanceof ByproductOrderMenu actions) {
                actions.gtShanhai$uploadByproductPattern();
            }
        });
        addRenderableWidget(this.gtShanhai$byproductMarkButton);
        addRenderableWidget(this.gtShanhai$byproductUploadButton);
        gtShanhai$placeByproductButtons();
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void gtShanhai$moveByproductButtons(CallbackInfo ci) {
        if (this.gtShanhai$byproductMarkButton != null) {
            gtShanhai$placeByproductButtons();
        }
    }

    @Unique
    private void gtShanhai$placeByproductButtons() {
        int x = this.leftPos + GT_SHANHAI$ENCODE_LEFT - 10;
        int y = this.topPos + this.imageHeight - GT_SHANHAI$ENCODE_BOTTOM;
        this.gtShanhai$byproductMarkButton.setX(x);
        this.gtShanhai$byproductMarkButton.setY(y);
        this.gtShanhai$byproductUploadButton.setX(x);
        this.gtShanhai$byproductUploadButton.setY(y + 9);
    }

    @Unique
    private boolean gtShanhai$outputMarked() {
        List<Slot> slots = this.menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        if (slots.isEmpty()) return false;
        ItemStack stack = slots.get(0).getItem();
        return PatternByproductOrder.isMarked(stack);
    }

    @Unique
    private static final class MarkButton extends IconButton {
        private final BooleanSupplier marked;

        private MarkButton(Button.OnPress onPress, BooleanSupplier marked) {
            super(onPress);
            this.marked = marked;
            setHalfSize(true);
            setWidth(8);
            setHeight(8);
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            setTooltip(Tooltip.create(Component.translatable(this.marked.getAsBoolean()
                    ? "gt_shanhai.tooltip.pattern_byproduct_order.button_on"
                    : "gt_shanhai.tooltip.pattern_byproduct_order.button_off")));
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        protected Icon getIcon() {
            return this.marked.getAsBoolean() ? Icon.VALID : Icon.INVALID;
        }
    }

    @Unique
    private static final class UploadButton extends IconButton {
        private UploadButton(Button.OnPress onPress) {
            super(onPress);
            setHalfSize(true);
            setWidth(8);
            setHeight(8);
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            setTooltip(Tooltip.create(Component.translatable(
                    "gt_shanhai.tooltip.pattern_byproduct_order.upload")));
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        protected Icon getIcon() {
            return Icon.ARROW_RIGHT;
        }
    }
}
