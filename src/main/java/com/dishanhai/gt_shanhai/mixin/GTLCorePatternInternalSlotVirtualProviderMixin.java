package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;

import com.dishanhai.gt_shanhai.common.item.PatternNotConsumableFilter;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotState;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import org.gtlcore.gtlcore.integration.ae2.handler.SlotCacheManager;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
    public void gtShanhai$restoreVirtualTarget(AEKey key, long amount) {
        if (key instanceof AEItemKey itemKey) {
            VirtualPatternBufferSlotState.registerVirtualTarget(itemInventory, itemKey, amount);
        } else if (key instanceof AEFluidKey fluidKey) {
            VirtualPatternBufferSlotState.registerVirtualTarget(fluidInventory, fluidKey, amount);
        }
    }

    @Override
    public boolean gtShanhai$hasVirtualTarget(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            return VirtualPatternBufferSlotState.getVirtualTargets(itemInventory).containsKey(itemKey);
        }
        if (key instanceof AEFluidKey fluidKey) {
            return VirtualPatternBufferSlotState.getVirtualTargets(fluidInventory).containsKey(fluidKey);
        }
        return false;
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

    @Override
    public void gtShanhai$clearVirtualTargetsIfDepleted() {
        gtShanhai$clearCatalystsIfDepleted();
    }

    @Inject(method = "serializeNBT", at = @At("RETURN"), remap = false)
    private void gtShanhai$saveVirtualIdentity(CallbackInfoReturnable<CompoundTag> cir) {
        VirtualPatternBufferSlotState.writeVirtualTargets(itemInventory, fluidInventory, cir.getReturnValue());
    }

    @Inject(method = "deserializeNBT", at = @At("RETURN"), remap = false)
    private void gtShanhai$loadVirtualIdentity(CompoundTag tag, CallbackInfo ci) {
        VirtualPatternBufferSlotState.readVirtualTargets(itemInventory, fluidInventory, tag);
        gtShanhai$syncVirtualTargetsToCatalyst();
    }

    @Inject(method = "handleItemInternal", at = @At("HEAD"), remap = false)
    private void gtShanhai$snapshotVirtualItems(Object2LongMap<Ingredient> ingredients, int circuitConfig,
            boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        gtShanhai$itemVirtualSnapshot = simulate ? null : VirtualPatternBufferSlotState.snapshotVirtualTargets(itemInventory);
    }

    @Inject(method = "handleItemInternal", at = @At("RETURN"), remap = false)
    private void gtShanhai$restoreVirtualItems(Object2LongMap<Ingredient> ingredients, int circuitConfig,
            boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        if (!simulate && Boolean.TRUE.equals(cir.getReturnValue())
                && !PatternNotConsumableFilter.isActiveRecipeAuxiliaryIO()) {
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
        if (!simulate && Boolean.TRUE.equals(cir.getReturnValue())
                && !PatternNotConsumableFilter.isActiveRecipeAuxiliaryIO()) {
            gtShanhai$stripVirtualFluids();
        }
        gtShanhai$fluidVirtualSnapshot = null;
    }

    @Unique
    private void gtShanhai$stripVirtualItems() {
        VirtualPatternBufferSlotState.removeVirtualTargets(itemInventory, gtShanhai$getItemCatalystInventory());
        // 配方执行后剥离虚拟目标，但保留不消耗催化剂（chance==0）：让它撑住这一单的后续执行，
        // 不再执行一次即被清空。退料/下单结束走无谓词版全清，不残留。
        VirtualPatternBufferSlotState.stripVirtualTargets(itemInventory,
                PatternNotConsumableFilter::isKeyNotConsumableForActiveRecipe);
        gtShanhai$clearCatalystsIfDepleted();
    }

    @Unique
    private void gtShanhai$stripVirtualFluids() {
        VirtualPatternBufferSlotState.removeVirtualTargets(fluidInventory, gtShanhai$getFluidCatalystInventory());
        VirtualPatternBufferSlotState.stripVirtualTargets(fluidInventory,
                PatternNotConsumableFilter::isKeyNotConsumableForActiveRecipe);
        gtShanhai$clearCatalystsIfDepleted();
    }

    /**
     * 收尾清理：若这个槽位的真实消耗料已全部耗尽（item/fluid 两仓减去虚拟目标后都为 0，即只剩催化剂
     * 虚拟凑数），说明这一单已经做完，把常驻的催化剂虚拟目标也一并清空——否则催化剂会永久残留、
     * 让 isActive() 恒真。执行期间只要还有一份真实消耗料没用完，就不会触发，催化剂照常保留在场。
     */
    @Unique
    private void gtShanhai$clearCatalystsIfDepleted() {
        if (gtShanhai$inventoryHasNoRealStock(itemInventory) && gtShanhai$inventoryHasNoRealStock(fluidInventory)) {
            gtShanhai$stripVirtualTargetsFromCatalyst();
            gtShanhai$clearVirtualCircuitCache();
            VirtualPatternBufferSlotState.stripVirtualTargets(itemInventory);
            VirtualPatternBufferSlotState.stripVirtualTargets(fluidInventory);
        }
    }

    @Unique
    private void gtShanhai$clearVirtualCircuitCache() {
        if (VirtualPatternBufferSlotState.getVirtualCircuit(itemInventory) < 0
                && !gtShanhai$hasVirtualCircuitTarget()) return;
        SlotCacheManager cacheManager = getCacheManager();
        if (cacheManager instanceof SlotCacheManagerAccessor accessor) {
            accessor.gtShanhai$setCircuitCacheRaw(-1);
            accessor.gtShanhai$setCircuitStackRaw(ItemStack.EMPTY);
        }
        VirtualPatternBufferSlotState.clearVirtualCircuit(itemInventory);
    }

    @Unique
    private boolean gtShanhai$hasVirtualCircuitTarget() {
        for (AEItemKey key : VirtualPatternBufferSlotState.getVirtualTargets(itemInventory).keySet()) {
            if (key != null && IntCircuitBehaviour.isIntegratedCircuit(key.toStack())) return true;
        }
        return false;
    }

    @Unique
    private <T extends AEKey> boolean gtShanhai$inventoryHasNoRealStock(Object2LongOpenHashMap<T> inventory) {
        if (inventory.isEmpty()) return true;
        Object2LongMap<T> targets = VirtualPatternBufferSlotState.getVirtualTargets(inventory);
        for (Object2LongMap.Entry<T> entry : inventory.object2LongEntrySet()) {
            long real = entry.getLongValue() - targets.getLong(entry.getKey());
            if (real > 0) return false; // 尚有真实消耗料，这一单没做完，保留催化剂
        }
        return true;
    }

    @Shadow
    public Object2LongMap<AEItemKey> getItemCatalystInventory() {
        throw new AssertionError();
    }

    @Shadow
    public Object2LongMap<AEFluidKey> getFluidCatalystInventory() {
        throw new AssertionError();
    }

    @Shadow
    public SlotCacheManager getCacheManager() {
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
