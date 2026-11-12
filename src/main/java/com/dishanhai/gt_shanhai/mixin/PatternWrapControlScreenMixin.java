package com.dishanhai.gt_shanhai.mixin;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.Text;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.FakeSlot;

import com.dishanhai.gt_shanhai.client.PatternWrapClientState;
import com.dishanhai.gt_shanhai.client.gui.PatternEncodingIconButton;
import com.dishanhai.gt_shanhai.client.gui.PatternEncodingIconButton.Appearance;
import com.dishanhai.gt_shanhai.common.ae2.ByproductOrderMenu;
import com.dishanhai.gt_shanhai.common.item.PatternEncodeOverride.WrapMode;
import com.dishanhai.gt_shanhai.common.item.PatternEncodePreferences;
import com.dishanhai.gt_shanhai.common.item.PatternWrapControlMenu;

import com.dishanhai.sh_better_ui.client.gui.display.GraphicIcon;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 样板终端界面：编码标题旁显示三态包裹模式和编码开关，
 * 并给手动标记过"不消耗自动包裹"的输入槽位画高亮框。纯展示，真正生效的判断逻辑
 * 在服务端 PatternWrapControlMenuMixin / VirtualPatternEncodingHelper。
 */
@Mixin({AEBaseScreen.class})
public abstract class PatternWrapControlScreenMixin<T extends AEBaseMenu> extends AbstractContainerScreen<T> {

    @Unique
    private static final int gtShanhai$inactiveIconTint = 0xFF4B5160;

    @Shadow(remap = false)
    @Final
    protected ScreenStyle style;

    @Unique
    private PatternEncodingIconButton gtShanhai$wrapModeButton;

    @Unique
    private PatternEncodingIconButton gtShanhai$recipeIdButton;

    @Unique
    private PatternEncodingIconButton gtShanhai$byproductButton;

    @Unique
    private PatternEncodingIconButton gtShanhai$virtualProviderButton;

    @Unique
    private boolean gtShanhai$markByproductOnEncode;

    private PatternWrapControlScreenMixin(T menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Unique
    private boolean gtShanhai$isPatternEncodingScreen() {
        return (Object) this instanceof PatternEncodingTermScreen;
    }

    // 本项目无 refmap：AE2 覆写的 Screen.init / Screen.render 必须是 SRG 名 + remap=false。
    @Inject(method = "m_7856_", at = @At("TAIL"), remap = false)
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
        Map<WrapMode, Appearance> wrapAppearances = Map.of(
                WrapMode.AUTO, gtShanhai$appearance(GraphicIcon.SETTINGS, 0xFF6C450A, "wrap.auto"),
                WrapMode.FORCE_WRAP, gtShanhai$appearance(GraphicIcon.LOCK, 0xFF6C450A, "wrap.on"),
                WrapMode.FORCE_NO_WRAP,
                gtShanhai$appearance(GraphicIcon.UNLOCK, gtShanhai$inactiveIconTint, "wrap.off"));
        this.gtShanhai$wrapModeButton = new PatternEncodingIconButton(() -> wrapAppearances.get(PatternWrapClientState.mode), button -> {
            if (this.menu instanceof PatternWrapControlMenu control) {
                WrapMode[] values = WrapMode.values();
                PatternWrapClientState.mode = values[(PatternWrapClientState.mode.ordinal() + 1) % values.length];
                control.gtShanhai$cycleWrapMode();
            }
        });
        this.gtShanhai$recipeIdButton = new PatternEncodingIconButton(
                gtShanhai$appearance(GraphicIcon.RECIPE_ID, 0xFF005372, "recipe_id.on"),
                gtShanhai$appearance(GraphicIcon.RECIPE_ID, gtShanhai$inactiveIconTint, "recipe_id.off"),
                PatternEncodePreferences::writeRecipeId, button -> gtShanhai$toggleRecipeId());
        this.gtShanhai$byproductButton = new PatternEncodingIconButton(
                gtShanhai$appearance(GraphicIcon.EXPORT, 0xFF82370B, "byproduct.on"),
                gtShanhai$appearance(GraphicIcon.EXPORT, gtShanhai$inactiveIconTint, "byproduct.off"),
                () -> this.gtShanhai$markByproductOnEncode, button -> gtShanhai$toggleByproductEncode());
        this.gtShanhai$virtualProviderButton = new PatternEncodingIconButton(
                gtShanhai$appearance(GraphicIcon.VIRTUAL_ITEM_PROVIDER, 0xFF6B246F, "virtual_provider.on"),
                gtShanhai$appearance(GraphicIcon.VIRTUAL_ITEM_PROVIDER, gtShanhai$inactiveIconTint, "virtual_provider.off"),
                PatternEncodePreferences::writeVirtualProvider, button -> gtShanhai$toggleVirtualProvider());
        addRenderableWidget(this.gtShanhai$wrapModeButton);
        addRenderableWidget(this.gtShanhai$recipeIdButton);
        addRenderableWidget(this.gtShanhai$byproductButton);
        addRenderableWidget(this.gtShanhai$virtualProviderButton);
    }

    @Unique
    private Appearance gtShanhai$appearance(GraphicIcon fallback, int tint, String state) {
        ResourceLocation texture = new ResourceLocation("sh_better_ui",
                "textures/gui/display/pattern_" + state.replace('.', '_') + ".png");
        GraphicIcon icon = this.minecraft.getResourceManager().getResource(texture).isPresent() ? new GraphicIcon(texture) : fallback;
        return new Appearance(icon, tint, Component.translatable("tooltip.gt_shanhai.pattern_encoding." + state));
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
        x += this.gtShanhai$wrapModeButton.getWidth() + 2;
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

    @Inject(method = "m_88315_", at = @At("TAIL"), remap = false)
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
}
