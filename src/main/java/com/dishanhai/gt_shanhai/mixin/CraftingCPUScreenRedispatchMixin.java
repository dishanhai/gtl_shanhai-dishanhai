package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.menu.AEBaseMenu;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingRedispatchMenu;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiButton;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** 重发配单独放在面板下方，避开 AE2 和 GTLCore 的底栏。 */
@Mixin(AEBaseScreen.class)
public abstract class CraftingCPUScreenRedispatchMixin<T extends AEBaseMenu> extends AbstractContainerScreen<T> {

    @Unique
    private UiButton gtShanhai$redispatchButton;

    private CraftingCPUScreenRedispatchMixin(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    // 本项目无 refmap：AE2 覆写的 Screen.init 必须是 SRG 名 + remap=false。
    @Inject(method = "m_7856_", at = @At("TAIL"), remap = false)
    private void gtShanhai$addRedispatchButton(CallbackInfo ci) {
        if (!((Object) this instanceof CraftingCPUScreen<?>)) return;
        if (!(this.menu instanceof QuantumCraftingRedispatchMenu control)) return;
        Component label = Component.translatable("gt_shanhai.quantum_cpu.redispatch.button");
        int buttonWidth = Math.max(50, this.font.width(label) + 16);
        this.gtShanhai$redispatchButton = new UiButton(
                this.leftPos + this.imageWidth - 8 - buttonWidth,
                this.topPos + this.imageHeight + 2, buttonWidth, 18,
                label, button -> control.gtShanhai$redispatch());
        this.gtShanhai$redispatchButton.setPreview(
                Component.translatable("gt_shanhai.quantum_cpu.redispatch.tooltip"));
        this.gtShanhai$redispatchButton.visible = control.gtShanhai$canRedispatch();
        this.gtShanhai$redispatchButton.active = this.gtShanhai$redispatchButton.visible;
        addRenderableWidget(this.gtShanhai$redispatchButton);
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void gtShanhai$updateRedispatchButton(CallbackInfo ci) {
        if (this.gtShanhai$redispatchButton != null && this.menu instanceof QuantumCraftingRedispatchMenu control) {
            this.gtShanhai$redispatchButton.visible = control.gtShanhai$canRedispatch();
            this.gtShanhai$redispatchButton.active = this.gtShanhai$redispatchButton.visible;
            if (!this.gtShanhai$redispatchButton.visible && getFocused() == this.gtShanhai$redispatchButton) {
                setFocused(null);
            }
        }
    }

    @Inject(method = "getExclusionZones", at = @At("RETURN"), remap = false)
    private void gtShanhai$excludeRedispatchButton(CallbackInfoReturnable<List<Rect2i>> cir) {
        UiButton button = this.gtShanhai$redispatchButton;
        if (button != null && button.visible) {
            cir.getReturnValue().add(new Rect2i(button.getX(), button.getY(), button.getWidth(), button.getHeight()));
        }
    }
}
