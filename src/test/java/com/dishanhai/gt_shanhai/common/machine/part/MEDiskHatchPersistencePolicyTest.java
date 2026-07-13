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
