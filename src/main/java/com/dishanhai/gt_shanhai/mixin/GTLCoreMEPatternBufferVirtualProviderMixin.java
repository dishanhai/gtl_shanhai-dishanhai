package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumPatternBufferRefundAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferMachineAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotState;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.integration.ae2.handler.SlotCacheManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.lang.reflect.Method;

@Mixin(value = MEPatternBufferPartMachineBase.class, remap = false)
public abstract class GTLCoreMEPatternBufferVirtualProviderMixin implements QuantumPatternBufferRefundAccess,
        VirtualPatternBufferMachineAccess {

    @Shadow
    protected Object2LongOpenHashMap<AEKey> buffer;

    @Shadow
    protected abstract Integer getSlotIndexForPattern(IPatternDetails patternDetails);

    @Shadow
    protected abstract int getInternalSlotCount();

    @Inject(method = "pushPattern", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$pushVirtualProviderPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder,
            CallbackInfoReturnable<Boolean> cir) {
        if (!VirtualPatternEncodingHelper.containsVirtualProviderPattern(patternDetails)) {
            return;
        }
        Integer slotIndex = getSlotIndexForPattern(patternDetails);
        IManagedGridNode mainNode = gtShanhai$getMainNode();
        if (mainNode == null || !mainNode.isActive() || slotIndex == null || slotIndex < 0
                || slotIndex >= getInternalSlotCount()) {
            cir.setReturnValue(false);
            return;
        }

        Object slot = gtShanhai$getInternalSlot(slotIndex);
        if (slot == null) {
            cir.setReturnValue(false);
            return;
        }
        VirtualPatternEncodingHelper.pushPatternInputsIncludingVirtual(patternDetails, inputHolder,
                (what, amount) -> gtShanhai$addToSlot(slot, what, amount),
                (what, amount) -> gtShanhai$addVirtualTargetToSlot(slot, what, amount));
        gtShanhai$notifySlotChanged(slot);
        cir.setReturnValue(true);
    }

    @Inject(method = "refundSlot", at = @At("HEAD"), remap = false)
    private void gtShanhai$stripVirtualTargetsBeforeRefund(Object2LongOpenHashMap<AEItemKey> itemInventory,
            Object2LongOpenHashMap<AEFluidKey> fluidInventory, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        gtShanhai$stripVirtualTargetsFromCatalyst(itemInventory, fluidInventory);
        VirtualPatternBufferSlotState.stripVirtualTargets(itemInventory);
        VirtualPatternBufferSlotState.stripVirtualTargets(fluidInventory);
    }

    @Override
    public String gtShanhai$getQuantumRefundId() {
        Level level = gtShanhai$getLevel();
        BlockPos pos = gtShanhai$getPos();
        if (level == null || pos == null) {
            return "";
        }
        return level.dimension().location() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Override
    public boolean gtShanhai$refundQuantumPush(IPatternDetails patternDetails, KeyCounter inputs) {
        if (patternDetails == null || inputs == null || inputs.isEmpty()) {
            return false;
        }
        Integer slotIndex = getSlotIndexForPattern(patternDetails);
        if (slotIndex == null || slotIndex < 0 || slotIndex >= getInternalSlotCount()) {
            return false;
        }
        Object slot = gtShanhai$getInternalSlot(slotIndex);
        if (slot == null) {
            return false;
        }
        Object2LongOpenHashMap<AEItemKey> itemInventory = gtShanhai$getItemInventory(slot);
        Object2LongOpenHashMap<AEFluidKey> fluidInventory = gtShanhai$getFluidInventory(slot);
        if (itemInventory == null || fluidInventory == null) {
            return false;
        }

        gtShanhai$stripVirtualTargetsFromCatalyst(itemInventory, fluidInventory);
        VirtualPatternBufferSlotState.stripVirtualTargets(itemInventory);
        VirtualPatternBufferSlotState.stripVirtualTargets(fluidInventory);
        boolean didRefund = false;
        for (Object2LongMap.Entry<AEKey> entry : inputs) {
            long amount = entry.getLongValue();
            if (amount <= 0) {
                continue;
            }
            AEKey key = entry.getKey();
            long refunded = 0;
            if (key instanceof AEItemKey itemKey) {
                refunded = gtShanhai$moveToRefundBuffer(itemInventory, itemKey, amount);
            } else if (key instanceof AEFluidKey fluidKey) {
                refunded = gtShanhai$moveToRefundBuffer(fluidInventory, fluidKey, amount);
            }
            if (refunded > 0) {
                buffer.addTo(key, refunded);
                didRefund = true;
            }
        }
        if (didRefund) {
            gtShanhai$notifySlotChanged(slot);
            gtShanhai$notifySelfIO();
        }
        return didRefund;
    }

    @Override
    public boolean gtShanhai$stripVirtualTargetsInSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= getInternalSlotCount()) {
            return false;
        }
        Object slot = gtShanhai$getInternalSlot(slotIndex);
        if (!(slot instanceof VirtualPatternBufferSlotAccess access)) {
            return false;
        }
        access.gtShanhai$stripVirtualTargets();
        gtShanhai$notifySlotChanged(slot);
        gtShanhai$notifySelfIO();
        return true;
    }

    private <T extends AEKey> long gtShanhai$moveToRefundBuffer(Object2LongOpenHashMap<T> inventory, T key,
            long requested) {
        long current = inventory.getLong(key);
        long refunded = Math.min(current, requested);
        if (refunded <= 0) {
            return 0;
        }
        long remaining = current - refunded;
        if (remaining > 0) {
            inventory.put(key, remaining);
        } else {
            inventory.removeLong(key);
        }
        return refunded;
    }

    @SuppressWarnings("unchecked")
    private Object2LongOpenHashMap<AEItemKey> gtShanhai$getItemInventory(Object slot) {
        try {
            Method method = gtShanhai$findMethod(slot.getClass(), "getItemInventory");
            Object inventory = method.invoke(slot);
            if (inventory instanceof Object2LongOpenHashMap<?>) {
                return (Object2LongOpenHashMap<AEItemKey>) inventory;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object2LongOpenHashMap<AEFluidKey> gtShanhai$getFluidInventory(Object slot) {
        try {
            Method method = gtShanhai$findMethod(slot.getClass(), "getFluidInventory");
            Object inventory = method.invoke(slot);
            if (inventory instanceof Object2LongOpenHashMap<?>) {
                return (Object2LongOpenHashMap<AEFluidKey>) inventory;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private void gtShanhai$notifySelfIO() {
        try {
            Method getTrait = gtShanhai$findMethod(this.getClass(), "getMETrait");
            Object trait = getTrait.invoke(this);
            if (trait == null) {
                return;
            }
            Method notify = gtShanhai$findMethod(trait.getClass(), "notifySelfIO");
            notify.invoke(trait);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private Level gtShanhai$getLevel() {
        try {
            Method method = gtShanhai$findMethod(this.getClass(), "getLevel");
            Object level = method.invoke(this);
            if (level instanceof Level levelValue) {
                return levelValue;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private BlockPos gtShanhai$getPos() {
        try {
            Method method = gtShanhai$findMethod(this.getClass(), "getPos");
            Object pos = method.invoke(this);
            if (pos instanceof BlockPos blockPos) {
                return blockPos;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private IManagedGridNode gtShanhai$getMainNode() {
        try {
            Method method = gtShanhai$findMethod(this.getClass(), "getMainNode");
            Object node = method.invoke(this);
            if (node instanceof IManagedGridNode managedGridNode) {
                return managedGridNode;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private Object gtShanhai$getInternalSlot(int slotIndex) {
        try {
            Method method = MEPatternBufferPartMachineBase.class.getDeclaredMethod("getInternalSlot", int.class);
            method.setAccessible(true);
            return method.invoke(this, slotIndex);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private void gtShanhai$addToSlot(Object slot, AEKey what, long amount) {
        try {
            Method method = gtShanhai$findMethod(slot.getClass(), "add", AEKey.class, long.class);
            method.invoke(slot, what, amount);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void gtShanhai$addVirtualTargetToSlot(Object slot, AEKey what, long amount) {
        gtShanhai$addToSlot(slot, what, amount);
        if (what instanceof AEItemKey itemKey) {
            Object2LongOpenHashMap<AEItemKey> itemInventory = gtShanhai$getItemInventory(slot);
            if (itemInventory != null) {
                VirtualPatternBufferSlotState.addVirtualTarget(itemInventory, itemKey, amount);
                VirtualPatternBufferSlotState.copyVirtualTargets(itemInventory, gtShanhai$getItemCatalystInventory(slot));
                gtShanhai$cacheVirtualCircuit(slot, itemKey);
            }
        } else if (what instanceof AEFluidKey fluidKey) {
            Object2LongOpenHashMap<AEFluidKey> fluidInventory = gtShanhai$getFluidInventory(slot);
            if (fluidInventory != null) {
                VirtualPatternBufferSlotState.addVirtualTarget(fluidInventory, fluidKey, amount);
                VirtualPatternBufferSlotState.copyVirtualTargets(fluidInventory, gtShanhai$getFluidCatalystInventory(slot));
            }
        }
    }

    private void gtShanhai$cacheVirtualCircuit(Object slot, AEItemKey itemKey) {
        ItemStack stack = itemKey.toStack();
        if (!IntCircuitBehaviour.isIntegratedCircuit(stack)) {
            return;
        }
        int config = IntCircuitBehaviour.getCircuitConfiguration(stack);
        if (config < 0 || config > IntCircuitBehaviour.CIRCUIT_MAX) {
            return;
        }
        SlotCacheManager cacheManager = gtShanhai$getCacheManager(slot);
        if (cacheManager instanceof SlotCacheManagerAccessor accessor) {
            accessor.gtShanhai$setCircuitCacheRaw(config);
            accessor.gtShanhai$setCircuitStackRaw(IntCircuitBehaviour.stack(config));
            Object2LongOpenHashMap<AEItemKey> itemInventory = gtShanhai$getItemInventory(slot);
            if (itemInventory != null) {
                VirtualPatternBufferSlotState.setVirtualCircuit(itemInventory, config);
            }
        }
    }

    private SlotCacheManager gtShanhai$getCacheManager(Object slot) {
        try {
            Method method = gtShanhai$findMethod(slot.getClass(), "getCacheManager");
            Object cacheManager = method.invoke(slot);
            if (cacheManager instanceof SlotCacheManager manager) {
                return manager;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private void gtShanhai$stripVirtualTargetsFromCatalyst(Object2LongOpenHashMap<AEItemKey> itemInventory,
            Object2LongOpenHashMap<AEFluidKey> fluidInventory) {
        Object slot = gtShanhai$findSlotByInventories(itemInventory, fluidInventory);
        if (slot != null) {
            VirtualPatternBufferSlotState.removeVirtualTargets(itemInventory, gtShanhai$getItemCatalystInventory(slot));
            VirtualPatternBufferSlotState.removeVirtualTargets(fluidInventory, gtShanhai$getFluidCatalystInventory(slot));
            gtShanhai$clearVirtualCircuitCache(slot, itemInventory);
        }
    }

    private void gtShanhai$clearVirtualCircuitCache(Object slot, Object2LongOpenHashMap<AEItemKey> itemInventory) {
        if (VirtualPatternBufferSlotState.getVirtualCircuit(itemInventory) < 0) {
            return;
        }
        SlotCacheManager cacheManager = gtShanhai$getCacheManager(slot);
        if (cacheManager instanceof SlotCacheManagerAccessor accessor) {
            accessor.gtShanhai$setCircuitCacheRaw(-1);
            accessor.gtShanhai$setCircuitStackRaw(ItemStack.EMPTY);
        }
        VirtualPatternBufferSlotState.clearVirtualCircuit(itemInventory);
    }

    @SuppressWarnings("unchecked")
    private Object2LongMap<AEItemKey> gtShanhai$getItemCatalystInventory(Object slot) {
        try {
            Method method = gtShanhai$findMethod(slot.getClass(), "getItemCatalystInventory");
            Object inventory = method.invoke(slot);
            if (inventory instanceof Object2LongMap<?>) {
                return (Object2LongMap<AEItemKey>) inventory;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object2LongMap<AEFluidKey> gtShanhai$getFluidCatalystInventory(Object slot) {
        try {
            Method method = gtShanhai$findMethod(slot.getClass(), "getFluidCatalystInventory");
            Object inventory = method.invoke(slot);
            if (inventory instanceof Object2LongMap<?>) {
                return (Object2LongMap<AEFluidKey>) inventory;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private Object gtShanhai$findSlotByInventories(Object2LongOpenHashMap<AEItemKey> itemInventory,
            Object2LongOpenHashMap<AEFluidKey> fluidInventory) {
        for (int i = 0; i < getInternalSlotCount(); i++) {
            Object slot = gtShanhai$getInternalSlot(i);
            if (slot != null && gtShanhai$getItemInventory(slot) == itemInventory
                    && gtShanhai$getFluidInventory(slot) == fluidInventory) {
                return slot;
            }
        }
        return null;
    }

    private void gtShanhai$notifySlotChanged(Object slot) {
        try {
            Method method = gtShanhai$findMethod(slot.getClass(), "getOnContentsChanged");
            Object callback = method.invoke(slot);
            if (callback instanceof Runnable runnable) {
                runnable.run();
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private Method gtShanhai$findMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }
}
