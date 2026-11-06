package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：通配重建與雲端租約共用地址時，忙碌槽不得被重編號或回收。 */
class StellarCloudSlotIndexTest {
    @Test void wildcardRebuildDoesNotMoveCloudSlot() {
        var slots = new StellarCloudSlotIndex();
        int local = slots.allocate("wildcard/0"), cloud = slots.allocate("cloud/a");
        assertTrue(slots.release("wildcard/0", false));
        assertEquals(local, slots.allocate("wildcard/1"));
        assertEquals(cloud, slots.allocate("cloud/a"));
    }
    @Test void busySlotsCannotBeReused() {
        var slots = new StellarCloudSlotIndex(); int a = slots.allocate("cloud/a");
        assertFalse(slots.release("cloud/a", true));
        assertNotEquals(a, slots.allocate("cloud/b"));
        assertTrue(slots.release("cloud/a", false)); assertEquals(a, slots.allocate("cloud/c"));
    }
    @Test void restoreRejectsCollidingIdentityAndKeepsSparseAddress() {
        var slots = new StellarCloudSlotIndex();
        assertTrue(slots.restore("cloud/a", 12)); assertFalse(slots.restore("cloud/b", 12));
        assertFalse(slots.restore("cloud/a", 5)); assertEquals(13, slots.extent());
        assertEquals(0, slots.allocate("wildcard/0")); assertEquals(12, slots.allocate("cloud/a"));
    }
}
