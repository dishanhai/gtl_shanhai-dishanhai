package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.widgets.IconButton;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.FakeSlot;

import com.dishanhai.gt_shanhai.client.PatternWrapClientState;
import com.dishanhai.gt_shanhai.common.item.PatternEncodeOverride;
import com.dishanhai.gt_shanhai.common.item.PatternWrapControlMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 样板终端界面：右上角加一个三态"包裹模式"按钮(自动/强制包裹/强制不包裹)，
 * 并给手动标记过"不消耗自动包裹"的输入槽位画高亮框。纯展示，真正生效的判断逻辑
 * 在服务端 PatternWrapControlMenuMixin / VirtualPatternEncodingHelper。
 */
@Mixin({AEBaseScreen.class})
public abstract class PatternWrapControlScreenMixin<T extends AEBaseMenu> extends AbstractContainerScreen<T> {

    @Unique
    private IconButton gtShanhai$wrapModeButton;

    private PatternWrapControlScreenMixin(T menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Unique
    private boolean gtShanhai$isPatternEncodingScreen() {
        return (Object) this instanceof PatternEncodingTermScreen;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void gtShanhai$initWrapModeButton(CallbackInfo ci) {
        if (!gtShanhai$isPatternEncodingScreen()) return;
        PatternWrapClientState.reset();
        this.gtShanhai$wrapModeButton = new WrapModeButton(button -> {
            if (this.menu instanceof PatternWrapControlMenu control) {
                PatternEncodeOverride.WrapMode[] values = PatternEncodeOverride.WrapMode.values();
                PatternWrapClientState.mode = values[(PatternWrapClientState.mode.ordinal() + 1) % values.length];
                control.gtShanhai$cycleWrapMode();
            }
        });
        this.gtShanhai$wrapModeButton.setX(this.leftPos + this.imageWidth - 10);
        this.gtShanhai$wrapModeButton.setY(this.topPos + 3);
        addRenderableWidget(this.gtShanhai$wrapModeButton);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void gtShanhai$renderMarkedSlotHighlights(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!gtShanhai$isPatternEncodingScreen() || PatternWrapClientState.markedSlots.isEmpty()) return;
        if (!(this.menu instanceof PatternEncodingTermMenu patternMenu)) return;
        int color = 0xFFFFD24A;
        for (FakeSlot slot : patternMenu.getProcessingInputSlots()) {
            if (!PatternWrapClientState.markedSlots.contains(slot.getContainerSlot())) continue;
            gtShanhai$drawSlotOutline(guiGraphics, slot, color);
        }
    }

    @Unique
    private void gtShanhai$drawSlotOutline(GuiGraphics guiGraphics, Slot slot, int color) {
        int x = this.leftPos + slot.x - 1;
        int y = this.topPos + slot.y - 1;
        int size = 18;
        guiGraphics.fill(x, y, x + size, y + 1, color);
        guiGraphics.fill(x, y + size - 1, x + size, y + size, color);
        guiGraphics.fill(x, y, x + 1, y + size, color);
        guiGraphics.fill(x + size - 1, y, x + size, y + size, color);
    }

    @Unique
    private static final class WrapModeButton extends IconButton {
        private WrapModeButton(Button.OnPress onPress) {
            super(onPress);
            setHalfSize(true);
            setWidth(8);
            setHeight(8);
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            setTooltip(Tooltip.create(Component.literal("虚拟供应包裹模式: " + gtShanhai$label())));
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        }

        private static String gtShanhai$label() {
            return switch (PatternWrapClientState.mode) {
                case FORCE_WRAP -> "强制包裹(只按手动标记)";
                case FORCE_NO_WRAP -> "强制不包裹";
                default -> "自动(配方引擎+手动标记)";
            };
        }

        @Override
        protected Icon getIcon() {
            return switch (PatternWrapClientState.mode) {
                case FORCE_WRAP -> Icon.LOCKED;
                case FORCE_NO_WRAP -> Icon.SUBSTITUTION_DISABLED;
                default -> Icon.SUBSTITUTION_ENABLED;
            };
        }
    }
}
