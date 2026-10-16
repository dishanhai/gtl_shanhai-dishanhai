package com.dishanhai.gt_shanhai.mixin;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.Text;
import appeng.client.gui.widgets.IconButton;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.FakeSlot;

import com.dishanhai.gt_shanhai.client.PatternWrapClientState;
import com.dishanhai.gt_shanhai.common.ae2.ByproductOrderMenu;
import com.dishanhai.gt_shanhai.common.item.PatternEncodeOverride;
import com.dishanhai.gt_shanhai.common.item.PatternEncodePreferences;
import com.dishanhai.gt_shanhai.common.item.PatternWrapControlMenu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    @Shadow(remap = false)
    @Final
    protected ScreenStyle style;

    @Unique
    private IconButton gtShanhai$wrapModeButton;

    @Unique
    private EncodeToggleButton gtShanhai$recipeIdButton;

    @Unique
    private EncodeToggleButton gtShanhai$byproductButton;

    @Unique
    private EncodeToggleButton gtShanhai$virtualProviderButton;

    @Unique
    private boolean gtShanhai$markByproductOnEncode;

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
        gtShanhai$ensureEncodeToggleButtons();
        gtShanhai$placeEncodeToggles();
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void gtShanhai$moveEncodeToggles(CallbackInfo ci) {
        if (!gtShanhai$isPatternEncodingScreen()) return;
        gtShanhai$ensureEncodeToggleButtons();
        gtShanhai$placeEncodeToggles();
    }

    @Unique
    private void gtShanhai$ensureEncodeToggleButtons() {
        if (this.gtShanhai$recipeIdButton != null) {
            gtShanhai$ensureEncodeToggle(this.gtShanhai$wrapModeButton);
            gtShanhai$ensureEncodeToggle(this.gtShanhai$recipeIdButton);
            gtShanhai$ensureEncodeToggle(this.gtShanhai$byproductButton);
            gtShanhai$ensureEncodeToggle(this.gtShanhai$virtualProviderButton);
            return;
        }
        this.gtShanhai$wrapModeButton = new WrapModeButton(button -> {
            if (this.menu instanceof PatternWrapControlMenu control) {
                PatternEncodeOverride.WrapMode[] values = PatternEncodeOverride.WrapMode.values();
                PatternWrapClientState.mode = values[(PatternWrapClientState.mode.ordinal() + 1) % values.length];
                control.gtShanhai$cycleWrapMode();
            }
        });
        this.gtShanhai$recipeIdButton = new EncodeToggleButton(Icon.ENTER, 0x00D4FF, 0x073044,
                () -> PatternEncodePreferences.writeRecipeId(),
                button -> gtShanhai$toggleRecipeId(),
                () -> PatternEncodePreferences.writeRecipeId()
                        ? "配方 ID：开。编码和快速上传都会写入精确配方 ID。"
                        : "配方 ID：关。编码和快速上传都不写配方 ID。");
        this.gtShanhai$byproductButton = new EncodeToggleButton(Icon.ARROW_RIGHT, 0xFF7A18, 0x3A220C,
                () -> this.gtShanhai$markByproductOnEncode,
                button -> gtShanhai$toggleByproductEncode(),
                () -> this.gtShanhai$markByproductOnEncode
                        ? "副产物下单：开。只作用于本页编码按钮，快速上传仍用它自己的按钮。"
                        : "副产物下单：关。本页编码按钮不会打副产物标记。");
        this.gtShanhai$virtualProviderButton = new EncodeToggleButton(Icon.TYPE_FILTER_ALL, 0xFF3DDE, 0x3A1038,
                () -> PatternEncodePreferences.writeVirtualProvider(),
                button -> gtShanhai$toggleVirtualProvider(),
                () -> PatternEncodePreferences.writeVirtualProvider()
                        ? "虚拟供应器：开。不消耗输入会按原规则写成虚拟供应器，快速上传同样生效。"
                        : "虚拟供应器：关。编码和快速上传都保留原物品或流体。");
        addRenderableWidget(this.gtShanhai$wrapModeButton);
        addRenderableWidget(this.gtShanhai$recipeIdButton);
        addRenderableWidget(this.gtShanhai$byproductButton);
        addRenderableWidget(this.gtShanhai$virtualProviderButton);
    }

    @Unique
    private void gtShanhai$ensureEncodeToggle(Button button) {
        if (button != null && !this.renderables.contains(button)) {
            addRenderableWidget(button);
        }
    }

    @Unique
    private void gtShanhai$placeEncodeToggles() {
        int x = this.leftPos + 8;
        int y = this.topPos + this.imageHeight - 175;
        Text title = this.style.getText().get("crafting_grid_title");
        if (title != null && title.getPosition() != null) {
            Point pos = title.getPosition().resolve(new Rect2i(this.leftPos, this.topPos, this.imageWidth, this.imageHeight));
            x = pos.getX();
            y = pos.getY();
        }
        x += this.font.width(Component.translatable("gui.ae2.PatternEncoding")) + 4;
        this.gtShanhai$wrapModeButton.setX(x);
        this.gtShanhai$wrapModeButton.setY(y);
        x += this.gtShanhai$wrapModeButton.getWidth() + 3;
        this.gtShanhai$recipeIdButton.setX(x);
        this.gtShanhai$recipeIdButton.setY(y);
        x += this.gtShanhai$recipeIdButton.getWidth() + 2;
        this.gtShanhai$byproductButton.setX(x);
        this.gtShanhai$byproductButton.setY(y);
        x += this.gtShanhai$byproductButton.getWidth() + 2;
        this.gtShanhai$virtualProviderButton.setX(x);
        this.gtShanhai$virtualProviderButton.setY(y);
    }

    @Unique
    private void gtShanhai$toggleRecipeId() {
        boolean enabled = !PatternEncodePreferences.writeRecipeId();
        PatternEncodePreferences.setWriteRecipeId(enabled);
        if (this.menu instanceof PatternWrapControlMenu control) {
            control.gtShanhai$setWriteRecipeId(enabled);
        }
    }

    @Unique
    private void gtShanhai$toggleVirtualProvider() {
        boolean enabled = !PatternEncodePreferences.writeVirtualProvider();
        PatternEncodePreferences.setWriteVirtualProvider(enabled);
        if (this.menu instanceof PatternWrapControlMenu control) {
            control.gtShanhai$setWriteVirtualProvider(enabled);
        }
    }

    @Unique
    private void gtShanhai$toggleByproductEncode() {
        this.gtShanhai$markByproductOnEncode = !this.gtShanhai$markByproductOnEncode;
        if (this.menu instanceof ByproductOrderMenu actions) {
            actions.gtShanhai$setMarkByproductOnEncode(this.gtShanhai$markByproductOnEncode);
        }
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

    @Unique
    private static final class EncodeToggleButton extends IconButton {
        private final Icon icon;
        private final int onColor;
        private final int offColor;
        private final java.util.function.BooleanSupplier enabled;
        private final java.util.function.Supplier<String> tip;

        private EncodeToggleButton(Icon icon, int onColor, int offColor,
                java.util.function.BooleanSupplier enabled, OnPress onPress,
                java.util.function.Supplier<String> tip) {
            super(onPress);
            this.icon = icon;
            this.onColor = onColor;
            this.offColor = offColor;
            this.enabled = enabled;
            this.tip = tip;
            setHalfSize(true);
            setWidth(8);
            setHeight(8);
            setDisableBackground(true);
        }

        @Override
        protected Icon getIcon() {
            return this.icon;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            if (!this.visible) return;
            boolean on = this.enabled.getAsBoolean();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(getX(), getY(), 0.0F);
            pose.scale(0.5F, 0.5F, 1.0F);
            Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter()
                    .colorRgb(on ? this.onColor : this.offColor)
                    .dest(0, 0)
                    .blit(guiGraphics);
            Blitter iconBlit = this.icon.getBlitter().dest(0, 0);
            if (!on) {
                iconBlit.opacity(0.45F);
            }
            iconBlit.blit(guiGraphics);
            pose.popPose();
            RenderSystem.enableDepthTest();
            setTooltip(Tooltip.create(Component.literal(this.tip.get())));
        }
    }
}
