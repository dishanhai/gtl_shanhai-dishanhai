package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MEDiskHatchPersistencePolicyTest {

    private static final Path HATCH_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "MEDiskHatchPartMachine.java");
    private static final Path DROP_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "misc", "DiskMachineDropHandler.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path STORAGE_SERVICE_DELTA_CACHE_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "StorageServiceDeltaCacheMixin.java");
    private static final Path ME_STORAGE_MENU_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "MEStorageMenuBroadcastOptimizationMixin.java");

    @Test
    void mountedCellsAlwaysReceiveAHostSaveProvider() throws IOException {
        String source = Files.readString(HATCH_SOURCE);

        assertFalse(source.contains("StorageCells.getCellInventory(stack, null)"),
                "顶层磁盘不能选择 AE2 的每次修改立即持久化模式");
        assertFalse(source.contains("StorageCells.getCellInventory(innerStack, null)"),
                "嵌套磁盘也必须把修改归属到对应槽位宿主");
        assertTrue(source.contains("StorageCells.getCellInventory(stack, slotSaveProvider)"));
    }

    @Test
    void slotSerializationUnloadAndBreakAllForcePendingCellsToPersist() throws IOException {
        String hatch = Files.readString(HATCH_SOURCE);
        String drop = Files.readString(DROP_SOURCE);

        assertTrue(hatch.contains("final class DiskSlotTransfer"));
        assertTrue(hatch.contains("public CompoundTag serializeNBT()"));
        assertTrue(hatch.contains("public void onUnload()"));
        assertTrue(drop.contains("hatch.forcePersistAll();"));
    }

    @Test
    void networkStorageIsNeverScannedByTheBackgroundFastClearMixin() throws IOException {
        String mixinConfig = Files.readString(MIXIN_CONFIG);

        assertFalse(mixinConfig.contains("StorageServiceFastClearMixin"),
                "AE2 NetworkStorage 不是线程安全容器，不能从后台线程全量扫描");
    }

    @Test
    void storageServiceKeepsMenuCacheWarmWhenNoWatcherExists() throws IOException {
        String source = Files.readString(STORAGE_SERVICE_DELTA_CACHE_MIXIN);

        assertTrue(source.contains("method = \"onServerEndTick\""),
                "无 watcher 的 AE2 StorageService tick 必须由山海缓存逻辑接管");
        assertTrue(source.contains("this.interestManager.isEmpty()"),
                "只接管原版 watcher 为空时的无条件判脏分支");
        assertTrue(source.contains("if (!this.cachedStacksNeedUpdate)"),
                "外部显式 invalidateCache 后仍必须保留下一次全量同步");
        assertTrue(source.contains("ci.cancel();"),
                "接管分支必须阻止原版每 tick 把 cachedStacksNeedUpdate 重新置 true");
    }

    @Test
    void storageMutationsInvalidateInsteadOfApplyingUnsafeArithmeticDeltas() throws IOException {
        String source = Files.readString(STORAGE_SERVICE_DELTA_CACHE_MIXIN);

        assertTrue(source.contains("this.cachedStacksNeedUpdate = true;"),
                "真实存取后必须把 AE2 可用库存缓存标脏");
        assertFalse(source.contains("gtShanhai$applyPendingDeltas"),
                "无限盘和创造存储的返回量不代表库存变化量，不能直接增减缓存");
        assertFalse(source.contains("gtShanhai$saturatedAdd"),
                "不得继续用算术 delta 修补特殊存储的可用数量");
    }

    @Test
    void wirelessTerminalMenuUsesItsActionHostToReachStorageServiceCache() throws IOException {
        String source = Files.readString(ME_STORAGE_MENU_MIXIN);

        assertTrue(source.contains("@Shadow @Final private ITerminalHost host;"),
                "菜单必须保留无线终端 host，不能只依赖构造器未设置的 networkNode");
        assertTrue(source.contains("this.host instanceof IActionHost actionHost"),
                "IPortableTerminal 同时实现 IActionHost 时必须从 host 获取网格节点");
        assertTrue(source.contains("node = actionHost.getActionableNode();"),
                "无线终端必须通过 actionable node 进入 StorageService 缓存");
    }

    @Test
    void nestedCellCarrierUpdatesAreAtomicAndSimulationHasNoWriteSideEffects() throws IOException {
        String source = Files.readString(HATCH_SOURCE);

        assertFalse(source.contains("simulatedCell.insert(what, amount, Actionable.MODULATE"),
                "容量预检不能在共享 UUID 的临时 SDA 上执行真实写入");
        assertFalse(source.contains("parent.extract(currentCarrierKey, 1, Actionable.MODULATE"),
                "嵌套载体不能先删除再尝试插回，否则插入失败会直接丢载体");
        assertTrue(source.contains("replaceOneStoredKey"),
                "嵌套载体 NBT 变化必须通过父 SDA 的原子换键接口提交");
    }

    @Test
    void slotStackLimitSimulationCannotEvictMountedRuntime() throws IOException {
        String source = Files.readString(HATCH_SOURCE);

        assertFalse(source.contains("public void setStackInSlot(int slot, ItemStack stack)"),
                "SlotWidget 会临时 set 空槽再恢复来计算堆叠上限，不能在 setStackInSlot 中强刷并失效运行态");
    }
}
