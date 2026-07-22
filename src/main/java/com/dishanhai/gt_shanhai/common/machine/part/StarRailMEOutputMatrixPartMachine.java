package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Higher throughput variant of the phase output matrix.
 * Recipe outputs are still confirmed only after entering the persisted buffer.
 */
public class StarRailMEOutputMatrixPartMachine extends ReliableMEAsyncOutputPartMachine {

    private static final int LOW_OPERATIONS_PER_TICK = 128;
    private static final int MID_OPERATIONS_PER_TICK = 256;
    private static final int HIGH_OPERATIONS_PER_TICK = 512;
    private static final int LOW_SCAN_PER_TICK = 512;
    private static final int MID_SCAN_PER_TICK = 1024;
    private static final int HIGH_SCAN_PER_TICK = 2048;
    private static final int MID_BUFFER_SIZE = 256;
    private static final int HIGH_BUFFER_SIZE = 1024;
    private static final int MAX_CONSECUTIVE_FAILURES = 16;
    private static final int FULL_NETWORK_COOLDOWN_TICKS = 20;
    private static final int FAILED_KEY_COOLDOWN_TICKS = 10;
    private static final int COOLING_SCAN_BACKOFF_TICKS = 5;
    private int fullNetworkCooldown;
    private long starRailTick;
    private boolean lastFlushDidWork;
    private boolean lastFlushHitFailureLimit;
    private int lastFlushScannedKeys;
    private int lastFlushCoolingSkippedKeys;
    private long lastFlushMinCoolingRemaining;
    private final Object2LongOpenHashMap<AEKey> failedKeyCooldownUntil = new Object2LongOpenHashMap<>();
    private OutputMode outputMode = OutputMode.BALANCED;
    private IGrid cachedGrid;
    private MEStorage cachedNetworkInventory;
    private IEnergyService cachedEnergy;

    public StarRailMEOutputMatrixPartMachine(IMachineBlockEntity holder) {
        super(holder);
        // 冷却值恒为 starRailTick + N（>0），用默认返回值 0 表示"无冷却"，
        // 从而在扫描热路径上用单次 getLong 取代 containsKey + getLong 的双查。
        failedKeyCooldownUntil.defaultReturnValue(0L);
    }

    @Override
    protected void registerDefaultServices() {
        getMainNode().addService(IGridTickable.class, new StarRailTicker());
    }

    @Override
    public String getAeJadeKind() {
        return "ME 星轨输出矩阵";
    }

    @Override
    public String getAeOutputModeName() {
        return outputMode.displayName;
    }

    @Override
    public int getAeFailedKeyCooldowns() {
        return failedKeyCooldownUntil.size();
    }

    @Override
    public int getAeNetworkCooldownTicks() {
        return fullNetworkCooldown;
    }

    @Override
    public String getAeFlushBudgetText() {
        int bufferSize = buffer.size();
        return getMaxOperationsForBuffer(bufferSize) + "/" + getMaxScansForBuffer(bufferSize);
    }

    @Override
    public boolean isAeServiceCacheReady() {
        return cachedGrid != null && cachedNetworkInventory != null && cachedEnergy != null;
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putInt("StarRailOutputMode", outputMode.ordinal());
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains("StarRailOutputMode")) {
            outputMode = OutputMode.byOrdinal(tag.getInt("StarRailOutputMode"));
        }
    }

    @Override
    protected InteractionResult onScrewdriverClick(Player player, InteractionHand hand, Direction dir, BlockHitResult hit) {
        if (getLevel() != null && !getLevel().isClientSide) {
            outputMode = outputMode.next();
            player.displayClientMessage(Component.literal("§dME 星轨输出矩阵模式: §f" + outputMode.displayName), true);
        }
        return InteractionResult.SUCCESS;
    }

    protected class StarRailTicker implements IGridTickable {

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(1, 5, false, true);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            starRailTick++;
            if (!getMainNode().isActive()) {
                isSleeping = true;
                clearCachedAeServices();
                return TickRateModulation.SLEEP;
            }
            if (buffer.isEmpty()) {
                fullNetworkCooldown = 0;
                failedKeyCooldownUntil.clear();
                if (ticksSinceLastCall >= 5) {
                    isSleeping = true;
                    clearCachedAeServices();
                    return TickRateModulation.SLEEP;
                }
                return TickRateModulation.SLOWER;
            }
            if (fullNetworkCooldown > 0) {
                fullNetworkCooldown--;
                return TickRateModulation.SLOWER;
            }

            flushStarRailBuffer();
            if (!lastFlushDidWork && lastFlushHitFailureLimit) {
                fullNetworkCooldown = FULL_NETWORK_COOLDOWN_TICKS;
            } else if (!lastFlushDidWork && shouldBackoffCoolingOnlyScan()) {
                fullNetworkCooldown = coolingOnlyBackoffTicks();
            }
            return lastFlushDidWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
        }
    }

    private void flushStarRailBuffer() {
        lastFlushDidWork = false;
        lastFlushHitFailureLimit = false;
        lastFlushScannedKeys = 0;
        lastFlushCoolingSkippedKeys = 0;
        lastFlushMinCoolingRemaining = Long.MAX_VALUE;
        if (buffer.isEmpty() || !refreshCachedAeServices()) {
            return;
        }

        int bufferSize = buffer.size();
        int maxOperations = getMaxOperationsForBuffer(bufferSize);
        // 所有 flush 阶段共享同一扫描预算，避免 ITEMS_FIRST/FLUIDS_FIRST/SMALL_FIRST
        // 模式下多次 flushByMode 各自独立拿满 maxScans 导致重复扫描缓冲区
        int[] scanBudget = {getMaxScansForBuffer(bufferSize)};
        int operations = 0;

        if (outputMode == OutputMode.SMALL_FIRST && flushSmallestCandidate(scanBudget)) {
            operations++;
        }

        boolean typePreferredMode = outputMode == OutputMode.ITEMS_FIRST || outputMode == OutputMode.FLUIDS_FIRST;
        if (typePreferredMode) {
            operations += flushByMode(maxOperations - operations, scanBudget, true);
        }
        if (operations >= maxOperations) {
            return;
        }

        operations += flushByMode(maxOperations - operations, scanBudget, false);
    }

    private int flushByMode(int operationBudget, int[] scanBudget, boolean preferredOnly) {
        if (operationBudget <= 0 || scanBudget[0] <= 0) {
            return 0;
        }
        int operations = 0;
        int consecutiveFailures = 0;

        ObjectIterator<Object2LongMap.Entry<AEKey>> it = Object2LongMaps.fastIterator(buffer);
        while (it.hasNext() && operations < operationBudget && scanBudget[0] > 0) {
            Object2LongMap.Entry<AEKey> entry = it.next();
            scanBudget[0]--;
            lastFlushScannedKeys++;
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            if (amount <= 0) {
                it.remove();
                failedKeyCooldownUntil.removeLong(key);
                continue;
            }
            long cooldownUntil = failedKeyCooldownUntil.getLong(key);
            if (cooldownUntil != 0L) {
                if (cooldownUntil > starRailTick) {
                    recordCoolingSkip(cooldownUntil);
                    continue;
                }
                failedKeyCooldownUntil.removeLong(key);
            }
            if (preferredOnly && !outputMode.acceptsPreferred(key)) {
                continue;
            }

            long inserted = StorageHelper.poweredInsert(cachedEnergy, cachedNetworkInventory, key, amount, actionSource);
            operations++;
            if (inserted > 0) {
                lastFlushDidWork = true;
                consecutiveFailures = 0;
                failedKeyCooldownUntil.removeLong(key);
                long left = amount - inserted;
                if (left <= 0) {
                    it.remove();
                } else {
                    entry.setValue(left);
                }
                continue;
            }

            failedKeyCooldownUntil.put(key, starRailTick + FAILED_KEY_COOLDOWN_TICKS);
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                lastFlushHitFailureLimit = true;
                return operations;
            }
        }
        return operations;
    }

    private boolean flushSmallestCandidate(int[] scanBudget) {
        AEKey smallestKey = null;
        long smallestAmount = Long.MAX_VALUE;

        ObjectIterator<Object2LongMap.Entry<AEKey>> it = Object2LongMaps.fastIterator(buffer);
        while (it.hasNext() && scanBudget[0] > 0) {
            Object2LongMap.Entry<AEKey> entry = it.next();
            scanBudget[0]--;
            lastFlushScannedKeys++;
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            if (amount <= 0 || amount >= smallestAmount) {
                continue;
            }
            long cooldownUntil = failedKeyCooldownUntil.getLong(key);
            if (cooldownUntil > starRailTick) {
                recordCoolingSkip(cooldownUntil);
                continue;
            }
            smallestKey = key;
            smallestAmount = amount;
        }
        if (smallestKey == null) {
            return false;
        }

        long inserted = StorageHelper.poweredInsert(cachedEnergy, cachedNetworkInventory, smallestKey, smallestAmount, actionSource);
        if (inserted > 0) {
            lastFlushDidWork = true;
            failedKeyCooldownUntil.removeLong(smallestKey);
            long left = smallestAmount - inserted;
            if (left <= 0) {
                buffer.removeLong(smallestKey);
            } else {
                buffer.put(smallestKey, left);
            }
            return true;
        }
        failedKeyCooldownUntil.put(smallestKey, starRailTick + FAILED_KEY_COOLDOWN_TICKS);
        return false;
    }

    private void recordCoolingSkip(long cooldownUntil) {
        lastFlushCoolingSkippedKeys++;
        long remaining = cooldownUntil - starRailTick;
        if (remaining > 0L && remaining < lastFlushMinCoolingRemaining) {
            lastFlushMinCoolingRemaining = remaining;
        }
    }

    private boolean shouldBackoffCoolingOnlyScan() {
        return lastFlushScannedKeys > 0
                && lastFlushCoolingSkippedKeys >= lastFlushScannedKeys
                && failedKeyCooldownUntil.size() >= buffer.size();
    }

    private int coolingOnlyBackoffTicks() {
        if (lastFlushMinCoolingRemaining == Long.MAX_VALUE) {
            return COOLING_SCAN_BACKOFF_TICKS;
        }
        long ticks = Math.min(COOLING_SCAN_BACKOFF_TICKS, lastFlushMinCoolingRemaining);
        return (int) Math.max(1L, ticks);
    }

    private int getMaxOperationsForBuffer(int bufferSize) {
        if (bufferSize >= HIGH_BUFFER_SIZE) {
            return HIGH_OPERATIONS_PER_TICK;
        }
        if (bufferSize >= MID_BUFFER_SIZE) {
            return MID_OPERATIONS_PER_TICK;
        }
        return LOW_OPERATIONS_PER_TICK;
    }

    private int getMaxScansForBuffer(int bufferSize) {
        if (bufferSize >= HIGH_BUFFER_SIZE) {
            return HIGH_SCAN_PER_TICK;
        }
        if (bufferSize >= MID_BUFFER_SIZE) {
            return MID_SCAN_PER_TICK;
        }
        return LOW_SCAN_PER_TICK;
    }

    private boolean refreshCachedAeServices() {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            clearCachedAeServices();
            return false;
        }
        if (grid != cachedGrid || cachedNetworkInventory == null || cachedEnergy == null) {
            if (grid != cachedGrid) {
                failedKeyCooldownUntil.clear();
                fullNetworkCooldown = 0;
            }
            cachedGrid = grid;
            cachedNetworkInventory = grid.getStorageService().getInventory();
            cachedEnergy = grid.getEnergyService();
        }
        return true;
    }

    private void clearCachedAeServices() {
        cachedGrid = null;
        cachedNetworkInventory = null;
        cachedEnergy = null;
    }

    private enum OutputMode {
        BALANCED("均衡"),
        ITEMS_FIRST("物品优先"),
        FLUIDS_FIRST("流体优先"),
        SMALL_FIRST("小堆优先");

        private final String displayName;

        OutputMode(String displayName) {
            this.displayName = displayName;
        }

        private OutputMode next() {
            OutputMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        private boolean acceptsPreferred(AEKey key) {
            if (this == ITEMS_FIRST) {
                return key instanceof AEItemKey;
            }
            if (this == FLUIDS_FIRST) {
                return key instanceof AEFluidKey;
            }
            return true;
        }

        private static OutputMode byOrdinal(int ordinal) {
            OutputMode[] values = values();
            if (ordinal < 0 || ordinal >= values.length) {
                return BALANCED;
            }
            return values[ordinal];
        }
    }
}
