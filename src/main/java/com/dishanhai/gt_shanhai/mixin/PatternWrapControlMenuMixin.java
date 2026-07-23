package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.GenericStack;
import appeng.api.storage.ITerminalHost;
import appeng.helpers.IPatternTerminalMenuHost;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.util.ConfigInventory;

import com.dishanhai.gt_shanhai.common.item.PatternEncodeOverride;
import com.dishanhai.gt_shanhai.common.item.PatternWrapControlMenu;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 样板终端(样板编码界面)"包裹模式"手动覆盖：三态开关(自动/强制包裹/强制不包裹) + 手动标记
 * "不消耗自动包裹"的输入槽位——用来绕开 VirtualPatternEncodingHelper 反查配方时可能出现的
 * 歧义放弃/槽位数量不符等静默失败（ERR/LRN 20260718 无法包裹排查）。
 * 与 GTLCore 自己的 PatternEncodingTermMenuMixin 共存，方法名不冲突。
 */
@Mixin(value = PatternEncodingTermMenu.class, priority = 900, remap = false)
public abstract class PatternWrapControlMenuMixin extends MEStorageMenu implements PatternWrapControlMenu {

    @Shadow(remap = false)
    @Final
    private ConfigInventory encodedInputsInv;

    @Shadow(remap = false)
    @Final
    private ConfigInventory encodedOutputsInv;

    // getPlayer() 是从 MEStorageMenu 继承来的具体方法（不是 PatternEncodingTermMenu 自己声明的），
    // 直接靠 extends MEStorageMenu 的编译期继承就能拿到，@Shadow 它反而会报"target 类里找不到"
    // （Mixin 只在目标类自身声明的成员里找 @Shadow，不会往父类找）。

    @Unique
    private PatternEncodeOverride.WrapMode gtShanhai$wrapMode = PatternEncodeOverride.WrapMode.AUTO;

    @Unique
    private final Set<Integer> gtShanhai$markedSlots = new LinkedHashSet<>();

    protected PatternWrapControlMenuMixin(MenuType<?> menuType, int id, Inventory ip, ITerminalHost host) {
        super(menuType, id, ip, host);
    }

    @Inject(method = {"<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;Lappeng/helpers/IPatternTerminalMenuHost;Z)V"},
            at = {@At("TAIL")}, remap = false)
    private void gtShanhai$registerWrapControlActions(MenuType<?> menuType, int id, Inventory ip,
            IPatternTerminalMenuHost host, boolean bindInventory, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        registerClientAction("gtShanhaiCycleWrapMode", this::gtShanhai$cycleWrapMode);
        registerClientAction("gtShanhaiToggleMark", Integer.class, this::gtShanhai$toggleMark);
    }

    @Override
    @Unique
    public void gtShanhai$cycleWrapMode() {
        if (isClientSide()) {
            sendClientAction("gtShanhaiCycleWrapMode");
            return;
        }
        PatternEncodeOverride.WrapMode[] values = PatternEncodeOverride.WrapMode.values();
        this.gtShanhai$wrapMode = values[(this.gtShanhai$wrapMode.ordinal() + 1) % values.length];
        Player player = getPlayer();
        if (player != null) {
            player.displayClientMessage(Component.literal("[虚拟供应] 包裹模式: " + gtShanhai$modeLabel(this.gtShanhai$wrapMode)), true);
        }
    }

    @Override
    @Unique
    public void gtShanhai$toggleMark(Integer slot) {
        if (isClientSide()) {
            sendClientAction("gtShanhaiToggleMark", slot);
            return;
        }
        if (slot == null || slot < 0 || slot >= this.encodedInputsInv.size()) return;
        if (!this.gtShanhai$markedSlots.remove(slot)) {
            this.gtShanhai$markedSlots.add(slot);
        }
    }

    @Unique
    private static String gtShanhai$modeLabel(PatternEncodeOverride.WrapMode mode) {
        switch (mode) {
            case FORCE_WRAP: return "强制包裹(只按手动标记)";
            case FORCE_NO_WRAP: return "强制不包裹";
            default: return "自动(配方引擎+手动标记)";
        }
    }

    @Inject(method = "encodeProcessingPattern", at = @At("HEAD"), remap = false)
    private void gtShanhai$pushWrapOverride(CallbackInfoReturnable<ItemStack> cir) {
        PatternEncodeOverride.push(new PatternEncodeOverride(this.gtShanhai$wrapMode, this.gtShanhai$markedSlots));
    }

    @Inject(method = "encodeProcessingPattern", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$popWrapOverride(CallbackInfoReturnable<ItemStack> cir) {
        String diagnostic;
        try {
            if (cir.getReturnValue() == null) {
                ItemStack recovered = gtShanhai$encodeVirtualOnlyPattern();
                if (!recovered.isEmpty()) {
                    PatternEncodeOverride.setLastDiagnostic(
                            "all visible inputs omitted; restored virtual non-consumables");
                    cir.setReturnValue(recovered);
                }
            }
            diagnostic = PatternEncodeOverride.consumeLastDiagnostic();
        } finally {
            PatternEncodeOverride.pop();
        }
        if (diagnostic == null || diagnostic.isEmpty()) return;
        Player player = getPlayer();
        if (player != null) {
            player.displayClientMessage(Component.literal("[虚拟供应] " + diagnostic), true);
        }
    }

    @Unique
    private ItemStack gtShanhai$encodeVirtualOnlyPattern() {
        GenericStack[] inputs = new GenericStack[this.encodedInputsInv.size()];
        for (int slot = 0; slot < inputs.length; slot++) {
            inputs[slot] = this.encodedInputsInv.getStack(slot);
            if (inputs[slot] != null) return ItemStack.EMPTY;
        }

        GenericStack[] outputs = new GenericStack[this.encodedOutputsInv.size()];
        for (int slot = 0; slot < outputs.length; slot++) {
            outputs[slot] = this.encodedOutputsInv.getStack(slot);
        }
        if (outputs.length == 0 || outputs[0] == null) return ItemStack.EMPTY;

        return VirtualPatternEncodingHelper.encodeProcessingPatternRecoveringVirtualInputs(inputs, outputs);
    }
}
