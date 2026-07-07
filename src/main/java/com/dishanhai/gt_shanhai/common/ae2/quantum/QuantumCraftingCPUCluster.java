package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.config.CpuSelectionMode;
import appeng.api.config.Settings;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.events.GridCraftingCpuChange;
import appeng.api.networking.security.IActionSource;
import appeng.api.util.IConfigManager;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.IAECluster;
import appeng.me.cluster.MBCalculator;
import appeng.me.helpers.MachineSource;
import appeng.util.ConfigManager;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class QuantumCraftingCPUCluster implements IAECluster {

    private final BlockPos boundsMin;
    private final BlockPos boundsMax;
    private final HashMap<UUID, QuantumCraftingCPU> activeCpus = new HashMap<>();
    private final List<QuantumCraftingBlockEntity> blockEntities = new ArrayList<>();
    private final ConfigManager configManager = new ConfigManager(this::markDirty);
    private QuantumCraftingCPU remainingStorageCpu;
    private Component name;
    private boolean destroyed;
    private long storage;
    private long remainingStorage;
    private int accelerator;
    private MachineSource source;

    public QuantumCraftingCPUCluster(BlockPos boundsMin, BlockPos boundsMax) {
        this.boundsMin = boundsMin.immutable();
        this.boundsMax = boundsMax.immutable();
        this.configManager.registerSetting(Settings.CPU_SELECTION_MODE, CpuSelectionMode.ANY);
    }

    @Override
    public BlockPos getBoundsMin() {
        return boundsMin;
    }

    @Override
    public BlockPos getBoundsMax() {
        return boundsMax;
    }

    @Override
    public void updateStatus(boolean updateGrid) {
        long start = QuantumDiagnostics.start();
        for (QuantumCraftingBlockEntity blockEntity : blockEntities) {
            blockEntity.updateSubType(true);
        }
        QuantumDiagnostics.slow("cluster.updateStatus", start,
                "blocks=" + blockEntities.size() + " updateGrid=" + updateGrid);
    }

    @Override
    public void destroy() {
        if (destroyed) return;
        QuantumDiagnostics.hit("cluster.destroy", "blocks=" + blockEntities.size());
        destroyed = true;
        boolean ownsModification = !MBCalculator.isModificationInProgress();
        if (ownsModification) {
            MBCalculator.setModificationInProgress(this);
        }
        try {
            updateGridForChangedCpu(null);
        } finally {
            if (ownsModification) {
                MBCalculator.setModificationInProgress(null);
            }
        }
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public Iterator<QuantumCraftingBlockEntity> getBlockEntities() {
        return blockEntities.iterator();
    }

    public int numBlockEntities() {
        return blockEntities.size();
    }

    public int getSizeX() {
        return boundsMax.getX() - boundsMin.getX() + 1;
    }

    public int getSizeY() {
        return boundsMax.getY() - boundsMin.getY() + 1;
    }

    public int getSizeZ() {
        return boundsMax.getZ() - boundsMin.getZ() + 1;
    }

    public int getStructureTier() {
        return Math.max(1, getSizeX() - 2);
    }

    public long getTotalStorage() {
        return storage;
    }

    public int getActiveCpuCount() {
        return activeCpus.size();
    }

    void addBlockEntity(QuantumCraftingBlockEntity blockEntity) {
        QuantumDiagnostics.hit("cluster.addBlockEntity", blockEntity.getBlockPos().toShortString());
        if (source == null && blockEntity.getUnitBlock().getQuantumType() != QuantumCraftingUnitTypes.STRUCTURE) {
            source = new MachineSource(blockEntity);
        } else if (source == null && blockEntity.isCoreBlock()) {
            source = new MachineSource(blockEntity);
        }
        blockEntity.setCoreBlock(false);
        blockEntity.saveChanges();
        blockEntities.add(0, blockEntity);
        storage = saturatedAdd(storage, blockEntity.getStorageBytes());
        accelerator = saturatedAddInt(accelerator, blockEntity.getAcceleratorThreads());
        recalculateRemainingStorage();
    }

    public List<ListCraftingInventory> getInventories() {
        ArrayList<ListCraftingInventory> inventories = new ArrayList<>();
        for (QuantumCraftingCPU cpu : activeCpus.values()) {
            inventories.add(cpu.getInventory());
        }
        return inventories;
    }

    public void cancelJobs() {
        QuantumDiagnostics.hit("cluster.cancelJobs", "active=" + activeCpus.size());
        ArrayList<UUID> ids = new ArrayList<>(activeCpus.keySet());
        for (UUID id : ids) {
            killCpu(id, false);
        }
        updateGridForChangedCpu(this);
    }

    public void cancelJob(UUID id) {
        if (activeCpus.containsKey(id)) {
            killCpu(id, true);
        }
    }

    public ICraftingSubmitResult submitJob(IGrid grid, ICraftingPlan plan, IActionSource src,
            ICraftingRequester requestingMachine) {
        QuantumDiagnostics.hit("cluster.submitJob.start",
                "bytes=" + plan.bytes() + " available=" + getAvailableStorage()
                        + " active=" + activeCpus.size() + " final=" + plan.finalOutput());
        if (!isActive()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (getAvailableStorage() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }
        UUID id = UUID.randomUUID();
        QuantumCraftingCPU cpu = new QuantumCraftingCPU(this, id, plan.bytes());
        ICraftingSubmitResult result = cpu.craftingLogic.trySubmitJob(grid, plan, src, requestingMachine);
        if (result.successful()) {
            activeCpus.put(id, cpu);
            recalculateRemainingStorage();
            markDirty();
            updateGridForChangedCpu(this);
        }
        QuantumDiagnostics.hit("cluster.submitJob.result",
                "success=" + result.successful() + " id=" + id + " active=" + activeCpus.size()
                        + " cpuHasJob=" + cpu.craftingLogic.hasJob());
        return result;
    }

    public List<QuantumCraftingCPU> getActiveCPUs() {
        cleanupInactiveCpus();
        return getActiveCPUSnapshot();
    }

    public List<QuantumCraftingCPU> getActiveCPUSnapshot() {
        // 空闲快速路径：无 CPU 时直接返回共享空列表，避免每 tick 多次 new ArrayList。
        if (activeCpus.isEmpty()) {
            return Collections.emptyList();
        }
        long start = QuantumDiagnostics.start();
        ArrayList<QuantumCraftingCPU> list = new ArrayList<>(activeCpus.size());
        for (QuantumCraftingCPU cpu : activeCpus.values()) {
            if (cpu.craftingLogic.hasJob() || cpu.craftingLogic.isMarkedForDeletion()) {
                list.add(cpu);
            }
        }
        if (QuantumDiagnostics.ENABLED) {
            QuantumDiagnostics.slow("cluster.getActiveCPUSnapshot", start,
                    "stored=" + activeCpus.size() + " active=" + list.size());
        }
        return list;
    }

    public void cleanupInactiveCpus() {
        if (activeCpus.isEmpty()) {
            return;
        }
        long start = QuantumDiagnostics.start();
        ArrayList<UUID> killList = null;
        for (Map.Entry<UUID, QuantumCraftingCPU> entry : activeCpus.entrySet()) {
            QuantumCraftingCPU cpu = entry.getValue();
            if (!cpu.craftingLogic.hasJob() && !cpu.craftingLogic.isMarkedForDeletion()) {
                if (cpu.craftingLogic.hasStoredItems()) {
                    cpu.craftingLogic.markForDeletion();
                    if (QuantumDiagnostics.ENABLED) {
                        QuantumDiagnostics.hit("cluster.cleanupInactiveCpus.markForDeletion",
                                "id=" + entry.getKey() + " stored=true before=" + activeCpus.size());
                    }
                } else {
                    // 延迟分配：仅在真正命中需清理的 CPU 时才建 killList。
                    if (killList == null) {
                        killList = new ArrayList<>();
                    }
                    killList.add(entry.getKey());
                }
            }
        }
        if (killList == null || killList.isEmpty()) return;
        if (QuantumDiagnostics.ENABLED) {
            QuantumDiagnostics.hit("cluster.cleanupInactiveCpus",
                    "remove=" + killList.size() + " before=" + activeCpus.size());
        }
        for (UUID id : killList) {
            activeCpus.remove(id);
        }
        recalculateRemainingStorage();
        updateGridForChangedCpu(this);
        if (QuantumDiagnostics.ENABLED) {
            QuantumDiagnostics.slow("cluster.cleanupInactiveCpus", start,
                    "removed=" + killList.size() + " remaining=" + activeCpus.size());
        }
    }

    public QuantumCraftingCPU getRemainingCapacityCPU() {
        if (remainingStorageCpu == null || remainingStorageCpu.getAvailableStorage() != remainingStorage) {
            remainingStorageCpu = new QuantumCraftingCPU(this, remainingStorage);
        }
        return remainingStorageCpu;
    }

    public void recalculateRemainingStorage() {
        long usedStorage = 0;
        for (QuantumCraftingCPU cpu : activeCpus.values()) {
            usedStorage = saturatedAdd(usedStorage, cpu.getAvailableStorage());
        }
        remainingStorage = Math.max(0, storage - usedStorage);
    }

    public long getAvailableStorage() {
        return remainingStorage;
    }

    public int getCoProcessors() {
        return accelerator;
    }

    @Nullable
    public Component getName() {
        return name;
    }

    @Nullable
    public IGridNode getNode() {
        QuantumCraftingBlockEntity core = getCore();
        if (core != null) {
            return core.getActionableNode();
        }
        return null;
    }

    @Nullable
    public IGrid getGrid() {
        IGridNode node = getNode();
        return node == null ? null : node.getGrid();
    }

    public boolean isActive() {
        IGridNode node = getNode();
        return node != null && node.isActive();
    }

    public IActionSource getSrc() {
        return Objects.requireNonNull(source);
    }

    @Nullable
    public QuantumCraftingBlockEntity getCoreBlockEntity() {
        return getCore();
    }

    public Level getLevel() {
        return getCore().getLevel();
    }

    public void updateOutput(appeng.api.stacks.GenericStack output) {
    }

    public void markDirty() {
        QuantumCraftingBlockEntity core = getCore();
        if (core != null) {
            core.saveChanges();
        }
    }

    void done() {
        QuantumCraftingBlockEntity core = getCore();
        if (core == null) return;
        core.setCoreBlock(true);
        if (core.getPreviousState() != null) {
            readFromNBT(core.getPreviousState());
            core.setPreviousState(null);
        }
        updateName();
        core.saveChanges();
    }

    public void updateName() {
        name = null;
        for (QuantumCraftingBlockEntity blockEntity : blockEntities) {
            if (blockEntity.hasCustomName()) {
                if (name != null) {
                    name = name.copy().append(" ").append(blockEntity.getCustomName());
                } else {
                    name = blockEntity.getCustomName().copy();
                }
            }
        }
    }

    public void breakCluster() {
        QuantumCraftingBlockEntity core = getCore();
        if (core != null) {
            core.breakCluster();
        }
    }

    public CpuSelectionMode getSelectionMode() {
        return configManager.getSetting(Settings.CPU_SELECTION_MODE);
    }

    public IConfigManager getConfigManager() {
        return configManager;
    }

    public boolean canBeAutoSelectedFor(IActionSource source) {
        // 用直接枚举比较代替 switch(enum)，避免编译器生成合成 switch-map 类（$1）。
        // 在 Forge 模块化 ClassLoader 下该合成类会加载失败，抛 NoClassDefFoundError。
        CpuSelectionMode mode = getSelectionMode();
        if (mode == CpuSelectionMode.PLAYER_ONLY) {
            return source.player().isPresent();
        }
        if (mode == CpuSelectionMode.MACHINE_ONLY) {
            return source.player().isEmpty();
        }
        // ANY 及其它情况
        return true;
    }

    public boolean isPreferredFor(IActionSource source) {
        CpuSelectionMode mode = getSelectionMode();
        if (mode == CpuSelectionMode.PLAYER_ONLY) {
            return source.player().isPresent();
        }
        if (mode == CpuSelectionMode.MACHINE_ONLY) {
            return source.player().isEmpty();
        }
        return false;
    }

    public void writeToNBT(CompoundTag data) {
        ListTag cpuList = new ListTag();
        for (Map.Entry<UUID, QuantumCraftingCPU> entry : activeCpus.entrySet()) {
            CompoundTag pair = new CompoundTag();
            pair.put("key", StringTag.valueOf(entry.getKey().toString()));
            pair.put("bytes", LongTag.valueOf(entry.getValue().getAvailableStorage()));
            CompoundTag cpuTag = new CompoundTag();
            entry.getValue().writeToNBT(cpuTag);
            pair.put("cpu", cpuTag);
            cpuList.add(pair);
        }
        data.put("cpuList", cpuList);
        configManager.writeToNBT(data);
        QuantumDiagnostics.hit("cluster.writeToNBT",
                "active=" + activeCpus.size() + " list=" + cpuList.size()
                        + " storage=" + storage + " remaining=" + remainingStorage);
    }

    public void readFromNBT(CompoundTag data) {
        activeCpus.clear();
        ListTag cpuList = data.getList("cpuList", 10);
        for (int i = 0; i < cpuList.size(); i++) {
            CompoundTag pair = cpuList.getCompound(i);
            UUID id;
            try {
                id = UUID.fromString(pair.getString("key"));
            } catch (IllegalArgumentException ignored) {
                id = UUID.randomUUID();
            }
            QuantumCraftingCPU cpu = new QuantumCraftingCPU(this, id, pair.getLong("bytes"));
            cpu.readFromNBT(pair.getCompound("cpu"));
            activeCpus.put(id, cpu);
        }
        configManager.readFromNBT(data);
        recalculateRemainingStorage();
        markDirty();
        updateGridForChangedCpu(this);
        QuantumDiagnostics.hit("cluster.readFromNBT",
                "list=" + cpuList.size() + " active=" + activeCpus.size()
                        + " storage=" + storage + " remaining=" + remainingStorage);
    }

    protected void deactivate(UUID id) {
        QuantumDiagnostics.hit("cluster.deactivate",
                "id=" + id + " exists=" + activeCpus.containsKey(id));
        activeCpus.remove(id);
        recalculateRemainingStorage();
        markDirty();
        updateGridForChangedCpu(this);
    }

    private void killCpu(UUID id, boolean updateGrid) {
        QuantumCraftingCPU cpu = activeCpus.get(id);
        if (cpu == null) return;
        QuantumDiagnostics.hit("cluster.killCpu",
                "id=" + id + " updateGrid=" + updateGrid + " hasJob=" + cpu.craftingLogic.hasJob()
                        + " hasStored=" + cpu.craftingLogic.hasStoredItems());
        cpu.craftingLogic.cancel();
        if (cpu.craftingLogic.hasStoredItems()) {
            cpu.craftingLogic.markForDeletion();
        } else {
            activeCpus.remove(id);
        }
        recalculateRemainingStorage();
        markDirty();
        if (updateGrid) {
            updateGridForChangedCpu(this);
        }
    }

    private void updateGridForChangedCpu(QuantumCraftingCPUCluster cluster) {
        long start = QuantumDiagnostics.start();
        boolean posted = false;
        for (QuantumCraftingBlockEntity blockEntity : blockEntities) {
            IGridNode node = blockEntity.getActionableNode();
            if (node != null && !posted) {
                node.getGrid().postEvent(new GridCraftingCpuChange(node));
                posted = true;
            }
            blockEntity.updateStatus(cluster);
        }
        QuantumDiagnostics.slow("cluster.updateGridForChangedCpu", start,
                "blocks=" + blockEntities.size() + " posted=" + posted
                        + " cluster=" + (cluster == null ? "null" : "set"));
    }

    private QuantumCraftingBlockEntity getCore() {
        if (source == null) return null;
        return (QuantumCraftingBlockEntity) source.machine().get();
    }

    private static long saturatedAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static int saturatedAddInt(int a, int b) {
        long result = (long) a + (long) b;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }
}
