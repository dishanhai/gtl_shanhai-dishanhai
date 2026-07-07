package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiNBTAPI;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem;
import com.dishanhai.gt_shanhai.common.item.VirtualCellStorage;
import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.dishanhai.gt_shanhai.mixin.KeyCounterAccessor;

import com.gregtechceu.gtceu.api.capability.recipe.IO;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.Set;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;

import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import appeng.api.config.Actionable;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.registries.ForgeRegistries;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER;
import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class MEDiskHatchPartMachine extends MultiblockPartMachine
        implements IInteractedMachine, IFancyUIMachine, DShanhaiAENetworkMachine, IStorageProvider, ISaveProvider {

    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MEDiskHatchPartMachine.class, MultiblockPartMachine.MANAGED_FIELD_HOLDER);
    public static final int DEFAULT_DISK_SLOT_COUNT = 108;

    @Persisted
    @DescSynced
    public final NotifiableItemStackHandler diskSlots;

    @Persisted
    private final GridNodeHolder nodeHolder;

    @DescSynced
    private boolean isOnline;

    /** 每槽虚拟磁盘缓存 — flushDirty 时不调 onContentsChanged，避免递归 */
    private transient Map<Integer, List<VirtualCellStorage>> cachedVirtualCells;

    public MEDiskHatchPartMachine(IMachineBlockEntity holder) {
        super(holder);
        int slots = DEFAULT_DISK_SLOT_COUNT;
        try {
            slots = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.meDiskHatchSlots.get();
        } catch (Throwable ignored) {}
        this.diskSlots = new NotifiableItemStackHandler(this, slots, IO.BOTH);
        this.diskSlots.storage.setOnContentsChanged(() -> {
            if (!isRemote()) {
                markDirty();
                IStorageProvider.requestUpdate(getMainNode());
            }
        });
        this.nodeHolder = new GridNodeHolder(this);
        exposeGridNodeOnAllSides();
        getMainNode().addService(IStorageProvider.class, this);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MEH init traits={}, diskSlots.storage.slots={}, nodeHolder={}",
                getTraits().size(), diskSlots.getSlots(), nodeHolder != null ? "ok" : "null");
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        exposeGridNodeOnAllSides();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MEH onLoad server={}, diskSlots[0]={}, nodeOnline={}",
                !isRemote(), diskSlots.getStackInSlot(0), getMainNode().isOnline());
        }
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ==================== ISaveProvider ====================

    @Override
    public void saveChanges() {
        flushDirtyVirtualCells();
        markDirty();
    }

    // ==================== IGridConnectedMachine ====================

    @Override
    public IManagedGridNode getMainNode() {
        return nodeHolder.getMainNode();
    }

    private void exposeGridNodeOnAllSides() {
        getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
    }

    @Override
    public boolean isOnline() {
        return isOnline;
    }

    @Override
    public void setOnline(boolean online) {
        this.isOnline = online;
    }

    @Override
    public String getAeJadeKind() {
        return "ME 磁盘仓室";
    }

    @Override
    public int getAeTotalSlots() {
        return diskSlots.getSlots();
    }

    @Override
    public int getAeStockedSlots() {
        int count = 0;
        for (int i = 0; i < diskSlots.getSlots(); i++) {
            if (!diskSlots.getStackInSlot(i).isEmpty()) count++;
        }
        return count;
    }

    // ==================== IStorageProvider ====================

    @Override
    public void mountInventories(IStorageMounts storageMounts) {
        flushDirtyVirtualCells();
        int mounted = 0;
        int slots = diskSlots.getSlots();
        List<NestedCellStorage> nestedStorages = new java.util.ArrayList<>();
        List<MEStorage> pendingMounts = new java.util.ArrayList<>();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = diskSlots.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof SuperDiskArrayItem) {
                mounted += collectSdaMounts(stack, i, pendingMounts, nestedStorages);
            } else {
                StorageCell cell = StorageCells.getCellInventory(stack, null);
                if (cell != null) {
                    pendingMounts.add(new NormalizedFluidKeyStorage(new PersistedCellStorage(cell, this)));
                    mounted++;
                }
            }
        }
        if (!nestedStorages.isEmpty()) {
            storageMounts.mount(new AggregatedNestedCellStorage(nestedStorages), 10);
            mounted++;
        }
        for (MEStorage storage : pendingMounts) {
            storageMounts.mount(storage, 10);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MEH mountInventories → {} cells, online={}, nodeOnline={}, nodeActive={}",
                mounted, isOnline, getMainNode().isOnline(), getMainNode().isActive());
        }
    }

    /**
     * 智能挂载 SDA：
     * 1. 遍历 SDA 内部物品，对每个有子 cell inventory 的（如 infinity_cell）挂载其子盘内容
     * 2. SDA 自身 inventory 用 FilteredMEStorage 过滤掉子载体物品
     * 3. 额外挂载 virtual_cells NBT 定义的虚拟磁盘
     */
    private int collectSdaMounts(ItemStack stack, int slot, List<MEStorage> pendingMounts,
                                 List<NestedCellStorage> nestedStorages) {
        StorageCell sdaCell = StorageCells.getCellInventory(stack, null);
        if (sdaCell == null) return 0;
        int count = 0;

        // 收集 SDA 内的子载体（infinity_cell 等）
        KeyCounter contents = new KeyCounter();
        sdaCell.getAvailableStacks(contents);
        Set<AEKey> carrierKeys = new HashSet<>();
        CarrierKeyFilterState carrierFilterState = new CarrierKeyFilterState(carrierKeys);
        for (var entry : contents) {
            AEKey key = entry.getKey();
            if (key instanceof AEItemKey itemKey) {
                ItemStack innerStack = itemKey.toStack();
                normalizeEaeInfinityCellRecord(innerStack);
                MEStorage directInfinityCell = createEaeInfinityCellStorage(innerStack);
                if (directInfinityCell != null) {
                    carrierFilterState.add(key);
                    pendingMounts.add(directInfinityCell);
                    count++;
                    continue;
                }
                StorageCell subCell = StorageCells.getCellInventory(innerStack, null);
                if (subCell != null) {
                    carrierFilterState.add(key);
                    nestedStorages.add(new NestedCellStorage(sdaCell,
                            new NormalizedStorageCell(subCell), key, innerStack, carrierFilterState, this));
                }
            }
        }

        // 挂载 SDA（过滤掉子载体物品）
        MEStorage mountedSda = new PersistedCellStorage(sdaCell, this);
        if (carrierKeys.isEmpty()) {
            pendingMounts.add(new NormalizedFluidKeyStorage(mountedSda));
        } else {
            pendingMounts.add(new NormalizedFluidKeyStorage(new FilteredMEStorage(mountedSda, carrierFilterState)));
        }
        count++;

        // 挂载 virtual_cells NBT 定义的虚拟磁盘
        List<VirtualCellStorage> vcsList = cachedVirtualCells != null ? cachedVirtualCells.get(slot) : null;
        if (vcsList == null) {
            vcsList = SuperDiskArrayItem.readVirtualCells(stack, getLevel());
            if (!vcsList.isEmpty()) {
                if (cachedVirtualCells == null) cachedVirtualCells = new HashMap<>();
                cachedVirtualCells.put(slot, vcsList);
            }
        }
        for (var vcs : vcsList) {
            pendingMounts.add(new NormalizedFluidKeyStorage(vcs));
            count++;
        }
        return count;
    }

    public static void normalizeEaeInfinityCellRecord(ItemStack stack) {
        if (stack.isEmpty()) return;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null || !"expatternprovider:infinity_cell".equals(itemId.toString())) return;
        CompoundTag tag = stack.getTag();
        DShanhaiNBTAPI.normalizeEaeInfinityCellRecord(tag);
    }

    public static MEStorage createEaeInfinityCellStorage(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null || !"expatternprovider:infinity_cell".equals(itemId.toString())) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("record", net.minecraft.nbt.Tag.TAG_COMPOUND)) return null;
        AEKey record = AEKey.fromTagGeneric(tag.getCompound("record"));
        if (record == null) return null;
        return new EaeInfinityCellStorage(normalizeFluidKey(record), stack.getHoverName());
    }

    public static final class EaeInfinityCellStorage implements MEStorage {
        private final AEKey record;
        private final Component description;

        private EaeInfinityCellStorage(AEKey record, Component description) {
            this.record = record;
            this.description = description;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            return amount > 0 && record.equals(normalizeFluidKey(what)) ? amount : 0L;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            return amount > 0 && record.equals(normalizeFluidKey(what)) ? amount : 0L;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            out.set(record, Long.MAX_VALUE);
        }

        @Override
        public Component getDescription() {
            return description;
        }
    }

    public static final class CarrierKeyFilterState {
        private final Set<AEKey> filtered;
        private int version;

        public CarrierKeyFilterState(Set<AEKey> filtered) {
            this.filtered = filtered;
        }

        private boolean contains(AEKey key) {
            return filtered.contains(key);
        }

        private Set<AEKey> keys() {
            return filtered;
        }

        private int version() {
            return version;
        }

        public void add(AEKey key) {
            if (filtered.add(key)) version++;
        }

        private void replace(AEKey oldKey, AEKey newKey) {
            boolean changed = filtered.remove(oldKey);
            changed |= filtered.add(newKey);
            if (changed) version++;
        }
    }

    private static final class KeyCounterSnapshot {
        private static final java.lang.invoke.MethodHandle VARIANT_COUNTER_COPY = findVariantCounterMethod("copy");
        private static final java.lang.invoke.MethodHandle VARIANT_COUNTER_ADD_ALL = findVariantCounterMethod("addAll");
        private static final java.lang.invoke.MethodHandle VARIANT_COUNTER_ADD = findVariantCounterMethod("add");
        private static final java.lang.invoke.MethodHandle VARIANT_COUNTER_GET_RECORDS = findVariantCounterMethod("getRecords");
        private static final sun.misc.Unsafe UNSAFE = findUnsafe();
        private static final Class<?> VARIANT_COUNTER_UNORDERED = findVariantCounterNestedClass("UnorderedVariantMap");
        private static final Class<?> VARIANT_COUNTER_FUZZY = findVariantCounterNestedClass("FuzzyVariantMap");
        private static final java.lang.invoke.MethodHandle VARIANT_COUNTER_UNORDERED_CTOR =
                findVariantCounterConstructor(VARIANT_COUNTER_UNORDERED);
        private static final java.lang.invoke.MethodHandle VARIANT_COUNTER_FUZZY_CTOR =
                findVariantCounterConstructor(VARIANT_COUNTER_FUZZY);
        private static final long VARIANT_COUNTER_UNORDERED_RECORDS_OFFSET = findFieldOffset(VARIANT_COUNTER_UNORDERED, "records");
        private static final long VARIANT_COUNTER_FUZZY_RECORDS_OFFSET = findFieldOffset(VARIANT_COUNTER_FUZZY, "records");
        private static final long OBJECT2LONG_OPEN_HASH_MAP_KEY_OFFSET =
                findFieldOffset(it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap.class, "key");
        private static final long OBJECT2LONG_OPEN_HASH_MAP_VALUE_OFFSET =
                findFieldOffset(it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap.class, "value");
        private static final long OBJECT2LONG_OPEN_HASH_MAP_MASK_OFFSET =
                findFieldOffset(it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap.class, "mask");
        private static final long OBJECT2LONG_OPEN_HASH_MAP_SIZE_OFFSET =
                findFieldOffset(it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap.class, "size");
        private static final long OBJECT2LONG_OPEN_HASH_MAP_MAX_FILL_OFFSET =
                findFieldOffset(it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap.class, "maxFill");
        private static final long OBJECT2LONG_OPEN_HASH_MAP_N_OFFSET =
                findFieldOffset(it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap.class, "n");
        private static final long OBJECT2LONG_OPEN_HASH_MAP_MIN_N_OFFSET =
                findFieldOffset(it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap.class, "minN");
        private static final long OBJECT2LONG_OPEN_HASH_MAP_CONTAINS_NULL_KEY_OFFSET =
                findFieldOffset(it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap.class, "containsNullKey");

        private final AEKey[] keys;
        private final long[] amounts;
        private final KeyCounter copiedCounter;

        private KeyCounterSnapshot(KeyCounter counter) {
            this(counter, true);
        }

        private KeyCounterSnapshot(KeyCounter counter, boolean preferCounterCopy) {
            this.copiedCounter = preferCounterCopy ? copyCounter(counter) : null;
            java.util.ArrayList<AEKey> keyList = new java.util.ArrayList<>(counter.size());
            it.unimi.dsi.fastutil.longs.LongArrayList amountList = new it.unimi.dsi.fastutil.longs.LongArrayList(counter.size());
            for (var entry : counter) {
                long amount = entry.getLongValue();
                if (amount <= 0) continue;
                keyList.add(entry.getKey());
                amountList.add(amount);
            }
            this.keys = keyList.toArray(new AEKey[0]);
            this.amounts = amountList.toLongArray();
        }

        private void addTo(KeyCounter out) {
            if (copiedCounter != null && addCounterInto(copiedCounter, out)) {
                return;
            }
            addFlatTo(out);
        }

        private void addToCopyNonConflicting(KeyCounter out) {
            if (copiedCounter != null && addCounterIntoCopyNonConflicting(copiedCounter, out)) {
                return;
            }
            addFlatTo(out);
        }

        private void addFlatTo(KeyCounter out) {
            // 优化路径：按 AEKeyType 分组批量插入，减少 submap 查询
            if (addFlatToBatched(out)) return;
            // 回退：逐个插入
            for (int i = 0; i < keys.length; i++) out.add(keys[i], amounts[i]);
        }

        private boolean addFlatToBatched(KeyCounter out) {
            try {
                var targetLists = ((KeyCounterAccessor) (Object) out).gtShanhai$getLists();
                // 按 keyType 分组
                java.util.Map<appeng.api.stacks.AEKeyType, java.util.List<Integer>> typeGroups = new java.util.HashMap<>();
                for (int i = 0; i < keys.length; i++) {
                    appeng.api.stacks.AEKeyType type = keys[i].getType();
                    typeGroups.computeIfAbsent(type, k -> new java.util.ArrayList<>()).add(i);
                }
                // 批量插入每个类型
                for (var entry : typeGroups.entrySet()) {
                    appeng.api.stacks.AEKeyType type = entry.getKey();
                    Object variant = targetLists.get(type);
                    if (variant == null) {
                        // 默认使用 unordered（精确匹配），AE2 大部分场景不需要 fuzzy
                        variant = newVariantCounter(false);
                        if (variant == null) return false;
                        targetLists.put(type, variant);
                    }
                    for (int idx : entry.getValue()) {
                        addVariantCounterEntry(variant, keys[idx], amounts[idx]);
                    }
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static KeyCounter copyCounter(KeyCounter source) {
            KeyCounter copy = new KeyCounter();
            if (copyCounterInto(source, copy)) {
                return copy;
            }
            return null;
        }

        private static boolean copyCounterInto(KeyCounter source, KeyCounter target) {
            try {
                var sourceLists = ((KeyCounterAccessor) (Object) source).gtShanhai$getLists();
                var targetLists = ((KeyCounterAccessor) (Object) target).gtShanhai$getLists();
                if (!target.isEmpty()) {
                    return false;
                }
                targetLists.clear();
                for (Object entryObject : sourceLists.reference2ObjectEntrySet()) {
                    var entry = (it.unimi.dsi.fastutil.objects.Reference2ObjectMap.Entry) entryObject;
                    targetLists.put(entry.getKey(), copyVariantCounter(entry.getValue()));
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean addCounterInto(KeyCounter source, KeyCounter target) {
            try {
                var sourceLists = ((KeyCounterAccessor) (Object) source).gtShanhai$getLists();
                var targetLists = ((KeyCounterAccessor) (Object) target).gtShanhai$getLists();
                if (sourceLists.isEmpty()) return true;
                if (targetLists.isEmpty() || target.isEmpty()) {
                    targetLists.clear();
                    for (Object entryObject : sourceLists.reference2ObjectEntrySet()) {
                        var entry = (it.unimi.dsi.fastutil.objects.Reference2ObjectMap.Entry) entryObject;
                        targetLists.put(entry.getKey(), copyVariantCounter(entry.getValue()));
                    }
                    return true;
                }
                for (Object entryObject : sourceLists.reference2ObjectEntrySet()) {
                    var entry = (it.unimi.dsi.fastutil.objects.Reference2ObjectMap.Entry) entryObject;
                    Object targetVariant = targetLists.get(entry.getKey());
                    if (targetVariant == null) {
                        targetLists.put(entry.getKey(), copyVariantCounter(entry.getValue()));
                    } else {
                        addVariantCounter(targetVariant, entry.getValue());
                    }
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean addCounterIntoCopyNonConflicting(KeyCounter source, KeyCounter target) {
            try {
                var sourceLists = ((KeyCounterAccessor) (Object) source).gtShanhai$getLists();
                var targetLists = ((KeyCounterAccessor) (Object) target).gtShanhai$getLists();
                if (sourceLists.isEmpty()) return true;
                if (targetLists.isEmpty() || target.isEmpty()) {
                    targetLists.clear();
                    for (Object entryObject : sourceLists.reference2ObjectEntrySet()) {
                        var entry = (it.unimi.dsi.fastutil.objects.Reference2ObjectMap.Entry) entryObject;
                        targetLists.put(entry.getKey(), copyVariantCounter(entry.getValue()));
                    }
                    return true;
                }
                for (Object entryObject : sourceLists.reference2ObjectEntrySet()) {
                    var entry = (it.unimi.dsi.fastutil.objects.Reference2ObjectMap.Entry) entryObject;
                    Object targetVariant = targetLists.get(entry.getKey());
                    if (targetVariant == null) {
                        targetLists.put(entry.getKey(), copyVariantCounter(entry.getValue()));
                    } else if (!addVariantCounterFlat(targetVariant, entry.getValue())) {
                        return false;
                    }
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean addVariantCounterFlat(Object targetVariant, Object sourceVariant) throws Throwable {
            Object sourceRecords = getVariantRecordsOrNull(sourceVariant);
            Object targetRecords = getVariantRecordsOrNull(targetVariant);
            if (sourceRecords instanceof it.unimi.dsi.fastutil.objects.Object2LongMap<?> sourceMap
                    && targetRecords instanceof it.unimi.dsi.fastutil.objects.Object2LongMap<?> targetMap) {
                addRecordMapFlat((it.unimi.dsi.fastutil.objects.Object2LongMap<AEKey>) targetMap,
                        (it.unimi.dsi.fastutil.objects.Object2LongMap<AEKey>) sourceMap);
                return true;
            }
            if (!(sourceVariant instanceof Iterable<?> entries)) return false;
            for (Object entryObject : entries) {
                var entry = (it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey>) entryObject;
                long amount = entry.getLongValue();
                if (amount > 0) addVariantCounterEntry(targetVariant, entry.getKey(), amount);
            }
            return true;
        }

        private static Object getVariantRecordsOrNull(Object counter) {
            try {
                if (UNSAFE != null) {
                    Class<?> type = counter.getClass();
                    if (type == VARIANT_COUNTER_UNORDERED && VARIANT_COUNTER_UNORDERED_RECORDS_OFFSET >= 0L) {
                        return UNSAFE.getObject(counter, VARIANT_COUNTER_UNORDERED_RECORDS_OFFSET);
                    }
                    if (type == VARIANT_COUNTER_FUZZY && VARIANT_COUNTER_FUZZY_RECORDS_OFFSET >= 0L) {
                        return UNSAFE.getObject(counter, VARIANT_COUNTER_FUZZY_RECORDS_OFFSET);
                    }
                }
                if (VARIANT_COUNTER_GET_RECORDS == null) return null;
                return VARIANT_COUNTER_GET_RECORDS.invokeExact(counter);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static void addRecordMapFlat(it.unimi.dsi.fastutil.objects.Object2LongMap<AEKey> target,
                                             it.unimi.dsi.fastutil.objects.Object2LongMap<AEKey> source) {
            var iterator = it.unimi.dsi.fastutil.objects.Object2LongMaps.fastIterator(source);
            while (iterator.hasNext()) {
                var entry = iterator.next();
                long amount = entry.getLongValue();
                if (amount > 0) addRecordAmount(target, entry.getKey(), amount);
            }
        }

        private static void addRecordAmount(it.unimi.dsi.fastutil.objects.Object2LongMap<AEKey> target,
                                            AEKey key, long amount) {
            if (addOpenHashMapAmount(target, key, amount)) return;
            long oldValue = target.getLong(key);
            long newValue = oldValue + amount;
            if (((oldValue ^ newValue) & (amount ^ newValue)) < 0) {
                newValue = Long.MAX_VALUE;
            }
            target.put(key, newValue);
        }

        private static boolean addOpenHashMapAmount(it.unimi.dsi.fastutil.objects.Object2LongMap<AEKey> target,
                                                    AEKey key, long amount) {
            if (!(target instanceof it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<?>)) return false;
            if (UNSAFE == null || key == null
                    || OBJECT2LONG_OPEN_HASH_MAP_KEY_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_VALUE_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_MASK_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_SIZE_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_MAX_FILL_OFFSET < 0L) {
                return false;
            }
            try {
                Object[] keys = (Object[]) UNSAFE.getObject(target, OBJECT2LONG_OPEN_HASH_MAP_KEY_OFFSET);
                long[] values = (long[]) UNSAFE.getObject(target, OBJECT2LONG_OPEN_HASH_MAP_VALUE_OFFSET);
                int mask = UNSAFE.getInt(target, OBJECT2LONG_OPEN_HASH_MAP_MASK_OFFSET);
                int pos = it.unimi.dsi.fastutil.HashCommon.mix(key.hashCode()) & mask;
                Object current = keys[pos];
                while (current != null) {
                    if (key.equals(current)) {
                        values[pos] = saturatedAddPositive(values[pos], amount);
                        return true;
                    }
                    pos = (pos + 1) & mask;
                    current = keys[pos];
                }
                int size = UNSAFE.getInt(target, OBJECT2LONG_OPEN_HASH_MAP_SIZE_OFFSET);
                int maxFill = UNSAFE.getInt(target, OBJECT2LONG_OPEN_HASH_MAP_MAX_FILL_OFFSET);
                if (size + 1 >= maxFill) return false;
                keys[pos] = key;
                values[pos] = amount;
                UNSAFE.putInt(target, OBJECT2LONG_OPEN_HASH_MAP_SIZE_OFFSET, size + 1);
                return true;
            } catch (Throwable ignored) {}
            return false;
        }

        private static long saturatedAddPositive(long oldValue, long amount) {
            long newValue = oldValue + amount;
            if (((oldValue ^ newValue) & (amount ^ newValue)) < 0) {
                return Long.MAX_VALUE;
            }
            return newValue;
        }

        private static Object copyVariantCounter(Object counter) throws Throwable {
            return VARIANT_COUNTER_COPY.invokeExact(counter);
        }

        private static Object copyVariantCounterOrNull(Object counter) {
            try {
                if (counter == null) return null;
                return copyVariantCounter(counter);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Object copyVariantCounterTemplateOrNull(Object template) {
            return copyVariantCounterOrNull(template);
        }

        private static Object newVariantCounter(boolean fuzzy) throws Throwable {
            java.lang.invoke.MethodHandle constructor = fuzzy ? VARIANT_COUNTER_FUZZY_CTOR : VARIANT_COUNTER_UNORDERED_CTOR;
            if (constructor == null) return null;
            return constructor.invokeExact();
        }

        private static boolean copyOpenHashMapRecords(Object sourceVariant, Object targetVariant) {
            Object sourceRecords = getVariantRecordsOrNull(sourceVariant);
            Object targetRecords = getVariantRecordsOrNull(targetVariant);
            if (!(sourceRecords instanceof it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<?>)
                    || !(targetRecords instanceof it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<?>)) {
                return false;
            }
            if (UNSAFE == null
                    || OBJECT2LONG_OPEN_HASH_MAP_KEY_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_VALUE_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_MASK_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_SIZE_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_MAX_FILL_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_N_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_MIN_N_OFFSET < 0L
                    || OBJECT2LONG_OPEN_HASH_MAP_CONTAINS_NULL_KEY_OFFSET < 0L) {
                return false;
            }
            try {
                Object[] keys = (Object[]) UNSAFE.getObject(sourceRecords, OBJECT2LONG_OPEN_HASH_MAP_KEY_OFFSET);
                long[] values = (long[]) UNSAFE.getObject(sourceRecords, OBJECT2LONG_OPEN_HASH_MAP_VALUE_OFFSET);
                UNSAFE.putObject(targetRecords, OBJECT2LONG_OPEN_HASH_MAP_KEY_OFFSET, keys.clone());
                UNSAFE.putObject(targetRecords, OBJECT2LONG_OPEN_HASH_MAP_VALUE_OFFSET, values.clone());
                UNSAFE.putInt(targetRecords, OBJECT2LONG_OPEN_HASH_MAP_MASK_OFFSET,
                        UNSAFE.getInt(sourceRecords, OBJECT2LONG_OPEN_HASH_MAP_MASK_OFFSET));
                UNSAFE.putInt(targetRecords, OBJECT2LONG_OPEN_HASH_MAP_SIZE_OFFSET,
                        UNSAFE.getInt(sourceRecords, OBJECT2LONG_OPEN_HASH_MAP_SIZE_OFFSET));
                UNSAFE.putInt(targetRecords, OBJECT2LONG_OPEN_HASH_MAP_MAX_FILL_OFFSET,
                        UNSAFE.getInt(sourceRecords, OBJECT2LONG_OPEN_HASH_MAP_MAX_FILL_OFFSET));
                UNSAFE.putInt(targetRecords, OBJECT2LONG_OPEN_HASH_MAP_N_OFFSET,
                        UNSAFE.getInt(sourceRecords, OBJECT2LONG_OPEN_HASH_MAP_N_OFFSET));
                UNSAFE.putInt(targetRecords, OBJECT2LONG_OPEN_HASH_MAP_MIN_N_OFFSET,
                        UNSAFE.getInt(sourceRecords, OBJECT2LONG_OPEN_HASH_MAP_MIN_N_OFFSET));
                UNSAFE.putBoolean(targetRecords, OBJECT2LONG_OPEN_HASH_MAP_CONTAINS_NULL_KEY_OFFSET,
                        UNSAFE.getBoolean(sourceRecords, OBJECT2LONG_OPEN_HASH_MAP_CONTAINS_NULL_KEY_OFFSET));
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static void addVariantCounter(Object target, Object source) throws Throwable {
            VARIANT_COUNTER_ADD_ALL.invokeExact(target, source);
        }

        private static void addVariantCounterEntry(Object target, AEKey key, long amount) throws Throwable {
            VARIANT_COUNTER_ADD.invokeExact(target, key, amount);
        }

        private static java.lang.invoke.MethodHandle findVariantCounterMethod(String name) {
            try {
                Class<?> type = Class.forName("appeng.api.stacks.VariantCounter");
                java.lang.reflect.Method method;
                if (name.equals("addAll")) {
                    method = type.getDeclaredMethod(name, type);
                } else if (name.equals("add")) {
                    method = type.getDeclaredMethod(name, AEKey.class, long.class);
                } else {
                    method = type.getDeclaredMethod(name);
                }
                method.setAccessible(true);
                java.lang.invoke.MethodHandle handle = java.lang.invoke.MethodHandles.lookup().unreflect(method);
                if (name.equals("copy")) {
                    return handle.asType(java.lang.invoke.MethodType.methodType(Object.class, Object.class));
                }
                if (name.equals("add")) {
                    return handle.asType(java.lang.invoke.MethodType.methodType(void.class, Object.class, AEKey.class, long.class));
                }
                if (name.equals("getRecords")) {
                    return handle.asType(java.lang.invoke.MethodType.methodType(Object.class, Object.class));
                }
                return handle.asType(java.lang.invoke.MethodType.methodType(void.class, Object.class, Object.class));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static sun.misc.Unsafe findUnsafe() {
            try {
                java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                return (sun.misc.Unsafe) field.get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Class<?> findVariantCounterNestedClass(String simpleName) {
            try {
                return Class.forName("appeng.api.stacks.VariantCounter$" + simpleName);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static java.lang.invoke.MethodHandle findVariantCounterConstructor(Class<?> type) {
            try {
                if (type == null) return null;
                java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                return java.lang.invoke.MethodHandles.lookup().unreflectConstructor(constructor)
                        .asType(java.lang.invoke.MethodType.methodType(Object.class));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static long findFieldOffset(Class<?> type, String fieldName) {
            try {
                if (UNSAFE == null || type == null) return -1L;
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return UNSAFE.objectFieldOffset(field);
            } catch (Throwable ignored) {
                return -1L;
            }
        }
    }

    public static final class NormalizedFluidKeyStorage implements MEStorage {
        private final MEStorage delegate;
        private final EquivalentKeySnapshotCache<AEKey> equivalentKeyCache =
                new EquivalentKeySnapshotCache<>(MEDiskHatchPartMachine::normalizeFluidKey);
        private KeyCounterSnapshot cachedAvailableStacks;
        private int cachedDelegateVersion = Integer.MIN_VALUE;

        public NormalizedFluidKeyStorage(MEStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            long inserted = delegate.insert(normalizeFluidKey(what), amount, mode, src);
            if (inserted > 0 && mode == Actionable.MODULATE) {
                equivalentKeyCache.recordChange(normalizeFluidKey(what), inserted);
                clearAvailableSnapshot();
            }
            return inserted;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            int version = delegateSnapshotVersion();
            if (cachedDelegateVersion != version) {
                equivalentKeyCache.invalidate();
                cachedAvailableStacks = null;
                cachedDelegateVersion = version;
            }
            return extractNormalizedFluidKey(delegate, what, amount, mode, src, equivalentKeyCache,
                    this::loadEquivalentEntries, this::clearAvailableSnapshot);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            KeyCounterSnapshot cached = cachedAvailableStacks;
            int version = delegateSnapshotVersion();
            if (cached == null || cachedDelegateVersion != version) {
                if (equivalentKeyCache.hasSnapshot() && cachedDelegateVersion == version) {
                    KeyCounter normalized = new KeyCounter();
                    equivalentKeyCache.forEachNormalized((key, amount) -> normalized.add(key, amount));
                    cached = new KeyCounterSnapshot(normalized);
                } else {
                    java.util.ArrayList<EquivalentKeySnapshotCache.Entry<AEKey>> entries = loadEquivalentEntries();
                    equivalentKeyCache.replace(entries);
                    KeyCounter normalized = new KeyCounter();
                    for (EquivalentKeySnapshotCache.Entry<AEKey> entry : entries) {
                        normalized.add(normalizeFluidKey(entry.key()), entry.amount());
                    }
                    cached = new KeyCounterSnapshot(normalized);
                }
                cachedAvailableStacks = cached;
                cachedDelegateVersion = version;
            }
            cached.addTo(out);
        }

        @Override
        public Component getDescription() {
            return delegate.getDescription();
        }

        private java.util.ArrayList<EquivalentKeySnapshotCache.Entry<AEKey>> loadEquivalentEntries() {
            KeyCounter raw = new KeyCounter();
            delegate.getAvailableStacks(raw);
            java.util.ArrayList<EquivalentKeySnapshotCache.Entry<AEKey>> entries = new java.util.ArrayList<>(raw.size());
            for (var entry : raw) {
                long amount = entry.getLongValue();
                if (amount <= 0L) continue;
                entries.add(new EquivalentKeySnapshotCache.Entry<>(entry.getKey(), amount));
            }
            return entries;
        }

        private void clearAvailableSnapshot() {
            cachedAvailableStacks = null;
        }

        private int delegateSnapshotVersion() {
            if (delegate instanceof FilteredMEStorage filtered) {
                return filtered.snapshotVersion();
            }
            return 0;
        }
    }

    public static final class NormalizedStorageCell implements StorageCell {
        private final StorageCell delegate;
        private final EquivalentKeySnapshotCache<AEKey> equivalentKeyCache =
                new EquivalentKeySnapshotCache<>(MEDiskHatchPartMachine::normalizeFluidKey);
        private KeyCounterSnapshot cachedAvailableStacks;

        public NormalizedStorageCell(StorageCell delegate) {
            this.delegate = delegate;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            long inserted = delegate.insert(normalizeFluidKey(what), amount, mode, src);
            if (inserted > 0 && mode == Actionable.MODULATE) {
                equivalentKeyCache.recordChange(normalizeFluidKey(what), inserted);
                cachedAvailableStacks = null;
            }
            return inserted;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            return extractNormalizedFluidKey(delegate, what, amount, mode, src, equivalentKeyCache,
                    this::loadEquivalentEntries, this::clearAvailableSnapshot);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            KeyCounterSnapshot cached = cachedAvailableStacks;
            if (cached == null) {
                if (equivalentKeyCache.hasSnapshot()) {
                    KeyCounter normalized = new KeyCounter();
                    equivalentKeyCache.forEachNormalized((key, amount) -> normalized.add(key, amount));
                    cached = new KeyCounterSnapshot(normalized);
                } else {
                    java.util.ArrayList<EquivalentKeySnapshotCache.Entry<AEKey>> entries = loadEquivalentEntries();
                    equivalentKeyCache.replace(entries);
                    KeyCounter normalized = new KeyCounter();
                    for (EquivalentKeySnapshotCache.Entry<AEKey> entry : entries) {
                        normalized.add(normalizeFluidKey(entry.key()), entry.amount());
                    }
                    cached = new KeyCounterSnapshot(normalized);
                }
                cachedAvailableStacks = cached;
            }
            cached.addTo(out);
        }

        @Override
        public Component getDescription() {
            return delegate.getDescription();
        }

        @Override
        public CellState getStatus() {
            return delegate.getStatus();
        }

        @Override
        public double getIdleDrain() {
            return delegate.getIdleDrain();
        }

        @Override
        public void persist() {
            delegate.persist();
        }

        @Override
        public boolean canFitInsideCell() {
            return delegate.canFitInsideCell();
        }

        private java.util.ArrayList<EquivalentKeySnapshotCache.Entry<AEKey>> loadEquivalentEntries() {
            KeyCounter raw = new KeyCounter();
            delegate.getAvailableStacks(raw);
            java.util.ArrayList<EquivalentKeySnapshotCache.Entry<AEKey>> entries = new java.util.ArrayList<>(raw.size());
            for (var entry : raw) {
                long amount = entry.getLongValue();
                if (amount <= 0L) continue;
                entries.add(new EquivalentKeySnapshotCache.Entry<>(entry.getKey(), amount));
            }
            return entries;
        }

        private void clearAvailableSnapshot() {
            cachedAvailableStacks = null;
        }
    }

    private static AEKey normalizeFluidKey(AEKey key) {
        if (key instanceof AEFluidKey fluidKey && fluidKey.hasTag() && fluidKey.getTag().isEmpty()) {
            return fluidKey.dropSecondary();
        }
        return key;
    }

    private static long extractNormalizedFluidKey(MEStorage delegate, AEKey what, long amount, Actionable mode,
                                                  IActionSource src, EquivalentKeySnapshotCache<AEKey> cache,
                                                  java.util.function.Supplier<? extends Iterable<EquivalentKeySnapshotCache.Entry<AEKey>>> loader,
                                                  Runnable onChanged) {
        if (amount <= 0) return 0L;
        AEKey normalized = normalizeFluidKey(what);

        if (!(normalized instanceof AEFluidKey)) {
            long extracted = delegate.extract(normalized, amount, mode, src);
            if (extracted > 0 && mode == Actionable.MODULATE) {
                cache.recordChange(normalized, -extracted);
                onChanged.run();
            }
            return extracted;
        }

        long available = cache.getNormalizedAmount(normalized, loader);
        if (available <= 0L) {
            return 0L;
        }

        java.util.List<AEKey> rawKeys = cache.getEquivalentRawKeys(normalized, loader);
        if (rawKeys.isEmpty()) {
            return 0L;
        }
        if (mode == Actionable.SIMULATE) {
            return Math.min(amount, available);
        }
        if (rawKeys.size() == 1 && normalized.equals(rawKeys.get(0))) {
            long extracted = delegate.extract(normalized, Math.min(amount, available), mode, src);
            if (extracted > 0) {
                cache.recordChange(normalized, -extracted);
                onChanged.run();
            }
            return extracted;
        }

        long total = 0L;
        for (AEKey rawKey : rawKeys) {
            long remaining = amount - total;
            if (remaining <= 0L) break;
            long moved = delegate.extract(rawKey, remaining, mode, src);
            if (moved <= 0L) continue;
            total += moved;
            cache.recordChange(rawKey, -moved);
        }
        if (total > 0L) onChanged.run();
        return total;
    }

    /** 包装 MEStorage，在 getAvailableStacks 中过滤指定 key */
    public static final class FilteredMEStorage implements MEStorage {
        private final MEStorage delegate;
        private final CarrierKeyFilterState filterState;
        private KeyCounterSnapshot cachedAvailableStacks;
        private int cachedFilterVersion = -1;

        public FilteredMEStorage(MEStorage delegate, Set<AEKey> filtered) {
            this(delegate, new CarrierKeyFilterState(filtered));
        }

        public FilteredMEStorage(MEStorage delegate, CarrierKeyFilterState filterState) {
            this.delegate = delegate;
            this.filterState = filterState;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            if (filterState.contains(what)) return 0;
            long inserted = delegate.insert(what, amount, mode, src);
            if (inserted > 0 && mode == Actionable.MODULATE) invalidateCache();
            return inserted;
        }
        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            if (filterState.contains(what)) return 0;
            long extracted = delegate.extract(what, amount, mode, src);
            if (extracted > 0 && mode == Actionable.MODULATE) invalidateCache();
            return extracted;
        }
        @Override
        public void getAvailableStacks(KeyCounter out) {
            KeyCounterSnapshot cached = cachedAvailableStacks;
            int version = filterState.version();
            if (cached == null || cachedFilterVersion != version) {
                KeyCounter counter = new KeyCounter();
                delegate.getAvailableStacks(counter);
                for (AEKey key : filterState.keys()) counter.remove(key);
                cached = new KeyCounterSnapshot(counter);
                cachedAvailableStacks = cached;
                cachedFilterVersion = version;
            }
            cached.addTo(out);
        }
        @Override
        public Component getDescription() {
            return delegate.getDescription();
        }

        private void invalidateCache() {
            cachedAvailableStacks = null;
            cachedFilterVersion = -1;
        }

        private int snapshotVersion() {
            return filterState.version();
        }
    }

    /** AE2 BasicCellInventory 在 container 为 null 时会自己写回 ItemStack。 */
    public record PersistedCellStorage(StorageCell delegate, ISaveProvider owner) implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            long inserted = delegate.insert(what, amount, mode, src);
            if (inserted > 0 && mode == Actionable.MODULATE) {
                delegate.persist();
                owner.saveChanges();
            }
            return inserted;
        }
        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            long extracted = delegate.extract(what, amount, mode, src);
            if (extracted > 0 && mode == Actionable.MODULATE) {
                delegate.persist();
                owner.saveChanges();
            }
            return extracted;
        }
        @Override
        public void getAvailableStacks(KeyCounter out) {
            delegate.getAvailableStacks(out);
        }
        @Override
        public Component getDescription() {
            return delegate.getDescription();
        }
    }

    public static final class AggregatedNestedCellStorage implements MEStorage {
        private final List<NestedCellStorage> children;
        private final Map<AEKey, Integer> preferredExtractChild = new HashMap<>();
        // 懒建立聚合计数；子盘真实变更后失效重建，保留无限/BigInteger 子盘语义。
        private KeyCounter aggregateCounter;
        private KeyCounterSnapshot aggregateSnapshot;
        private int aggregateSnapshotVersion = -1;

        private AggregatedNestedCellStorage(List<NestedCellStorage> children) {
            this.children = children;
            for (NestedCellStorage child : children) {
                child.setAggregateInvalidationSink(this::invalidateAggregateCache);
            }
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            long remaining = amount;
            for (NestedCellStorage child : children) {
                if (remaining <= 0) break;
                remaining -= child.insert(what, remaining, mode, src);
            }
            return amount - remaining;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            if (amount <= 0) return 0;
            if (mode == Actionable.SIMULATE) {
                return Math.min(amount, getCachedAvailableAmount(what));
            }
            long extracted = 0;
            int preferredIndex = preferredExtractChild.getOrDefault(what, -1);
            if (preferredIndex >= 0 && preferredIndex < children.size()) {
                extracted = children.get(preferredIndex).extract(what, amount, mode, src);
                if (extracted >= amount) {
                    return extracted;
                }
            }
            int successfulIndex = extracted > 0 ? preferredIndex : -1;
            for (int i = 0; i < children.size(); i++) {
                if (extracted >= amount) break;
                if (i == preferredIndex) continue;
                long moved = children.get(i).extract(what, amount - extracted, mode, src);
                if (moved > 0 && successfulIndex < 0) {
                    successfulIndex = i;
                }
                extracted += moved;
            }
            if (successfulIndex >= 0) {
                preferredExtractChild.put(what, successfulIndex);
            } else if (preferredIndex >= 0) {
                preferredExtractChild.remove(what);
            }
            return extracted;
        }

        private long getCachedAvailableAmount(AEKey what) {
            // 直接 O(1) 哈希查找，绕开快照的线性扫描
            return getAggregateCounter().get(what);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            KeyCounterSnapshot snapshot = aggregateSnapshot;
            int version = getAggregateVersion();
            if (snapshot == null || aggregateSnapshotVersion != version) {
                KeyCounter counter = getAggregateCounter();
                snapshot = new KeyCounterSnapshot(counter, false);
                aggregateSnapshot = snapshot;
                aggregateSnapshotVersion = version;
            }
            snapshot.addTo(out);
        }

        // 首次访问时建立聚合基线；子盘变更后由 invalidateAggregateCache 触发下一次重建。
        private KeyCounter getAggregateCounter() {
            KeyCounter counter = aggregateCounter;
            if (counter == null) {
                counter = new KeyCounter();
                for (NestedCellStorage child : children) {
                    child.addAvailableStacksTo(counter);
                }
                counter.removeZeros();
                aggregateCounter = counter;
            }
            return counter;
        }

        private int getAggregateVersion() {
            return aggregateCounter != null ? System.identityHashCode(aggregateCounter) : 0;
        }

        // 子盘可能是无限/BigInteger 语义，extract 返回值不一定等于可用量 delta。
        private void invalidateAggregateCache() {
            aggregateCounter = null;
            aggregateSnapshot = null;
            aggregateSnapshotVersion = -1;
        }

        @Override
        public Component getDescription() {
            return Component.literal("Nested SDA Cells");
        }
    }

    /**
     * SDA 内联 cell 来自父 SDA 的物品条目，不能裸挂临时 ItemStack。
     * 修改后必须把旧载体从父 SDA 移除，再插入带新 NBT 的载体。
     */
    public static final class NestedCellStorage implements MEStorage {
        private final StorageCell parent;
        private final StorageCell nested;
        private final ItemStack carrierStack;
        private final CarrierKeyFilterState filterState;
        private final ISaveProvider owner;
        private AEKey currentCarrierKey;
        private KeyCounterSnapshot cachedAvailableStacks;
        private Runnable aggregateInvalidationSink;

        public NestedCellStorage(StorageCell parent, StorageCell nested, AEKey carrierKey, ItemStack carrierStack,
                                 Set<AEKey> filteredCarrierKeys, ISaveProvider owner) {
            this(parent, nested, carrierKey, carrierStack, new CarrierKeyFilterState(filteredCarrierKeys), owner);
        }

        public NestedCellStorage(StorageCell parent, StorageCell nested, AEKey carrierKey, ItemStack carrierStack,
                                 CarrierKeyFilterState filterState, ISaveProvider owner) {
            this.parent = parent;
            this.nested = nested;
            this.currentCarrierKey = carrierKey;
            this.carrierStack = carrierStack;
            this.filterState = filterState;
            this.owner = owner;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            if (mode == Actionable.MODULATE && !canPersistCarrierChange(what, amount, true, src)) {
                return 0;
            }
            long inserted = nested.insert(what, amount, mode, src);
            if (inserted > 0 && mode == Actionable.MODULATE) {
                onNestedChanged(what, inserted);
                persistCarrier(src);
            }
            return inserted;
        }
        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            if (mode == Actionable.MODULATE && parent.extract(currentCarrierKey, 1, Actionable.SIMULATE, src) <= 0) {
                return 0;
            }
            long extracted = nested.extract(what, amount, mode, src);
            if (extracted > 0 && mode == Actionable.MODULATE) {
                onNestedChanged(what, -extracted);
                persistCarrier(src);
            }
            return extracted;
        }
        @Override
        public void getAvailableStacks(KeyCounter out) {
            addAvailableStacksTo(out);
        }

        private void addAvailableStacksTo(KeyCounter out) {
            KeyCounterSnapshot cached = cachedAvailableStacks;
            if (cached == null) {
                KeyCounter counter = new KeyCounter();
                addRawAvailableStacksTo(counter);
                cached = new KeyCounterSnapshot(counter);
                cachedAvailableStacks = cached;
            }
            cached.addTo(out);
        }

        private void addRawAvailableStacksTo(KeyCounter out) {
            nested.getAvailableStacks(out);
        }

        private void setAggregateInvalidationSink(Runnable aggregateInvalidationSink) {
            this.aggregateInvalidationSink = aggregateInvalidationSink;
        }

        @Override
        public Component getDescription() {
            return nested.getDescription();
        }

        private void persistCarrier(IActionSource src) {
            nested.persist();
            long removed = parent.extract(currentCarrierKey, 1, Actionable.MODULATE, src);
            if (removed <= 0) return;
            parent.persist();
            AEItemKey updatedCarrierKey = AEItemKey.of(carrierStack);
            long inserted = parent.insert(updatedCarrierKey, 1, Actionable.MODULATE, src);
            parent.persist();
            if (inserted > 0) {
                filterState.replace(currentCarrierKey, updatedCarrierKey);
                currentCarrierKey = updatedCarrierKey;
            }
            owner.saveChanges();
        }

        // 本层清空自身快照；聚合层重建以尊重无限/BigInteger 子盘自己的库存语义。
        private void onNestedChanged(AEKey what, long delta) {
            cachedAvailableStacks = null;
            if (aggregateInvalidationSink != null && delta != 0) {
                aggregateInvalidationSink.run();
            }
        }

        private boolean canPersistCarrierChange(AEKey what, long amount, boolean insert, IActionSource src) {
            ItemStack simulatedStack = carrierStack.copy();
            StorageCell simulatedCell = StorageCells.getCellInventory(simulatedStack, null);
            if (simulatedCell == null) return false;
            long changed = insert
                ? simulatedCell.insert(what, amount, Actionable.MODULATE, src)
                : simulatedCell.extract(what, amount, Actionable.MODULATE, src);
            if (changed <= 0) return false;
            simulatedCell.persist();
            StorageCell changedCarrierCell = StorageCells.getCellInventory(simulatedStack, null);
            if (changedCarrierCell != null && !changedCarrierCell.canFitInsideCell()) return false;
            return parent.extract(currentCarrierKey, 1, Actionable.SIMULATE, src) > 0;
        }
    }

    private void flushDirtyVirtualCells() {
        if (cachedVirtualCells == null || cachedVirtualCells.isEmpty()) return;
        int slots = diskSlots.getSlots();
        for (var entry : cachedVirtualCells.entrySet()) {
            int slot = entry.getKey();
            if (slot >= slots) continue;
            ItemStack stack = diskSlots.getStackInSlot(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof SuperDiskArrayItem)) continue;
            SuperDiskArrayItem.flushVirtualCells(stack, entry.getValue());
            for (var vc : entry.getValue()) vc.clearDirty();
        }
    }

    // ==================== IFancyUIMachine ====================

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return true;
    }

    @Override
    public Widget createUIWidget() {
        int total = diskSlots.getSlots();
        int rowSize = Math.min(9, total);
        int colSize = (total + rowSize - 1) / rowSize;
        WidgetGroup group = new WidgetGroup(0, 0, (18 * rowSize) + 16, (18 * colSize) + 16);
        WidgetGroup container = new WidgetGroup(4, 4, (18 * rowSize) + 8, (18 * colSize) + 8);
        int index = 0;
        for (int y = 0; y < colSize && index < total; y++) {
            for (int x = 0; x < rowSize && index < total; x++) {
                int i = index;
                index++;
                container.addWidget(new SlotWidget(diskSlots.storage, i, 4 + (x * 18), 4 + (y * 18), true, true)
                        .setBackgroundTexture(GuiTextures.SLOT));
            }
        }
        container.setBackground(GuiTextures.BACKGROUND_INVERSE);
        group.addWidget(container);
        return group;
    }

    // ==================== 右键交互 ====================

    @Override
    public InteractionResult onUse(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return InteractionResult.PASS;
        StorageCell cell = StorageCells.getCellInventory(held, this);
        if (cell == null) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        int slots = diskSlots.getSlots();
        for (int i = 0; i < slots; i++) {
            if (diskSlots.getStackInSlot(i).isEmpty()) {
                diskSlots.storage.setStackInSlot(i, held.split(1));
                diskSlots.storage.onContentsChanged();
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.PASS;
    }

    // ==================== 公开 API ====================

    public NotifiableItemStackHandler getDiskSlots() {
        return diskSlots;
    }

    // ==================== 注册 ====================

    public static MachineDefinition register() {
        var def = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "me_disk_hatch",
                MachineDefinition::createDefinition,
                MEDiskHatchPartMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation("gtlcore", "block/casings/dimensionally_transcendent_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/me_disk_hatch")))
                .register();

        def.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(Component.literal("§6§lME 磁盘仓室"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7ME存储元件挂载点 槽位通过配置文件配置"));
            tooltips.add(Component.literal("默认配置下为108槽，最大为256槽，更改后需要重放磁盘仓室"));
            tooltips.add(Component.literal("§b放入 ME 存储元件后接入 AE 网络"));
            tooltips.add(Component.literal("§a任何面接 ME 线缆均可连接"));
        });

        return def;
    }
}
