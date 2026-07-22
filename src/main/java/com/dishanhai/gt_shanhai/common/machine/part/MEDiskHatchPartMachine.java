package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiNBTAPI;
import com.dishanhai.gt_shanhai.api.ae2.AeStorageAmountMath;
import com.dishanhai.gt_shanhai.api.ae2.ISaturatedAvailableStacksProvider;
import com.dishanhai.gt_shanhai.api.gui.configurators.MEDiskHatchPriorityConfigurator;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;
import com.dishanhai.gt_shanhai.common.item.VirtualCellStorage;
import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.gregtechceu.gtceu.api.capability.recipe.IO;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.Set;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
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
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
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
import appeng.helpers.IPriorityHost;
import appeng.menu.ISubMenu;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

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
        implements IInteractedMachine, IFancyUIMachine, DShanhaiAENetworkMachine, IStorageProvider, ISaveProvider,
        IPriorityHost {

    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MEDiskHatchPartMachine.class, MultiblockPartMachine.MANAGED_FIELD_HOLDER);
    public static final int DEFAULT_DISK_SLOT_COUNT = 108;
    private static final long PERSIST_DELAY_TICKS = 20L;

    @Persisted
    @DescSynced
    public final NotifiableItemStackHandler diskSlots;

    @Persisted
    private final GridNodeHolder nodeHolder;

    @Persisted
    @DescSynced
    private int priority;

    @DescSynced
    private boolean isOnline;

    private final SlotRuntimeCache[] slotRuntimeCaches;
    private final long[] slotGenerations;
    private final BitSet dirtyCellSlots;
    private transient TickableSubscription pendingPersistTick;
    private long lastPersistGameTime = Long.MIN_VALUE;
    private long pendingPersistFirstDirtyTick = Long.MIN_VALUE;

    public MEDiskHatchPartMachine(IMachineBlockEntity holder) {
        super(holder);
        int slots = DEFAULT_DISK_SLOT_COUNT;
        try {
            slots = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.meDiskHatchSlots.get();
        } catch (Throwable ignored) {}
        this.slotRuntimeCaches = new SlotRuntimeCache[slots];
        this.slotGenerations = new long[slots];
        this.dirtyCellSlots = new BitSet(slots);
        this.diskSlots = new NotifiableItemStackHandler(
                this, slots, IO.BOTH, IO.BOTH, DiskSlotTransfer::new);
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
        if (!isRemote()) {
            com.dishanhai.gt_shanhai.common.shop.ShopAeNetwork.registerDiskHatch(this);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MEH onLoad server={}, diskSlots[0]={}, nodeOnline={}",
                !isRemote(), diskSlots.getStackInSlot(0), getMainNode().isOnline());
        }
    }

    @Override
    public void onUnload() {
        if (!isRemote()) {
            com.dishanhai.gt_shanhai.common.shop.ShopAeNetwork.unregisterDiskHatch(this);
            forcePersistAll();
        }
        if (pendingPersistTick != null) {
            pendingPersistTick.unsubscribe();
            pendingPersistTick = null;
        }
        for (int slot = 0; slot < slotRuntimeCaches.length; slot++) {
            slotRuntimeCaches[slot] = null;
        }
        dirtyCellSlots.clear();
        pendingPersistFirstDirtyTick = Long.MIN_VALUE;
        super.onUnload();
    }

    public void forcePersistAll() {
        for (int slot = 0; slot < slotRuntimeCaches.length; slot++) {
            forcePersistSlot(slot);
        }
        dirtyCellSlots.clear();
        pendingPersistFirstDirtyTick = Long.MIN_VALUE;
    }

    private void forcePersistSlot(int slot) {
        if (slot < 0 || slot >= slotRuntimeCaches.length) return;
        SlotRuntimeCache runtime = slotRuntimeCaches[slot];
        if (runtime == null) return;
        for (StorageCell cell : runtime.persistableCells) {
            cell.persist();
        }
        if (!runtime.virtualCells.isEmpty()) {
            SuperDiskArrayItem.flushVirtualCells(runtime.carrier, runtime.virtualCells);
        }
        dirtyCellSlots.clear(slot);
    }

    private void onCellContentsChanged(int slot, long generation) {
        if (slot < 0 || slot >= slotGenerations.length || slotGenerations[slot] != generation) return;
        if (!dirtyCellSlots.get(slot)) {
            dirtyCellSlots.set(slot);
            if (pendingPersistFirstDirtyTick == Long.MIN_VALUE) {
                Level level = getLevel();
                pendingPersistFirstDirtyTick = level == null ? 0L : level.getGameTime();
            }
            markDirty();
            schedulePersistTick();
        }
    }

    private void schedulePersistTick() {
        if (isRemote() || pendingPersistTick != null) return;
        pendingPersistTick = subscribeServerTick(pendingPersistTick, this::flushScheduledPersistence);
    }

    private void flushScheduledPersistence() {
        Level level = getLevel();
        if (level == null) return;
        if (dirtyCellSlots.isEmpty()) {
            if (pendingPersistTick != null) {
                pendingPersistTick.unsubscribe();
                pendingPersistTick = null;
            }
            pendingPersistFirstDirtyTick = Long.MIN_VALUE;
            return;
        }
        long gameTime = level.getGameTime();
        if (lastPersistGameTime == gameTime) return;
        if (pendingPersistFirstDirtyTick == Long.MIN_VALUE) {
            pendingPersistFirstDirtyTick = gameTime;
        }
        if (gameTime - pendingPersistFirstDirtyTick < PERSIST_DELAY_TICKS) {
            return;
        }
        lastPersistGameTime = gameTime;

        for (int slot = dirtyCellSlots.nextSetBit(0);
             slot >= 0;
             slot = dirtyCellSlots.nextSetBit(slot + 1)) {
            forcePersistSlot(slot);
        }
        markDirty();
        if (dirtyCellSlots.isEmpty() && pendingPersistTick != null) {
            pendingPersistTick.unsubscribe();
            pendingPersistTick = null;
            pendingPersistFirstDirtyTick = Long.MIN_VALUE;
        }
    }

    private void evictSlotRuntime(int slot) {
        if (slot < 0 || slot >= slotRuntimeCaches.length) return;
        SlotRuntimeCache runtime = slotRuntimeCaches[slot];
        if (runtime != null) {
            forcePersistSlot(slot);
            slotRuntimeCaches[slot] = null;
        }
        dirtyCellSlots.clear(slot);
        if (dirtyCellSlots.isEmpty()) {
            pendingPersistFirstDirtyTick = Long.MIN_VALUE;
        }
        slotGenerations[slot]++;
    }

    private void reconcileSlotRuntimes() {
        for (int slot = 0; slot < slotRuntimeCaches.length; slot++) {
            SlotRuntimeCache runtime = slotRuntimeCaches[slot];
            if (runtime != null && runtime.carrier != diskSlots.getStackInSlot(slot)) {
                evictSlotRuntime(slot);
            }
        }
    }

    private void onDiskSlotContentsChanged(int slot) {
        evictSlotRuntime(slot);
        markDirty();
        if (!isRemote()) {
            IStorageProvider.requestUpdate(getMainNode());
        }
    }

    private void onAllDiskSlotsContentsChanged() {
        reconcileSlotRuntimes();
        markDirty();
        if (!isRemote()) {
            IStorageProvider.requestUpdate(getMainNode());
        }
    }

    private static final class SlotRuntimeCache {
        private final ItemStack carrier;
        private final long generation;
        private final List<MEStorage> mounts = new ArrayList<>();
        private final List<StorageCell> persistableCells = new ArrayList<>();
        private List<VirtualCellStorage> virtualCells = List.of();
        private int mountedCount;

        private SlotRuntimeCache(ItemStack carrier, long generation) {
            this.carrier = carrier;
            this.generation = generation;
        }
    }

    private final class DiskSlotTransfer extends ItemStackTransfer {
        private DiskSlotTransfer(int size) {
            super(size);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate, boolean notifyChanges) {
            if (!simulate) {
                forcePersistSlot(slot);
            }
            return super.extractItem(slot, amount, simulate, notifyChanges);
        }

        @Override
        public CompoundTag serializeNBT() {
            forcePersistAll();
            return super.serializeNBT();
        }

        @Override
        public void onContentsChanged(int slot) {
            onDiskSlotContentsChanged(slot);
            super.onContentsChanged();
        }

        @Override
        public void onContentsChanged() {
            onAllDiskSlotsContentsChanged();
            super.onContentsChanged();
        }
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ==================== ISaveProvider ====================

    @Override
    public void saveChanges() {
        boolean scheduled = false;
        for (int slot = 0; slot < slotRuntimeCaches.length; slot++) {
            SlotRuntimeCache runtime = slotRuntimeCaches[slot];
            if (runtime == null) continue;
            onCellContentsChanged(slot, runtime.generation);
            scheduled = true;
        }
        if (!scheduled) markDirty();
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

    // ==================== IPriorityHost ====================

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public void setPriority(int newValue) {
        if (priority == newValue) return;
        priority = newValue;
        markDirty();
        if (!isRemote()) {
            IStorageProvider.requestUpdate(getMainNode());
        }
    }

    public void adjustPriority(int delta) {
        setPriority(MEDiskHatchPriority.add(priority, delta));
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(getDefinition().getItem());
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        // Priority is configured through this machine's Fancy UI, not an AE2 submenu.
    }

    // ==================== IStorageProvider ====================

    @Override
    public void mountInventories(IStorageMounts storageMounts) {
        reconcileSlotRuntimes();
        int mounted = 0;
        int slots = diskSlots.getSlots();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = diskSlots.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            SlotRuntimeCache runtime = getOrCreateSlotRuntime(i, stack);
            mounted += runtime.mountedCount;
            int slotPriority = MEDiskHatchPriority.forSlot(priority, i);
            for (MEStorage storage : runtime.mounts) {
                storageMounts.mount(storage, slotPriority);
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MEH mountInventories → {} cells, priority={}, online={}, nodeOnline={}, nodeActive={}",
                mounted, priority, isOnline, getMainNode().isOnline(), getMainNode().isActive());
        }
    }

    private SlotRuntimeCache getOrCreateSlotRuntime(int slot, ItemStack stack) {
        SlotRuntimeCache cached = slotRuntimeCaches[slot];
        if (cached != null && cached.carrier == stack) {
            return cached;
        }

        evictSlotRuntime(slot);
        long generation = ++slotGenerations[slot];
        SlotRuntimeCache runtime = new SlotRuntimeCache(stack, generation);
        slotRuntimeCaches[slot] = runtime;
        ISaveProvider slotSaveProvider = () -> onCellContentsChanged(slot, generation);

        normalizeEaeInfinityCellRecord(stack);
        MEStorage directInfinityCell = createEaeInfinityCellStorage(stack);
        if (directInfinityCell != null) {
            runtime.mounts.add(directInfinityCell);
            runtime.mountedCount++;
            return runtime;
        }

        StorageCell cell = StorageCells.getCellInventory(stack, slotSaveProvider);
        if (cell == null) {
            return runtime;
        }
        runtime.persistableCells.add(cell);

        if (stack.getItem() instanceof SuperDiskArrayItem) {
            List<NestedCellStorage> nestedStorages = new ArrayList<>();
            collectSdaMounts(stack, slot, cell, slotSaveProvider, runtime, nestedStorages);
            if (!nestedStorages.isEmpty()) {
                runtime.mounts.add(new AggregatedNestedCellStorage(nestedStorages));
                runtime.mountedCount++;
            }
        } else {
            runtime.mounts.add(new NormalizedFluidKeyStorage(cell));
            runtime.mountedCount++;
        }
        return runtime;
    }

    /**
     * 智能挂载 SDA：
     * 1. 遍历 SDA 内部物品，对每个有子 cell inventory 的（如 infinity_cell）挂载其子盘内容
     * 2. SDA 自身 inventory 用 FilteredMEStorage 过滤掉子载体物品
     * 3. 额外挂载 virtual_cells NBT 定义的虚拟磁盘
     */
    private void collectSdaMounts(ItemStack stack, int slot, StorageCell sdaCell,
                                  ISaveProvider slotSaveProvider, SlotRuntimeCache runtime,
                                  List<NestedCellStorage> nestedStorages) {
        // 收集 SDA 内的子载体（infinity_cell 等）
        KeyCounter contents = new KeyCounter();
        sdaCell.getAvailableStacks(contents);
        Set<AEKey> carrierKeys = new HashSet<>();
        CarrierKeyFilterState carrierFilterState = new CarrierKeyFilterState(carrierKeys);
        List<EaeInfinityCellStorage> infinityCells = new ArrayList<>();
        for (var entry : contents) {
            AEKey key = entry.getKey();
            if (key instanceof AEItemKey itemKey) {
                ItemStack innerStack = itemKey.toStack();
                if (innerStack.getItem() instanceof SuperDiskArrayItem) {
                    // 已存在的嵌套 SDA 保留为普通物品；禁止把它再次解析成子库存，避免自引用挂载。
                    continue;
                }
                normalizeEaeInfinityCellRecord(innerStack);
                MEStorage directInfinityCell = createEaeInfinityCellStorage(innerStack);
                if (directInfinityCell != null) {
                    carrierFilterState.add(key);
                    infinityCells.add((EaeInfinityCellStorage) directInfinityCell);
                    continue;
                }
                StorageCell subCell = StorageCells.getCellInventory(innerStack, slotSaveProvider);
                if (subCell != null) {
                    // create() 可能在临时 innerStack 上认领 UUID；先完成子盘序列化，再把新 key
                    // 原子提交回父 SDA。否则重挂载会重复 fork UUID，且父盘始终保留旧载体。
                    subCell.persist();
                    AEItemKey mountedCarrierKey = AEItemKey.of(innerStack);
                    if (!key.equals(mountedCarrierKey)) {
                        if (!(sdaCell instanceof SuperDiskArrayInventory parentSda)
                                || !parentSda.replaceOneStoredKey(key, mountedCarrierKey)) {
                            LOGGER.warn("MEH slot {} failed to commit claimed nested cell carrier {}",
                                    slot, key);
                            continue;
                        }
                    }
                    carrierFilterState.add(key);
                    carrierFilterState.add(mountedCarrierKey);
                    nestedStorages.add(new NestedCellStorage(sdaCell,
                            new NormalizedStorageCell(subCell), mountedCarrierKey, innerStack, carrierFilterState,
                            slotSaveProvider));
                }
            }
        }

        // 合并挂载本槽位内的所有无限盘：单个直接挂，多个合并成一个聚合挂载对象，
        // 避免 AE 网络扫描时对每个无限盘单独派发 getAvailableStacks。
        if (infinityCells.size() == 1) {
            runtime.mounts.add(infinityCells.get(0));
            runtime.mountedCount++;
        } else if (infinityCells.size() > 1) {
            runtime.mounts.add(new AggregatedInfinityCellStorage(infinityCells));
            runtime.mountedCount++;
        }

        // 挂载 SDA（过滤掉子载体物品）
        if (carrierKeys.isEmpty()) {
            runtime.mounts.add(new NormalizedFluidKeyStorage(sdaCell));
        } else {
            runtime.mounts.add(new NormalizedFluidKeyStorage(new FilteredMEStorage(sdaCell, carrierFilterState)));
        }
        runtime.mountedCount++;

        // 挂载 virtual_cells NBT 定义的虚拟磁盘
        runtime.virtualCells = SuperDiskArrayItem.readVirtualCells(stack, getLevel());
        for (VirtualCellStorage virtualCell : runtime.virtualCells) {
            runtime.mounts.add(new NormalizedFluidKeyStorage(
                    new ChangeNotifyingStorage(virtualCell, slotSaveProvider)));
            runtime.mountedCount++;
        }
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

    public static final class EaeInfinityCellStorage implements MEStorage, ISaturatedAvailableStacksProvider {
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
            gtShanhai$getAvailableStacksSaturated(out);
        }

        @Override
        public void gtShanhai$getAvailableStacksSaturated(KeyCounter out) {
            out.set(record, Long.MAX_VALUE);
        }

        @Override
        public Component getDescription() {
            return description;
        }
    }

    /**
     * 合并同一槽位内多个 EaeInfinityCellStorage（SDA 批量装无限盘时常见）为一个挂载对象。
     * 每个无限盘内容构造后永不变化（record 不可变），聚合快照只需建立一次；
     * 避免 AE 网络每次扫描库存都对该槽位内每个无限盘单独派发一次 getAvailableStacks
     * （spark 剖析：单次 KeyCounter.set/getSubIndex 开销虽小，但按无限盘数量线性叠加，
     * SDA 内无限盘越多、网络扫描越频繁就越明显）。insert/extract 逐个匹配 record 委派，
     * 与原来 AE 网络自己逐个挂载点尝试的语义等价（同优先级内互相替代，不影响提取顺序）。
     */
    public static final class AggregatedInfinityCellStorage implements MEStorage, ISaturatedAvailableStacksProvider {
        private final List<EaeInfinityCellStorage> children;
        private final KeyCounterSnapshot snapshot;

        private AggregatedInfinityCellStorage(List<EaeInfinityCellStorage> children) {
            this.children = children;
            this.snapshot = KeyCounterSnapshot.aggregate(children);
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            for (EaeInfinityCellStorage child : children) {
                long inserted = child.insert(what, amount, mode, src);
                if (inserted > 0) return inserted;
            }
            return 0L;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            for (EaeInfinityCellStorage child : children) {
                long extracted = child.extract(what, amount, mode, src);
                if (extracted > 0) return extracted;
            }
            return 0L;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            gtShanhai$getAvailableStacksSaturated(out);
        }

        @Override
        public void gtShanhai$getAvailableStacksSaturated(KeyCounter out) {
            snapshot.addTo(out);
        }

        @Override
        public Component getDescription() {
            return Component.literal("Infinity Cells");
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
        private final KeyCounter counter = new KeyCounter();
        private final AEKey[] keys;
        private final long[] amounts;

        private KeyCounterSnapshot(KeyCounter source) {
            AeStorageAmountMath.mergeSaturated(counter, source);
            int size = counter.size();
            this.keys = new AEKey[size];
            this.amounts = new long[size];
            int index = 0;
            for (Object2LongMap.Entry<AEKey> entry : counter) {
                keys[index] = entry.getKey();
                amounts[index] = entry.getLongValue();
                index++;
            }
        }

        private static KeyCounterSnapshot aggregate(Iterable<? extends MEStorage> storages) {
            KeyCounter merged = new KeyCounter();
            KeyCounter contribution = new KeyCounter();
            for (MEStorage storage : storages) {
                AeStorageAmountMath.getAvailableStacksSaturated(storage, merged, contribution);
            }
            return new KeyCounterSnapshot(merged);
        }

        private long get(AEKey key) {
            return counter.get(key);
        }

        private void addTo(KeyCounter out) {
            for (int i = 0; i < keys.length; i++) {
                if (amounts[i] == Long.MAX_VALUE) {
                    out.set(keys[i], Long.MAX_VALUE);
                } else {
                    AeStorageAmountMath.addSaturated(out, keys[i], amounts[i]);
                }
            }
        }
    }

    public static final class NormalizedFluidKeyStorage implements MEStorage, ISaturatedAvailableStacksProvider {
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
                equivalentKeyCache.invalidate();
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
            gtShanhai$getAvailableStacksSaturated(out);
        }

        @Override
        public void gtShanhai$getAvailableStacksSaturated(KeyCounter out) {
            KeyCounterSnapshot cached = cachedAvailableStacks;
            int version = delegateSnapshotVersion();
            if (cached == null || cachedDelegateVersion != version) {
                if (equivalentKeyCache.hasSnapshot() && cachedDelegateVersion == version) {
                    KeyCounter normalized = new KeyCounter();
                    equivalentKeyCache.forEachNormalized(
                            (key, amount) -> AeStorageAmountMath.addSaturated(normalized, key, amount));
                    cached = new KeyCounterSnapshot(normalized);
                } else {
                    cached = loadNormalizedAvailableStacksSnapshot();
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

        private KeyCounterSnapshot loadNormalizedAvailableStacksSnapshot() {
            KeyCounter raw = new KeyCounter();
            delegate.getAvailableStacks(raw);
            KeyCounter normalized = new KeyCounter();
            for (var entry : raw) {
                long amount = entry.getLongValue();
                if (amount <= 0L) continue;
                AeStorageAmountMath.addSaturated(normalized, normalizeFluidKey(entry.getKey()), amount);
            }
            return new KeyCounterSnapshot(normalized);
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
                equivalentKeyCache.invalidate();
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
                    equivalentKeyCache.forEachNormalized(
                            (key, amount) -> AeStorageAmountMath.addSaturated(normalized, key, amount));
                    cached = new KeyCounterSnapshot(normalized);
                } else {
                    cached = loadNormalizedAvailableStacksSnapshot();
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

        private KeyCounterSnapshot loadNormalizedAvailableStacksSnapshot() {
            KeyCounter raw = new KeyCounter();
            delegate.getAvailableStacks(raw);
            KeyCounter normalized = new KeyCounter();
            for (var entry : raw) {
                long amount = entry.getLongValue();
                if (amount <= 0L) continue;
                AeStorageAmountMath.addSaturated(normalized, normalizeFluidKey(entry.getKey()), amount);
            }
            return new KeyCounterSnapshot(normalized);
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
                cache.invalidate();
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
                cache.invalidate();
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
        }
        if (total > 0L) {
            cache.invalidate();
            onChanged.run();
        }
        return total;
    }

    /** 包装 MEStorage，在 getAvailableStacks 中过滤指定 key */
    public static final class FilteredMEStorage implements MEStorage, ISaturatedAvailableStacksProvider {
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
            gtShanhai$getAvailableStacksSaturated(out);
        }

        @Override
        public void gtShanhai$getAvailableStacksSaturated(KeyCounter out) {
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

    /** 为没有 ISaveProvider 契约的 MEStorage 补宿主变更通知，不在热路径执行序列化。 */
    public record ChangeNotifyingStorage(MEStorage delegate, ISaveProvider owner)
            implements MEStorage, ISaturatedAvailableStacksProvider {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            long inserted = delegate.insert(what, amount, mode, src);
            if (inserted > 0 && mode == Actionable.MODULATE) owner.saveChanges();
            return inserted;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            long extracted = delegate.extract(what, amount, mode, src);
            if (extracted > 0 && mode == Actionable.MODULATE) owner.saveChanges();
            return extracted;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            gtShanhai$getAvailableStacksSaturated(out);
        }

        @Override
        public void gtShanhai$getAvailableStacksSaturated(KeyCounter out) {
            AeStorageAmountMath.getAvailableStacksSaturated(delegate, out, null);
        }

        @Override
        public Component getDescription() {
            return delegate.getDescription();
        }
    }

    /** 高频存取只通知宿主标脏；实际序列化由宿主在合并后的安全落盘点执行。 */
    public record PersistedCellStorage(StorageCell delegate, ISaveProvider owner)
            implements MEStorage, ISaturatedAvailableStacksProvider {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            long inserted = delegate.insert(what, amount, mode, src);
            if (inserted > 0 && mode == Actionable.MODULATE) {
                owner.saveChanges();
            }
            return inserted;
        }
        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            long extracted = delegate.extract(what, amount, mode, src);
            if (extracted > 0 && mode == Actionable.MODULATE) {
                owner.saveChanges();
            }
            return extracted;
        }
        @Override
        public void getAvailableStacks(KeyCounter out) {
            gtShanhai$getAvailableStacksSaturated(out);
        }
        @Override
        public void gtShanhai$getAvailableStacksSaturated(KeyCounter out) {
            AeStorageAmountMath.getAvailableStacksSaturated(delegate, out, null);
        }
        @Override
        public Component getDescription() {
            return delegate.getDescription();
        }
    }

    public static final class AggregatedNestedCellStorage implements MEStorage, ISaturatedAvailableStacksProvider {
        private final List<NestedCellStorage> children;
        private final Map<AEKey, Integer> preferredExtractChild = new HashMap<>();
        // 查询与网络输出读取同一份状态，子盘真实变更后整体失效。
        private KeyCounterSnapshot aggregateSnapshot;

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
                return Math.min(amount, getAggregateSnapshot().get(what));
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

        @Override
        public void getAvailableStacks(KeyCounter out) {
            gtShanhai$getAvailableStacksSaturated(out);
        }

        @Override
        public void gtShanhai$getAvailableStacksSaturated(KeyCounter out) {
            getAggregateSnapshot().addTo(out);
        }

        private KeyCounterSnapshot getAggregateSnapshot() {
            KeyCounterSnapshot snapshot = aggregateSnapshot;
            if (snapshot == null) {
                snapshot = KeyCounterSnapshot.aggregate(children);
                aggregateSnapshot = snapshot;
            }
            return snapshot;
        }

        // 子盘可能是无限/BigInteger 语义，extract 返回值不一定等于可用量 delta。
        private void invalidateAggregateCache() {
            aggregateSnapshot = null;
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
    public static final class NestedCellStorage implements MEStorage, ISaturatedAvailableStacksProvider {
        private final StorageCell parent;
        private final StorageCell nested;
        private final ItemStack carrierStack;
        private final CarrierKeyFilterState filterState;
        private final ISaveProvider owner;
        private AEKey currentCarrierKey;
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
                onNestedChanged();
                persistCarrier();
            }
            return inserted;
        }
        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
            if (mode == Actionable.MODULATE && !canPersistCarrierChange(what, amount, false, src)) {
                return 0;
            }
            long extracted = nested.extract(what, amount, mode, src);
            if (extracted > 0 && mode == Actionable.MODULATE) {
                onNestedChanged();
                persistCarrier();
            }
            return extracted;
        }
        @Override
        public void getAvailableStacks(KeyCounter out) {
            gtShanhai$getAvailableStacksSaturated(out);
        }

        @Override
        public void gtShanhai$getAvailableStacksSaturated(KeyCounter out) {
            AeStorageAmountMath.getAvailableStacksSaturated(nested, out, null);
        }

        private void setAggregateInvalidationSink(Runnable aggregateInvalidationSink) {
            this.aggregateInvalidationSink = aggregateInvalidationSink;
        }

        @Override
        public Component getDescription() {
            return nested.getDescription();
        }

        private void persistCarrier() {
            nested.persist();
            AEItemKey updatedCarrierKey = AEItemKey.of(carrierStack);
            if (!currentCarrierKey.equals(updatedCarrierKey)) {
                if (!(parent instanceof SuperDiskArrayInventory parentSda)
                        || !parentSda.replaceOneStoredKey(currentCarrierKey, updatedCarrierKey)) {
                    LOGGER.error("MEH failed to atomically update nested cell carrier {}", currentCarrierKey);
                    owner.saveChanges();
                    return;
                }
                filterState.replace(currentCarrierKey, updatedCarrierKey);
                currentCarrierKey = updatedCarrierKey;
            }
            owner.saveChanges();
        }

        // 聚合层重建以尊重无限/BigInteger 子盘自己的库存语义。
        private void onNestedChanged() {
            if (aggregateInvalidationSink != null) {
                aggregateInvalidationSink.run();
            }
        }

        private boolean canPersistCarrierChange(AEKey what, long amount, boolean insert, IActionSource src) {
            if (!(parent instanceof SuperDiskArrayInventory)) return false;
            if (parent.extract(currentCarrierKey, 1, Actionable.SIMULATE, src) <= 0) return false;
            long changed = insert
                    ? nested.insert(what, amount, Actionable.SIMULATE, src)
                    : nested.extract(what, amount, Actionable.SIMULATE, src);
            return changed > 0;
        }
    }

    // ==================== IFancyUIMachine ====================

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return true;
    }

    @Override
    public void attachSideTabs(TabsWidget tabsWidget) {
        super.attachSideTabs(tabsWidget);
        tabsWidget.attachSubTab(new MEDiskHatchPriorityConfigurator(this));
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
        normalizeEaeInfinityCellRecord(held);
        StorageCell cell = StorageCells.getCellInventory(held, this);
        if (cell == null && createEaeInfinityCellStorage(held) == null) return InteractionResult.PASS;
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
