package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferMachineAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotState;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.world.item.ItemStack;

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
public abstract class GTLCoreMEPatternBufferVirtualProviderMixin implements VirtualPatternBufferMachineAccess {

    @Shadow
    protected Object2LongOpenHashMap<AEKey> buffer;

    @Shadow
    protected abstract Integer getSlotIndexForPattern(IPatternDetails patternDetails);

    @Shadow
    protected abstract int getInternalSlotCount();

    @Override
    public void gtShanhai$restoreVirtualTargetsFromPatterns(Iterable<IPatternDetails> patterns) {
        if (patterns == null) return;
        for (IPatternDetails details : patterns) {
            if (!VirtualPatternEncodingHelper.containsVirtualProviderPattern(details)) continue;
            Integer slotIndex = getSlotIndexForPattern(details);
            if (slotIndex == null || slotIndex < 0 || slotIndex >= getInternalSlotCount()) continue;
            Object slot = gtShanhai$getInternalSlot(slotIndex);
            if (!(slot instanceof VirtualPatternBufferSlotAccess access)) continue;
            for (IPatternDetails.IInput input : details.getInputs()) {
                if (!VirtualPatternEncodingHelper.isPresenceInput(input)) continue;
                GenericStack[] possible = input.getPossibleInputs();
                if (possible != null && possible.length > 0 && possible[0] != null) {
                    AEKey key = possible[0].what();
                    if (!access.gtShanhai$hasVirtualTarget(key)) {
                        access.gtShanhai$restoreVirtualTarget(key, Long.MAX_VALUE);
                    }
                }
            }
            access.gtShanhai$syncVirtualTargetsToCatalyst();
        }
    }

    @Inject(method = "pushPattern", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$pushVirtualProviderPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder,
            CallbackInfoReturnable<Boolean> cir) {
        if (!VirtualPatternEncodingHelper.containsVirtualProviderPattern(patternDetails)) {
            QuantumDiagnostics.hit("patternBuffer.pushPattern.notVirtual",
                    "machine=" + gtShanhai$describeSelf() + " pattern=" + patternDetails);
            return;
        }
        Integer slotIndex = getSlotIndexForPattern(patternDetails);
        IManagedGridNode mainNode = gtShanhai$getMainNode();
        if (mainNode == null || !mainNode.isActive() || slotIndex == null || slotIndex < 0
                || slotIndex >= getInternalSlotCount()) {
            QuantumDiagnostics.hit("patternBuffer.pushPattern.rejected",
                    "machine=" + gtShanhai$describeSelf() + " mainNodeNull=" + (mainNode == null)
                            + " mainNodeActive=" + (mainNode != null && mainNode.isActive())
                            + " slotIndex=" + slotIndex + " slotCount=" + getInternalSlotCount()
                            + " pattern=" + patternDetails);
            cir.setReturnValue(false);
            return;
        }

        Object slot = gtShanhai$getInternalSlot(slotIndex);
        if (slot == null) {
            QuantumDiagnostics.hit("patternBuffer.pushPattern.slotNull",
                    "machine=" + gtShanhai$describeSelf() + " slotIndex=" + slotIndex);
            cir.setReturnValue(false);
            return;
        }
        try {
            VirtualPatternEncodingHelper.pushPatternInputsIncludingVirtual(patternDetails, inputHolder,
                    (what, amount) -> gtShanhai$addToSlot(slot, what, amount),
                    (what, amount) -> gtShanhai$addVirtualTargetToSlot(slot, what, amount));
        } catch (RuntimeException e) {
            QuantumDiagnostics.hit("patternBuffer.pushPattern.throw",
                    "machine=" + gtShanhai$describeSelf() + " slotIndex=" + slotIndex
                            + " pattern=" + patternDetails + " error=" + e);
            cir.setReturnValue(false);
            return;
        }
        gtShanhai$notifySlotChanged(slot);
        QuantumDiagnostics.hit("patternBuffer.pushPattern.success",
                "machine=" + gtShanhai$describeSelf() + " slotIndex=" + slotIndex + " pattern=" + patternDetails);
        cir.setReturnValue(true);
    }

    private String gtShanhai$describeSelf() {
        try {
            return String.valueOf(((Object) this).getClass().getSimpleName()) + "@"
                    + Integer.toHexString(System.identityHashCode(this));
        } catch (RuntimeException ignored) {
            return "?";
        }
    }

    @Inject(method = "refundSlot", at = @At("HEAD"), remap = false)
    private void gtShanhai$stripVirtualTargetsBeforeRefund(Object2LongOpenHashMap<AEItemKey> itemInventory,
            Object2LongOpenHashMap<AEFluidKey> fluidInventory, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        gtShanhai$stripVirtualTargetsFromCatalyst(itemInventory, fluidInventory);
        VirtualPatternBufferSlotState.stripVirtualTargets(itemInventory);
        VirtualPatternBufferSlotState.stripVirtualTargets(fluidInventory);
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
