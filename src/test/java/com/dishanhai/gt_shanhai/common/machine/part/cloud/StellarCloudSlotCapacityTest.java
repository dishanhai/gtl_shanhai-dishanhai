package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 山海：新配置與存檔恢復必須使用相同的 65536 個延伸地址邊界。 */
class StellarCloudSlotCapacityTest {
    @Test
    void allPersistableAddressesCanBeAllocatedAndOverflowKeepsExistingMappings() {
        StellarCloudSlotIndex index = new StellarCloudSlotIndex();
        for (int address = 0; address < 65536; address++)
            assertEquals(address, index.allocate("entry/" + address));
        assertEquals(65536, index.extent());
        assertThrows(IllegalStateException.class, () -> index.allocate("overflow"));
        assertEquals(65536, index.extent());
        assertEquals(0, index.allocate("entry/0"));
        assertEquals(65535, index.allocate("entry/65535"));
    }

    @Test
    void failedOverflowDoesNotReserveTheIdentityAndReleasedAddressCanBeReused() {
        StellarCloudSlotIndex index = new StellarCloudSlotIndex();
        for (int address = 0; address < 65536; address++) index.allocate("entry/" + address);
        assertThrows(IllegalStateException.class, () -> index.allocate("overflow"));
        assertTrue(index.release("entry/12345", false));
        assertEquals(12345, index.allocate("overflow"));
        assertEquals(65535, index.allocate("entry/65535"));
        assertFalse(index.restore("collision", 12345));
    }

    @Test
    void restoredBoundaryAcceptsTheLastValidAddressAndRejectsTheNext() {
        StellarCloudSlotIndex index = new StellarCloudSlotIndex();
        assertTrue(index.restore("last", 65535));
        assertEquals(65535, index.allocate("last"));
        assertEquals(65536, index.extent());
        assertFalse(index.restore("outside", 65536));
        assertFalse(index.restore("negative", -1));
        assertEquals(0, index.allocate("next"));
        assertFalse(index.restore("collision", 65535));
    }

    @Test
    void busyLastAddressCannotBeReassigned() {
        StellarCloudSlotIndex index = new StellarCloudSlotIndex();
        assertTrue(index.restore("busy", 65535));
        assertFalse(index.release("busy", true));
        assertFalse(index.restore("other", 65535));
        assertTrue(index.release("busy", false));
        assertTrue(index.restore("other", 65535));
    }
}
