package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.menu.AEBaseMenu;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingRedispatchMenu;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在合成状态页取消按钮左侧增加量子 CPU 重发配按钮。 */
@Mixin(AEBaseScreen.class)
public abstract class CraftingCPUScreenRedispatchMixin<T extends AEBaseMenu> extends AbstractContainerScreen<T> {

    @Unique
    private Button gtShanhai$redispatchButton;

    private CraftingCPUScreenRedispatchMixin(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void gtShanhai$addRedispatchButton(CallbackInfo ci) {
        if (!((Object) this instanceof CraftingCPUScreen<?>)) return;
        if (!(this.menu instanceof QuantumCraftingRedispatchMenu control)) return;
        this.gtShanhai$redispatchButton = Button.builder(
                Component.literal("重发配"), button -> control.gtShanhai$redispatch())
                .bounds(this.leftPos + 108, this.topPos + this.imageHeight - 25, 50, 20)
                .build();
        this.gtShanhai$redispatchButton.setTooltip(Tooltip.create(Component.literal(
                "从 AE 网络补取当前量子 CPU 剩余任务缺失的普通原料，并重新尝试发配")));
        addRenderableWidget(this.gtShanhai$redispatchButton);
    }
}
