package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MEDiskHatchChangeNotifyingStorageTest {

    @Test
    void successfulModulatedInsertNotifiesOwner() {
        RecordingStorage delegate = new RecordingStorage(64, 0);
        AtomicInteger ownerNotifications = new AtomicInteger();
        MEDiskHatchPartMachine.ChangeNotifyingStorage storage =
                new MEDiskHatchPartMachine.ChangeNotifyingStorage(
                        delegate, ownerNotifications::incrementAndGet);

        long inserted = storage.insert(null, 64, Actionable.MODULATE, null);

        assertEquals(64, inserted);
        assertEquals(1, ownerNotifications.get());
    }

    @Test
    void simulatedInsertDoesNotNotifyOwner() {
        RecordingStorage delegate = new RecordingStorage(64, 0);
        AtomicInteger ownerNotifications = new AtomicInteger();
        MEDiskHatchPartMachine.ChangeNotifyingStorage storage =
                new MEDiskHatchPartMachine.ChangeNotifyingStorage(
                        delegate, ownerNotifications::incrementAndGet);

        long inserted = storage.insert(null, 64, Actionable.SIMULATE, null);

        assertEquals(64, inserted);
        assertEquals(0, ownerNotifications.get());
    }

    @Test
    void successfulModulatedExtractNotifiesOwner() {
        RecordingStorage delegate = new RecordingStorage(0, 32);
        AtomicInteger ownerNotifications = new AtomicInteger();
        MEDiskHatchPartMachine.ChangeNotifyingStorage storage =
                new MEDiskHatchPartMachine.ChangeNotifyingStorage(
                        delegate, ownerNotifications::incrementAndGet);

        long extracted = storage.extract(null, 32, Actionable.MODULATE, null);

        assertEquals(32, extracted);
        assertEquals(1, ownerNotifications.get());
    }

    @Test
    void simulatedExtractDoesNotNotifyOwner() {
        RecordingStorage delegate = new RecordingStorage(0, 32);
        AtomicInteger ownerNotifications = new AtomicInteger();
        MEDiskHatchPartMachine.ChangeNotifyingStorage storage =
                new MEDiskHatchPartMachine.ChangeNotifyingStorage(
                        delegate, ownerNotifications::incrementAndGet);

        long extracted = storage.extract(null, 32, Actionable.SIMULATE, null);

        assertEquals(32, extracted);
        assertEquals(0, ownerNotifications.get());
    }

    private record RecordingStorage(long insertResult, long extractResult) implements MEStorage {

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            return insertResult;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            return extractResult;
        }

        @Override
        public Component getDescription() {
            return Component.literal("recording storage");
        }
    }
}
