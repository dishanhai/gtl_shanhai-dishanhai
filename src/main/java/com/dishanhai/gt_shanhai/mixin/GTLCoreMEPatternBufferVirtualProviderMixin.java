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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

@Mixin(value = MEPatternBufferPartMachineBase.class, remap = false)
public abstract class GTLCoreMEPatternBufferVirtualProviderMixin implements VirtualPatternBufferMachineAccess {

    @Shadow
    protected Object2LongOpenHashMap<AEKey> buffer;

    @Shadow
    protected abstract Integer getSlotIndexForPattern(IPatternDetails patternDetails);

    @Shadow
    protected abstract int getInternalSlotCount();

    @Unique
    private final Map<Object2LongOpenHashMap<AEItemKey>, Object>
            gtShanhai$slotByItemInventory = new IdentityHashMap<>();

    @Unique
    private final Map<Object2LongOpenHashMap<AEFluidKey>, Object>
            gtShanhai$slotByFluidInventory = new IdentityHashMap<>();

    @Unique
    private Method gtShanhai$getInternalSlotMethod;

    @Unique
    private Method gtShanhai$getMainNodeMethod;

    @Unique
    private Method gtShanhai$getMETraitMethod;

    @Unique
    private Method gtShanhai$notifySelfIOMethod;

    @Unique
    private boolean gtShanhai$reflectionWarned;

    /**
     * 机器级反射兜底失败时的一次性警告。槽位级访问已全部改走
     * {@link VirtualPatternBufferSlotAccess} 桥接接口，不再有静默吞异常的问题；
     * 剩余的机器级反射（getInternalSlot/getMainNode/getMETrait）失败必须可见。
     */
    @Unique
    private void gtShanhai$warnReflectionFailure(String site, ReflectiveOperationException e) {
        if (gtShanhai$reflectionWarned) return;
        gtShanhai$reflectionWarned = true;
        com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.warn(
                "[VirtualProvider] 反射调用 {} 失败，虚拟供料链路可能失效（GTLCore 版本变动？）", site, e);
    }

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
            access.gtShanhai$clearVirtualTargetsIfDepleted();
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
        if (!(slot instanceof VirtualPatternBufferSlotAccess access)) {
            // 槽位为 null 或槽位 mixin 未生效：宁可下单失败，也不能让配料被静默吃掉
            QuantumDiagnostics.hit("patternBuffer.pushPattern.slotNull",
                    "machine=" + gtShanhai$describeSelf() + " slotIndex=" + slotIndex
                            + " slotClass=" + (slot == null ? "null" : slot.getClass().getName()));
            cir.setReturnValue(false);
            return;
        }
        try {
            VirtualPatternEncodingHelper.pushPatternInputsIncludingVirtual(patternDetails, inputHolder,
                    access::gtShanhai$add,
                    (what, amount) -> gtShanhai$addVirtualTargetToSlot(access, what, amount));
        } catch (RuntimeException e) {
            QuantumDiagnostics.hit("patternBuffer.pushPattern.throw",
                    "machine=" + gtShanhai$describeSelf() + " slotIndex=" + slotIndex
                            + " pattern=" + patternDetails + " error=" + e);
            cir.setReturnValue(false);
            return;
        }
        gtShanhai$notifySlotChanged(access);
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
        if (!gtShanhai$hasVirtualRefundState(itemInventory, fluidInventory)) return;
        gtShanhai$stripVirtualTargetsFromCatalyst(itemInventory, fluidInventory);
        VirtualPatternBufferSlotState.stripVirtualTargets(itemInventory);
        VirtualPatternBufferSlotState.stripVirtualTargets(fluidInventory);
    }

    @Unique
    private boolean gtShanhai$hasVirtualRefundState(Object2LongOpenHashMap<AEItemKey> itemInventory,
            Object2LongOpenHashMap<AEFluidKey> fluidInventory) {
        return VirtualPatternBufferSlotState.hasVirtualTargets(itemInventory)
                || VirtualPatternBufferSlotState.hasVirtualTargets(fluidInventory)
                || VirtualPatternBufferSlotState.getVirtualCircuit(itemInventory) >= 0;
    }

    @Override
    public void gtShanhai$indexRefundSlot(Object slot, Object2LongOpenHashMap<AEItemKey> itemInventory,
            Object2LongOpenHashMap<AEFluidKey> fluidInventory) {
        if (slot == null) return;
        if (itemInventory != null) gtShanhai$slotByItemInventory.put(itemInventory, slot);
        if (fluidInventory != null) gtShanhai$slotByFluidInventory.put(fluidInventory, slot);
    }

    @Override
    public void gtShanhai$invalidateRefundSlotIndex() {
        gtShanhai$slotByItemInventory.clear();
        gtShanhai$slotByFluidInventory.clear();
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
        gtShanhai$notifySlotChanged(access);
        gtShanhai$notifySelfIO();
        return true;
    }

    @Override
    public boolean gtShanhai$addVirtualTargetToSlot(int slotIndex, AEKey key, long amount) {
        if (slotIndex < 0 || slotIndex >= getInternalSlotCount() || key == null || amount <= 0) {
            return false;
        }
        Object slot = gtShanhai$getInternalSlot(slotIndex);
        if (!(slot instanceof VirtualPatternBufferSlotAccess access)) return false;
        if (access.gtShanhai$hasVirtualTarget(key)) {
            access.gtShanhai$syncVirtualTargetsToCatalyst();
            if (key instanceof AEItemKey itemKey) gtShanhai$cacheVirtualCircuit(access, itemKey);
            gtShanhai$notifySlotChanged(access);
            gtShanhai$notifySelfIO();
            return true;
        }

        access.gtShanhai$restoreVirtualTarget(key, Long.MAX_VALUE);
        if (!access.gtShanhai$hasVirtualTarget(key)) {
            gtShanhai$addVirtualTargetToSlot(access, key, amount);
        } else {
            access.gtShanhai$syncVirtualTargetsToCatalyst();
            if (key instanceof AEItemKey itemKey) gtShanhai$cacheVirtualCircuit(access, itemKey);
        }
        gtShanhai$notifySlotChanged(access);
        gtShanhai$notifySelfIO();
        return access.gtShanhai$hasVirtualTarget(key);
    }

    private void gtShanhai$notifySelfIO() {
        try {
            Method getTrait = gtShanhai$getMETraitMethod;
            if (getTrait == null) {
                getTrait = gtShanhai$findMethod(this.getClass(), "getMETrait");
                gtShanhai$getMETraitMethod = getTrait;
            }
            Object trait = getTrait.invoke(this);
            if (trait == null) {
                return;
            }
            Method notify = gtShanhai$notifySelfIOMethod;
            if (notify == null) {
                notify = gtShanhai$findMethod(trait.getClass(), "notifySelfIO");
                gtShanhai$notifySelfIOMethod = notify;
            }
            notify.invoke(trait);
        } catch (ReflectiveOperationException e) {
            gtShanhai$warnReflectionFailure("notifySelfIO", e);
        }
    }

    private IManagedGridNode gtShanhai$getMainNode() {
        try {
            Method method = gtShanhai$getMainNodeMethod;
            if (method == null) {
                method = gtShanhai$findMethod(this.getClass(), "getMainNode");
                gtShanhai$getMainNodeMethod = method;
            }
            Object node = method.invoke(this);
            if (node instanceof IManagedGridNode managedGridNode) {
                return managedGridNode;
            }
        } catch (ReflectiveOperationException e) {
            gtShanhai$warnReflectionFailure("getMainNode", e);
        }
        return null;
    }

    private Object gtShanhai$getInternalSlot(int slotIndex) {
        try {
            Method method = gtShanhai$getInternalSlotMethod;
            if (method == null) {
                method = MEPatternBufferPartMachineBase.class.getDeclaredMethod("getInternalSlot", int.class);
                method.setAccessible(true);
                gtShanhai$getInternalSlotMethod = method;
            }
            return method.invoke(this, slotIndex);
        } catch (ReflectiveOperationException e) {
            gtShanhai$warnReflectionFailure("getInternalSlot", e);
            return null;
        }
    }

    private void gtShanhai$addVirtualTargetToSlot(VirtualPatternBufferSlotAccess access, AEKey what, long amount) {
        access.gtShanhai$add(what, amount);
        if (what instanceof AEItemKey itemKey) {
            Object2LongOpenHashMap<AEItemKey> itemInventory = access.gtShanhai$itemInventory();
            if (itemInventory != null) {
                VirtualPatternBufferSlotState.addVirtualTarget(itemInventory, itemKey, amount);
                VirtualPatternBufferSlotState.copyVirtualTargets(itemInventory, access.gtShanhai$itemCatalystInventory());
                gtShanhai$cacheVirtualCircuit(access, itemKey);
            }
        } else if (what instanceof AEFluidKey fluidKey) {
            Object2LongOpenHashMap<AEFluidKey> fluidInventory = access.gtShanhai$fluidInventory();
            if (fluidInventory != null) {
                VirtualPatternBufferSlotState.addVirtualTarget(fluidInventory, fluidKey, amount);
                VirtualPatternBufferSlotState.copyVirtualTargets(fluidInventory, access.gtShanhai$fluidCatalystInventory());
            }
        }
    }

    private void gtShanhai$cacheVirtualCircuit(VirtualPatternBufferSlotAccess access, AEItemKey itemKey) {
        ItemStack stack = itemKey.toStack();
        if (!IntCircuitBehaviour.isIntegratedCircuit(stack)) {
            return;
        }
        int config = IntCircuitBehaviour.getCircuitConfiguration(stack);
        if (config < 0 || config > IntCircuitBehaviour.CIRCUIT_MAX) {
            return;
        }
        SlotCacheManager cacheManager = access.gtShanhai$cacheManager();
        if (cacheManager instanceof SlotCacheManagerAccessor accessor) {
            accessor.gtShanhai$setCircuitCacheRaw(config);
            accessor.gtShanhai$setCircuitStackRaw(IntCircuitBehaviour.stack(config));
            Object2LongOpenHashMap<AEItemKey> itemInventory = access.gtShanhai$itemInventory();
            if (itemInventory != null) {
                VirtualPatternBufferSlotState.setVirtualCircuit(itemInventory, config);
            }
        }
    }

    private void gtShanhai$stripVirtualTargetsFromCatalyst(Object2LongOpenHashMap<AEItemKey> itemInventory,
            Object2LongOpenHashMap<AEFluidKey> fluidInventory) {
        if (gtShanhai$findSlotByInventories(itemInventory, fluidInventory)
                instanceof VirtualPatternBufferSlotAccess access) {
            VirtualPatternBufferSlotState.removeVirtualTargets(itemInventory, access.gtShanhai$itemCatalystInventory());
            VirtualPatternBufferSlotState.removeVirtualTargets(fluidInventory, access.gtShanhai$fluidCatalystInventory());
            gtShanhai$clearVirtualCircuitCache(access, itemInventory);
        }
    }

    private void gtShanhai$clearVirtualCircuitCache(VirtualPatternBufferSlotAccess access,
            Object2LongOpenHashMap<AEItemKey> itemInventory) {
        int config = VirtualPatternBufferSlotState.getVirtualCircuit(itemInventory);
        if (config < 0) {
            return;
        }
        gtShanhai$removeVirtualCircuitPresence(access, itemInventory, config);
        SlotCacheManager cacheManager = access.gtShanhai$cacheManager();
        if (cacheManager instanceof SlotCacheManagerAccessor accessor) {
            accessor.gtShanhai$setCircuitCacheRaw(-1);
            accessor.gtShanhai$setCircuitStackRaw(ItemStack.EMPTY);
        }
        VirtualPatternBufferSlotState.clearVirtualCircuit(itemInventory);
    }

    private void gtShanhai$removeVirtualCircuitPresence(VirtualPatternBufferSlotAccess access,
            Object2LongOpenHashMap<AEItemKey> itemInventory, int config) {
        if (config < 0 || config > IntCircuitBehaviour.CIRCUIT_MAX) return;
        AEItemKey circuitKey = AEItemKey.of(IntCircuitBehaviour.stack(config));
        gtShanhai$subtractAmount(itemInventory, circuitKey, 1L);
        gtShanhai$subtractAmount(access.gtShanhai$itemCatalystInventory(), circuitKey, 1L);
    }

    private static <T> void gtShanhai$subtractAmount(Object2LongMap<T> inventory, T key, long amount) {
        if (inventory == null || key == null || amount <= 0L) return;
        long remaining = inventory.getLong(key) - amount;
        if (remaining > 0L) {
            inventory.put(key, remaining);
        } else {
            inventory.removeLong(key);
        }
    }

    private Object gtShanhai$findSlotByInventories(Object2LongOpenHashMap<AEItemKey> itemInventory,
            Object2LongOpenHashMap<AEFluidKey> fluidInventory) {
        Object slot = gtShanhai$slotByItemInventory.get(itemInventory);
        if (slot != null && gtShanhai$slotByFluidInventory.get(fluidInventory) == slot) {
            return slot;
        }
        gtShanhai$rebuildSlotInventoryIndex();
        slot = gtShanhai$slotByItemInventory.get(itemInventory);
        return slot != null && gtShanhai$slotByFluidInventory.get(fluidInventory) == slot ? slot : null;
    }

    @Unique
    private void gtShanhai$rebuildSlotInventoryIndex() {
        gtShanhai$slotByItemInventory.clear();
        gtShanhai$slotByFluidInventory.clear();
        for (int i = 0; i < getInternalSlotCount(); i++) {
            Object slot = gtShanhai$getInternalSlot(i);
            if (!(slot instanceof VirtualPatternBufferSlotAccess access)) continue;
            gtShanhai$indexRefundSlot(slot, access.gtShanhai$itemInventory(), access.gtShanhai$fluidInventory());
        }
    }

    private void gtShanhai$notifySlotChanged(VirtualPatternBufferSlotAccess access) {
        access.gtShanhai$notifyContentsChanged();
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
