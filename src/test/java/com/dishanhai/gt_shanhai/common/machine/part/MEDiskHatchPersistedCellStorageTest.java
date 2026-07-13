package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.config.Actionable;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MEDiskHatchPersistedCellStorageTest {

    @Test
    void successfulInsertDefersPersistenceToOwner() {
        RecordingCell cell = new RecordingCell();
        AtomicInteger ownerSaveCalls = new AtomicInteger();
        MEDiskHatchPartMachine.PersistedCellStorage storage =
                new MEDiskHatchPartMachine.PersistedCellStorage(cell, ownerSaveCalls::incrementAndGet);

        long inserted = storage.insert(null, 64, Actionable.MODULATE, null);

        assertEquals(64, inserted);
        assertEquals(0, cell.persistCalls,
                "高频存取路径不应立即序列化磁盘，应由宿主在安全落盘点统一 persist");
        assertEquals(1, ownerSaveCalls.get());
    }

    @Test
    void successfulExtractDefersPersistenceToOwner() {
        RecordingCell cell = new RecordingCell();
        AtomicInteger ownerSaveCalls = new AtomicInteger();
        MEDiskHatchPartMachine.PersistedCellStorage storage =
                new MEDiskHatchPartMachine.PersistedCellStorage(cell, ownerSaveCalls::incrementAndGet);

        long extracted = storage.extract(null, 64, Actionable.MODULATE, null);

        assertEquals(64, extracted);
        assertEquals(0, cell.persistCalls,
                "高频抽取路径不应立即全量序列化 BigInteger 磁盘");
        assertEquals(1, ownerSaveCalls.get());
    }

    private static final class RecordingCell implements StorageCell {
        private int persistCalls;

        @Override
        public long insert(appeng.api.stacks.AEKey what, long amount, Actionable mode,
                           appeng.api.networking.security.IActionSource source) {
            return amount;
        }

        @Override
        public long extract(appeng.api.stacks.AEKey what, long amount, Actionable mode,
                            appeng.api.networking.security.IActionSource source) {
            return amount;
        }

        @Override
        public CellState getStatus() {
            return CellState.EMPTY;
        }

        @Override
        public double getIdleDrain() {
            return 0;
        }

        @Override
        public void persist() {
            persistCalls++;
        }

        @Override
        public Component getDescription() {
            return Component.literal("recording cell");
        }
    }
}
