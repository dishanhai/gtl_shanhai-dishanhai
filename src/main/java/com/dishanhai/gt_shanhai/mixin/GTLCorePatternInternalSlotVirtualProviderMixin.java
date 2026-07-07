package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;

import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotState;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import net.minecraft.world.item.crafting.Ingredient;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

@Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase$InternalSlot", remap = false)
public class GTLCorePatternInternalSlotVirtualProviderMixin implements VirtualPatternBufferSlotAccess {

    @Shadow
    @Final
    private Object2LongOpenHashMap<AEItemKey> itemInventory;

    @Shadow
    @Final
    private Object2LongOpenHashMap<AEFluidKey> fluidInventory;

    @Unique
    private Object2LongOpenHashMap<AEItemKey> gtShanhai$itemVirtualSnapshot;

    @Unique
    private Object2LongOpenHashMap<AEFluidKey> gtShanhai$fluidVirtualSnapshot;

    @Override
    public void gtShanhai$addVirtualTarget(AEKey key, long amount) {
        if (key instanceof AEItemKey itemKey) {
            VirtualPatternBufferSlotState.addVirtualTarget(itemInventory, itemKey, amount);
        } else if (key instanceof AEFluidKey fluidKey) {
            VirtualPatternBufferSlotState.addVirtualTarget(fluidInventory, fluidKey, amount);
        }
    }

    @Override
    public void gtShanhai$syncVirtualTargetsToCatalyst() {
        VirtualPatternBufferSlotState.copyVirtualTargets(itemInventory, gtShanhai$getItemCatalystInventory());
        VirtualPatternBufferSlotState.copyVirtualTargets(fluidInventory, gtShanhai$getFluidCatalystInventory());
    }

    @Override
    public void gtShanhai$stripVirtualTargetsFromCatalyst() {
        VirtualPatternBufferSlotState.removeVirtualTargets(itemInventory, gtShanhai$getItemCatalystInventory());
        VirtualPatternBufferSlotState.removeVirtualTargets(fluidInventory, gtShanhai$getFluidCatalystInventory());
    }

    @Override
    public void gtShanhai$stripVirtualTargets() {
        gtShanhai$stripVirtualItems();
        gtShanhai$stripVirtualFluids();
    }

    @Inject(method = "handleItemInternal", at = @At("HEAD"), remap = false)
    private void gtShanhai$snapshotVirtualItems(Object2LongMap<Ingredient> ingredients, int circuitConfig,
            boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        gtShanhai$itemVirtualSnapshot = simulate ? null : VirtualPatternBufferSlotState.snapshotVirtualTargets(itemInventory);
    }

    @Inject(method = "handleItemInternal", at = @At("RETURN"), remap = false)
    private void gtShanhai$restoreVirtualItems(Object2LongMap<Ingredient> ingredients, int circuitConfig,
            boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        if (!simulate && Boolean.TRUE.equals(cir.getReturnValue())) {
            gtShanhai$stripVirtualItems();
        }
        gtShanhai$itemVirtualSnapshot = null;
    }

    @Inject(method = "handleFluidInternal", at = @At("HEAD"), remap = false)
    private void gtShanhai$snapshotVirtualFluids(Object2LongMap<FluidIngredient> ingredients,
            boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        gtShanhai$fluidVirtualSnapshot = simulate ? null : VirtualPatternBufferSlotState.snapshotVirtualTargets(fluidInventory);
    }

    @Inject(method = "handleFluidInternal", at = @At("RETURN"), remap = false)
    private void gtShanhai$restoreVirtualFluids(Object2LongMap<FluidIngredient> ingredients,
            boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        if (!simulate && Boolean.TRUE.equals(cir.getReturnValue())) {
            gtShanhai$stripVirtualFluids();
        }
        gtShanhai$fluidVirtualSnapshot = null;
    }

    @Unique
    private void gtShanhai$stripVirtualItems() {
        VirtualPatternBufferSlotState.removeVirtualTargets(itemInventory, gtShanhai$getItemCatalystInventory());
        VirtualPatternBufferSlotState.stripVirtualTargets(itemInventory);
    }

    @Unique
    private void gtShanhai$stripVirtualFluids() {
        VirtualPatternBufferSlotState.removeVirtualTargets(fluidInventory, gtShanhai$getFluidCatalystInventory());
        VirtualPatternBufferSlotState.stripVirtualTargets(fluidInventory);
    }

    @Shadow
    public Object2LongMap<AEItemKey> getItemCatalystInventory() {
        throw new AssertionError();
    }

    @Shadow
    public Object2LongMap<AEFluidKey> getFluidCatalystInventory() {
        throw new AssertionError();
    }

    @Unique
    private Object2LongMap<AEItemKey> gtShanhai$getItemCatalystInventory() {
        return getItemCatalystInventory();
    }

    @Unique
    private Object2LongMap<AEFluidKey> gtShanhai$getFluidCatalystInventory() {
        return getFluidCatalystInventory();
    }
}
