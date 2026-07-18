package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem;
import com.dishanhai.gt_shanhai.common.item.VirtualCellStorage;
import com.dishanhai.gt_shanhai.common.machine.ShanhaiPartAbility;
import com.dishanhai.gt_shanhai.common.machine.part.MEDiskHatchPartMachine;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;

import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import appeng.api.config.Actionable;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER;
import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class SingularityDataHubMachine extends MultiblockControllerMachine
        implements IInteractedMachine, IFancyUIMachine, IGridConnectedMachine, IStorageProvider, ISaveProvider {

    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            SingularityDataHubMachine.class, MultiblockControllerMachine.MANAGED_FIELD_HOLDER);

    @Persisted @DescSynced
    public final NotifiableItemStackHandler diskArraySlot;

    @Persisted
    private final GridNodeHolder nodeHolder;

    @DescSynced
    private boolean isOnline;

    private transient List<MEDiskHatchPartMachine> cachedHatches;
    private transient List<VirtualCellStorage> cachedVirtualCells;

    // ============ 构造 ============

    public SingularityDataHubMachine(IMachineBlockEntity holder, Object... args) {
        super(holder);
        this.diskArraySlot = new NotifiableItemStackHandler(this, 1, IO.BOTH) {
            @Override
            public int getSlotLimit(int slot) { return 1; }
        };
        this.diskArraySlot.storage.setOnContentsChanged(() -> {
            if (!isRemote()) {
                cachedVirtualCells = null;
                IStorageProvider.requestUpdate(getMainNode());
                markDirty();
            }
        });
        this.nodeHolder = new GridNodeHolder(this);
        getMainNode().addService(IStorageProvider.class, this);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ============ 仓室扫描（向后兼容） ============

    private List<MEDiskHatchPartMachine> getDiskHatches() {
        if (cachedHatches == null) {
            cachedHatches = new ArrayList<>();
            if (!isFormed()) return cachedHatches;
            for (IMultiPart part : getParts()) {
                if (part instanceof MEDiskHatchPartMachine h) {
                    cachedHatches.add(h);
                }
            }
        }
        return cachedHatches;
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        cachedHatches = null;
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        cachedHatches = null;
    }

    // ============ ISaveProvider ============

    @Override
    public void saveChanges() {
        flushDirtyVirtualCells();
        // 取出即分家依赖真实 saveProvider（见 getSdaCell）；saveProvider 不再是 null 后，
        // SDA 自身的 persist() 不会被 SuperDiskArrayInventory 内部兜底自动调用，需在此显式触发。
        StorageCell sdaCell = getSdaCell();
        if (sdaCell != null) sdaCell.persist();
        markDirty();
    }

    // ============ 挂载 ============

    private StorageCell getSdaCell() {
        var stack = diskArraySlot.getStackInSlot(0);
        if (stack.isEmpty()) return null;
        // 必须传真实 saveProvider（this），而非 null——否则 SuperDiskArrayInventory.create()
        // 会跳过"取出即分家"认领逻辑，插入的 SDA 若仍带着商店/任务模板的固定 UUID，
        // 会直接读写共享 backend，导致不同玩家的磁盘阵列互相串内容。
        return StorageCells.getCellInventory(stack, this);
    }

    private List<VirtualCellStorage> getOrCreateVirtualCells() {
        if (cachedVirtualCells == null) {
            var stack = diskArraySlot.getStackInSlot(0);
            if (stack.isEmpty() || !(stack.getItem() instanceof SuperDiskArrayItem)) {
                cachedVirtualCells = List.of();
            } else {
                cachedVirtualCells = SuperDiskArrayItem.readVirtualCells(stack);
            }
        }
        return cachedVirtualCells;
    }

    private void flushDirtyVirtualCells() {
        if (cachedVirtualCells == null || cachedVirtualCells.isEmpty()) return;
        var stack = diskArraySlot.getStackInSlot(0);
        if (stack.isEmpty() || !(stack.getItem() instanceof SuperDiskArrayItem)) return;
        SuperDiskArrayItem.flushVirtualCells(stack, cachedVirtualCells);
        for (var vc : cachedVirtualCells) vc.clearDirty();
    }

    // ============ IGridConnectedMachine ============

    @Override
    public IManagedGridNode getMainNode() {
        return nodeHolder.getMainNode();
    }

    @Override
    public boolean isOnline() {
        return isOnline;
    }

    @Override
    public void setOnline(boolean online) {
        this.isOnline = online;
    }

    // ============ IStorageProvider ============

    @Override
    public void mountInventories(IStorageMounts mounts) {
        flushDirtyVirtualCells();
        var sdaCell = getSdaCell();
        if (sdaCell != null) {
            // 收集 SDA 内的子载体（infinity_cell 等）
            KeyCounter contents = new KeyCounter();
            sdaCell.getAvailableStacks(contents);
            Set<AEKey> carrierKeys = new HashSet<>();
            MEDiskHatchPartMachine.CarrierKeyFilterState carrierFilterState =
                new MEDiskHatchPartMachine.CarrierKeyFilterState(carrierKeys);
            for (var entry : contents) {
                AEKey key = entry.getKey();
                if (key instanceof AEItemKey itemKey) {
                    ItemStack innerStack = itemKey.toStack();
                    if (innerStack.getItem() instanceof SuperDiskArrayItem) {
                        // 已存在的嵌套 SDA 只作为普通物品保留，不能递归挂载其内部库存。
                        continue;
                    }
                    MEDiskHatchPartMachine.normalizeEaeInfinityCellRecord(innerStack);
                    MEStorage directInfinityCell = MEDiskHatchPartMachine.createEaeInfinityCellStorage(innerStack);
                    if (directInfinityCell != null) {
                        carrierFilterState.add(key);
                        mounts.mount(directInfinityCell, 10);
                        continue;
                    }
                    StorageCell subCell = StorageCells.getCellInventory(innerStack, this);
                    if (subCell != null) {
                        // create() 可能在临时 innerStack 上认领 UUID；先完成子盘序列化，再把新 key
                        // 原子提交回父 SDA。否则重挂载会重复 fork UUID，且父盘始终保留旧载体。
                        // （与 MEDiskHatchPartMachine#collectSdaMounts 保持同一套逻辑）
                        subCell.persist();
                        AEItemKey mountedCarrierKey = AEItemKey.of(innerStack);
                        if (!key.equals(mountedCarrierKey)) {
                            if (!(sdaCell instanceof SuperDiskArrayInventory parentSda)
                                    || !parentSda.replaceOneStoredKey(key, mountedCarrierKey)) {
                                LOGGER.warn("SDH failed to commit claimed nested cell carrier {}", key);
                                continue;
                            }
                        }
                        carrierFilterState.add(key);
                        carrierFilterState.add(mountedCarrierKey);
                        mounts.mount(new MEDiskHatchPartMachine.NestedCellStorage(
                            sdaCell, new MEDiskHatchPartMachine.NormalizedStorageCell(subCell),
                            mountedCarrierKey, innerStack, carrierFilterState, this), 10);
                    }
                }
            }
            MEStorage mountedSda = new MEDiskHatchPartMachine.PersistedCellStorage(sdaCell, this);
            if (carrierKeys.isEmpty()) {
                mounts.mount(new MEDiskHatchPartMachine.NormalizedFluidKeyStorage(mountedSda), 10);
            } else {
                mounts.mount(new MEDiskHatchPartMachine.NormalizedFluidKeyStorage(
                    new MEDiskHatchPartMachine.FilteredMEStorage(mountedSda, carrierFilterState)), 10);
            }
        }
        for (var vcs : getOrCreateVirtualCells()) {
            mounts.mount(new MEDiskHatchPartMachine.NormalizedFluidKeyStorage(vcs), 10);
        }
    }

    // ============ UI ============

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return isFormed();
    }

    @Override
    public Widget createUIWidget() {
        var hatches = getDiskHatches();
        var sdaCell = getSdaCell();
        var virtualCells = getOrCreateVirtualCells();

        int cellSize = 18;
        int gridRowSize = 9;

        int titleH = 18;
        int pad = 4;
        int leftPad = 6;
        int gridW = gridRowSize * cellSize;
        int scrollbarW = 12;
        int totalW = leftPad + gridW + leftPad + scrollbarW;

        int diskArraySectionH = 24;
        int virtualSectionH = virtualCells.isEmpty() ? 0 : 16 + virtualCells.size() * 18 + 8;
        int hatchSectionH = 0;
        for (var h : hatches) {
            int total = h.getDiskSlots().getSlots();
            int cols = Math.min(12, total > 0 ? (total + gridRowSize - 1) / gridRowSize : 0);
            hatchSectionH += 16 + cols * cellSize + 6;
        }

        int contentH = diskArraySectionH + virtualSectionH + hatchSectionH + 8;
        int scrollH = Math.min(contentH, 180);
        int totalH = titleH + pad + scrollH + pad;

        var root = new WidgetGroup(0, 0, totalW, totalH);
        root.setBackground(GuiTextures.BACKGROUND_INVERSE);

        // ----- title -----
        root.addWidget(new LabelWidget(leftPad, pad,
            "§6奇点数据中枢"));
        int infoX = leftPad + 82;
        root.addWidget(new LabelWidget(infoX, pad,
            Component.literal("§7" + hatches.size() + " §8仓室")));
        if (!virtualCells.isEmpty()) {
            root.addWidget(new LabelWidget(infoX + 40, pad,
                Component.literal("§d" + virtualCells.size() + " §8虚拟")));
        }

        // ----- scrollable body -----
        int scrollY = titleH + pad + pad;
        var scroll = new DraggableScrollableWidgetGroup(0, scrollY, totalW, scrollH);

        int y = 0;

        // --- Slot for SuperDiskArrayItem ---
        scroll.addWidget(new LabelWidget(leftPad, y,
            Component.literal("§7磁盘阵列:")));
        var slotWidget = new SlotWidget(diskArraySlot.storage, 0,
            leftPad + 60, y - 2, true, true)
            .setBackgroundTexture(GuiTextures.SLOT);
        slotWidget.setHoverTooltips(Component.literal("§7放入 §d超级磁盘阵列"));
        scroll.addWidget(slotWidget);

        if (sdaCell != null) {
            var status = sdaCell.getStatus();
            String s;
            if (status == appeng.api.storage.cells.CellState.EMPTY) s = "§a空";
            else if (status == appeng.api.storage.cells.CellState.NOT_EMPTY) s = "§a有物品";
            else if (status == appeng.api.storage.cells.CellState.TYPES_FULL) s = "§c类型满";
            else if (status == appeng.api.storage.cells.CellState.FULL) s = "§c已满";
            else s = "§7-";
            scroll.addWidget(new LabelWidget(leftPad + 100, y,
                Component.literal("§7状态: " + s)));
        }
        y += 24;

        // --- Virtual cells list ---
        if (!virtualCells.isEmpty()) {
            scroll.addWidget(new LabelWidget(leftPad, y,
                Component.literal("§d虚拟磁盘 §8[" + virtualCells.size() + " 单元]")));
            y += 16;
            for (int i = 0; i < virtualCells.size(); i++) {
                var vc = virtualCells.get(i);
                long used = vc.getBytesUsed();
                long cap = vc.getBytesCapacity();
                int pct = cap > 0 ? (int)(used * 100 / cap) : 0;
                scroll.addWidget(new LabelWidget(leftPad, y + i * 18,
                    Component.literal("§7#" + i + " §8" + vc.getCellType()
                        + " §7" + (used > 0 ? "§e" : "§8") + pct + "%")));
            }
            y += virtualCells.size() * 18 + 8;
        }

        // --- Hatch sections ---
        if (!hatches.isEmpty()) {
            scroll.addWidget(new LabelWidget(leftPad, y, "§7仓室列表"));
            y += 16;
        }
        for (var hatch : hatches) {
            int total = hatch.getDiskSlots().getSlots();
            int used = 0;
            for (int i = 0; i < total; i++) {
                if (!hatch.getDiskSlots().getStackInSlot(i).isEmpty()) used++;
            }
            int pct = total > 0 ? (used * 100 / total) : 0;

            scroll.addWidget(new LabelWidget(leftPad, y,
                Component.literal("§7仓室 §8[" + used + "/" + total + " §b" + pct + "%§8]")));
            y += 16;

            int idx = 0;
            int cols = Math.min(12, total > 0 ? (total + gridRowSize - 1) / gridRowSize : 0);
            for (int cy = 0; cy < cols && idx < total; cy++) {
                for (int cx = 0; cx < gridRowSize && idx < total; cx++) {
                    int si = idx++;
                    scroll.addWidget(new SlotWidget(hatch.getDiskSlots().storage, si,
                        leftPad + cx * cellSize, y + cy * cellSize, true, true)
                        .setBackgroundTexture(GuiTextures.SLOT));
                }
            }
            y += cols * cellSize + 6;
        }

        root.addWidget(scroll);
        return root;
    }

    // ============ 注册 ============

    public static MultiblockMachineDefinition register() {
        var def = GTDishanhaiRegistration.REGISTRATE
            .multiblock("singularity_data_hub", h -> new SingularityDataHubMachine(h))
            .rotationState(RotationState.NON_Y_AXIS)
            .appearanceBlock(() -> DShanhaiBlocks.CASING_TRANSCENDENT.get())
            .pattern(def2 -> DShanhaiMBParser.parseSequence(
                List.of(new ResourceLocation(MOD_ID, "singularity_data_hub")),
                c -> {
                    if (c == ' ') return Predicates.any();
                    if (c == '~') return Predicates.controller(Predicates.blocks(def2.getBlock()));
                    if (c == 'A') return Predicates.blocks(DShanhaiBlocks.CASING_ASSEMBLY.get())
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.MAINTENANCE).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.PARALLEL_HATCH).setPreviewCount(1));
                    if (c == 'B') return Predicates.blocks(DShanhaiBlocks.CASING_MOLECULAR.get());
                    if (c == 'C') return Predicates.blocks(DShanhaiBlocks.CASING_TRANSCENDENT.get());
                    if (c == 'D') return Predicates.blocks(DShanhaiBlocks.CASING_QUANTUM_GLASS.get());
                    if (c == 'E') return Predicates.blocks(DShanhaiBlocks.CASING_RHENIUM.get());
                    if (c == 'F' || c == 'G') return Predicates.blocks(DShanhaiBlocks.CASING_MOLECULAR.get());
                    if (c == 'H' || c == 'I') return Predicates.blocks(DShanhaiBlocks.CASING_TRANSCENDENT.get());
                    if (c == 'J') return Predicates.blocks(DShanhaiBlocks.CASING_RHENIUM.get())
                        .or(Predicates.abilities(ShanhaiPartAbility.ME_DISK_HATCH).setPreviewCount(1));
                    return null;
                }, false, RelativeDirection.BACK, RelativeDirection.UP, RelativeDirection.LEFT))
            .workableCasingRenderer(
                new ResourceLocation(MOD_ID, "block/casing_transcendent"),
                new ResourceLocation(MOD_ID, "block/multiblock/singularity_data_hub"))
            .register();

        def.setTooltipBuilder((stack, tips) -> {
            tips.add(Component.literal("§6§l奇点数据中枢 §r§7- GTNL 移植"));
            tips.add(Component.literal(""));
            tips.add(Component.literal("§7奇点级 ME 网络存储中枢"));
            tips.add(Component.literal("§b支持:"));
            tips.add(Component.literal(" §eME 磁盘仓室 §7(旧)"));
            tips.add(Component.literal(" §d超级磁盘阵列 §7(新)"));
            tips.add(Component.literal(""));
            tips.add(Component.literal("§a操作:"));
            tips.add(Component.literal("§7右键中枢 → 集中管理"));
            tips.add(Component.literal("§7阵列放入槽位后自动登入网络"));
            tips.add(Component.literal("§8原机器 ID: gregtech:gt.blockmachines/21170"));
        });

        return def;
    }
}
