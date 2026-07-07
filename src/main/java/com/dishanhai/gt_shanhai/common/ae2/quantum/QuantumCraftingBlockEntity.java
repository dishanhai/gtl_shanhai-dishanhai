package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.implementations.IPowerChannelState;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.orientation.BlockOrientation;
import appeng.api.stacks.AEKey;
import appeng.api.util.IConfigManager;
import appeng.api.util.IConfigurableObject;
import appeng.blockentity.crafting.CraftingCubeModelData;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.IAEMultiBlock;
import appeng.util.NullConfigManager;
import appeng.util.Platform;
import appeng.util.iterators.ChainedIterator;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class QuantumCraftingBlockEntity extends AENetworkBlockEntity
        implements IAEMultiBlock<QuantumCraftingCPUCluster>, IPowerChannelState, IConfigurableObject {

    private final QuantumCraftingCPUCalculator calculator;
    private CompoundTag previousState;
    private boolean coreBlock;
    private QuantumCraftingCPUCluster cluster;

    public QuantumCraftingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.calculator = new QuantumCraftingCPUCalculator(this);
        getMainNode().setFlags(GridFlags.MULTIBLOCK, GridFlags.REQUIRE_CHANNEL)
                .addService(IGridMultiblock.class, this::getMultiblockNodes);
    }

    @Override
    protected Item getItemFromBlockEntity() {
        if (level == null) {
            return Items.AIR;
        }
        return getUnitBlock().getQuantumType().getItemFromType();
    }

    @Override
    public void setName(String name) {
        super.setName(name);
        QuantumCraftingCPUCluster liveCluster = getLiveCluster();
        if (liveCluster != null) {
            liveCluster.updateName();
        }
    }

    public QuantumCraftingUnitBlock getUnitBlock() {
        if (level == null || isRemoved()) {
            return QuantumCraftingUnitTypesBlockAccess.getFallbackBlock();
        }
        if (level.getBlockState(worldPosition).getBlock() instanceof QuantumCraftingUnitBlock) {
            return (QuantumCraftingUnitBlock) level.getBlockState(worldPosition).getBlock();
        }
        return QuantumCraftingUnitTypesBlockAccess.getFallbackBlock();
    }

    public long getStorageBytes() {
        return getUnitBlock().getQuantumType().getStorageBytes();
    }

    public int getAcceleratorThreads() {
        return getUnitBlock().getQuantumType().getAcceleratorThreads();
    }

    @Override
    public void onReady() {
        super.onReady();
        getMainNode().setVisualRepresentation(getItemFromBlockEntity());
        if (level instanceof ServerLevel) {
            long start = QuantumDiagnostics.start();
            calculator.calculateMultiblock((ServerLevel) level, worldPosition);
            QuantumDiagnostics.slow("block.onReady.calculateMultiblock", start, worldPosition.toShortString());
        }
    }

    public void updateMultiBlock(BlockPos changedPos) {
        if (level instanceof ServerLevel) {
            long start = QuantumDiagnostics.start();
            calculator.updateMultiblockAfterNeighborUpdate((ServerLevel) level, worldPosition, changedPos);
            QuantumDiagnostics.slow("block.updateMultiBlock", start,
                    "pos=" + worldPosition.toShortString() + " changed=" + changedPos.toShortString());
        }
    }

    public void updateStatus(QuantumCraftingCPUCluster newCluster) {
        QuantumDiagnostics.hit("block.updateStatus",
                "pos=" + worldPosition.toShortString() + " cluster=" + (newCluster == null ? "null" : "set"));
        QuantumCraftingCPUCluster liveCluster = getLiveCluster();
        boolean clusterChanged = liveCluster != newCluster;
        if (newCluster != null && liveCluster != null && clusterChanged) {
            liveCluster.breakCluster();
        }
        cluster = newCluster;
        updateSubType(true);
    }

    public void updateSubType(boolean updateFormed) {
        if (level == null || notLoaded() || isRemoved()) return;
        long start = QuantumDiagnostics.start();

        boolean formed = isFormed();
        boolean powered = getMainNode().isOnline();
        BlockState current = level.getBlockState(worldPosition);
        if (current.getBlock() instanceof QuantumCraftingUnitBlock) {
            QuantumCraftingUnitType type = getUnitBlock().getQuantumType();
            int lightLevel = type == QuantumCraftingUnitTypes.COMPUTER_UNIT ? 12 : 0;
            int activeLightLevel = formed && powered ? lightLevel : 0;
            boolean multiblocked = getLiveCluster() != null && getLiveCluster().numBlockEntities() > 1;
            BlockState next = current.setValue(QuantumCraftingUnitBlock.POWERED, powered)
                    .setValue(QuantumCraftingUnitBlock.FORMED, formed)
                    .setValue(QuantumCraftingUnitBlock.LIGHT_LEVEL, activeLightLevel)
                    .setValue(QuantumCraftingUnitBlock.MULTIBLOCKED, multiblocked);
            if (!current.equals(next)) {
                level.setBlock(worldPosition, next, 2);
            }
        }
        if (updateFormed) {
            onGridConnectableSidesChanged();
        }
        QuantumDiagnostics.slow("block.updateSubType", start,
                "pos=" + worldPosition.toShortString() + " formed=" + formed + " powered=" + powered
                        + " updateFormed=" + updateFormed);
    }

    @Override
    public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        if (isFormed()) {
            if (getUnitBlock().getQuantumType() == QuantumCraftingUnitTypes.COMPUTER_UNIT) {
                return EnumSet.of(Direction.UP, Direction.DOWN);
            }
            return EnumSet.allOf(Direction.class);
        }
        return EnumSet.noneOf(Direction.class);
    }

    public boolean isFormed() {
        if (isClientSide()) {
            return getBlockState().getValue(QuantumCraftingUnitBlock.FORMED);
        }
        return getLiveCluster() != null;
    }

    @Override
    public void saveAdditional(CompoundTag data) {
        super.saveAdditional(data);
        data.putBoolean("core", isCoreBlock());
        QuantumCraftingCPUCluster liveCluster = getLiveCluster();
        if (isCoreBlock() && liveCluster != null) {
            liveCluster.writeToNBT(data);
        }
        QuantumDiagnostics.hit("block.saveAdditional",
                "pos=" + worldPosition.toShortString() + " core=" + isCoreBlock()
                        + " hasCluster=" + (liveCluster != null)
                        + " cpuList=" + data.getList("cpuList", 10).size());
    }

    @Override
    public void loadTag(CompoundTag data) {
        super.loadTag(data);
        setCoreBlock(data.getBoolean("core"));
        QuantumDiagnostics.hit("block.loadTag",
                "pos=" + worldPosition.toShortString() + " core=" + isCoreBlock()
                        + " hasCluster=" + (getLiveCluster() != null)
                        + " cpuList=" + data.getList("cpuList", 10).size());
        if (isCoreBlock()) {
            QuantumCraftingCPUCluster liveCluster = getLiveCluster();
            if (liveCluster != null) {
                liveCluster.readFromNBT(data);
            } else {
                previousState = data.copy();
            }
        }
    }

    @Override
    public void disconnect(boolean update) {
        QuantumCraftingCPUCluster liveCluster = getLiveCluster();
        if (liveCluster != null) {
            cluster = null;
            liveCluster.destroy();
            if (update) {
                updateSubType(true);
            }
        }
    }

    @Override
    public QuantumCraftingCPUCluster getCluster() {
        return getLiveCluster();
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        if (reason != IGridNodeListener.State.GRID_BOOT) {
            updateSubType(false);
        }
    }

    public void breakCluster() {
        QuantumCraftingCPUCluster liveCluster = getLiveCluster();
        if (liveCluster == null) return;
        QuantumDiagnostics.hit("block.breakCluster", worldPosition.toShortString());

        liveCluster.cancelJobs();
        List<ListCraftingInventory> inventories = liveCluster.getInventories();
        ArrayList<BlockPos> places = new ArrayList<>();
        Iterable<QuantumCraftingBlockEntity> blockEntities = () -> liveCluster.getBlockEntities();
        for (QuantumCraftingBlockEntity blockEntity : blockEntities) {
            if (this == blockEntity) {
                places.add(worldPosition);
            } else {
                for (Direction direction : Direction.values()) {
                    BlockPos pos = blockEntity.worldPosition.relative(direction);
                    if (level != null && level.isEmptyBlock(pos)) {
                        places.add(pos);
                    }
                }
            }
        }
        if (places.isEmpty()) {
            throw new IllegalStateException(liveCluster + " does not contain any blocks to drop inventory near.");
        }
        for (ListCraftingInventory inventory : inventories) {
            for (Object2LongMap.Entry<AEKey> entry : inventory.list) {
                BlockPos pos = Util.getRandom(places, level.random);
                ArrayList<ItemStack> stacks = new ArrayList<>();
                entry.getKey().addDrops(entry.getLongValue(), stacks, level, pos);
                Platform.spawnDrops(level, pos, stacks);
            }
            inventory.clear();
        }
        cluster = null;
        liveCluster.destroy();
    }

    @Override
    public boolean isPowered() {
        if (isClientSide()) {
            return getBlockState().getValue(QuantumCraftingUnitBlock.POWERED);
        }
        return getMainNode().isActive();
    }

    @Override
    public boolean isActive() {
        if (isClientSide()) {
            return isPowered() && isFormed();
        }
        return isFormed() && getMainNode().isActive();
    }

    public boolean isCoreBlock() {
        return coreBlock;
    }

    public void setCoreBlock(boolean coreBlock) {
        this.coreBlock = coreBlock;
    }

    public CompoundTag getPreviousState() {
        return previousState;
    }

    public void setPreviousState(CompoundTag previousState) {
        this.previousState = previousState;
    }

    @Override
    public ModelData getModelData() {
        return CraftingCubeModelData.create(getConnections());
    }

    protected EnumSet<Direction> getConnections() {
        if (level == null) {
            return EnumSet.noneOf(Direction.class);
        }
        EnumSet<Direction> connections = EnumSet.noneOf(Direction.class);
        for (Direction direction : Direction.values()) {
            if (isConnected(level, worldPosition, direction)) {
                connections.add(direction);
            }
        }
        return connections;
    }

    private boolean isConnected(BlockGetter level, BlockPos pos, Direction side) {
        return level.getBlockState(pos.relative(side)).getBlock() instanceof QuantumCraftingUnitBlock;
    }

    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        requestModelDataUpdate();
    }

    private Iterator<IGridNode> getMultiblockNodes() {
        QuantumCraftingCPUCluster liveCluster = getLiveCluster();
        if (liveCluster == null) {
            return java.util.Collections.emptyIterator();
        }
        long start = QuantumDiagnostics.start();
        ArrayList<IGridNode> nodes = new ArrayList<>();
        Iterator<QuantumCraftingBlockEntity> iterator = liveCluster.getBlockEntities();
        while (iterator.hasNext()) {
            IGridNode node = iterator.next().getGridNode();
            if (node != null) {
                nodes.add(node);
            }
        }
        QuantumDiagnostics.slow("block.getMultiblockNodes", start,
                "pos=" + worldPosition.toShortString() + " nodes=" + nodes.size());
        return nodes.iterator();
    }

    @Override
    public IConfigManager getConfigManager() {
        QuantumCraftingCPUCluster liveCluster = getLiveCluster();
        if (liveCluster != null) {
            return liveCluster.getConfigManager();
        }
        return NullConfigManager.INSTANCE;
    }

    private QuantumCraftingCPUCluster getLiveCluster() {
        if (cluster != null && cluster.isDestroyed()) {
            cluster = null;
        }
        return cluster;
    }

    private static final class QuantumCraftingUnitTypesBlockAccess {
        private static QuantumCraftingUnitBlock getFallbackBlock() {
            return com.dishanhai.gt_shanhai.common.block.DShanhaiAE2Blocks.QUANTUM_STRUCTURE.get();
        }
    }
}
