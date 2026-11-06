package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StellarCloudMemberSelectionTest {
    @Test void cyclesAvailablePlayersAndHandlesDepartures() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        assertEquals(alice, StellarCloudMemberSelection.next(List.of(alice, bob), null));
        assertEquals(bob, StellarCloudMemberSelection.next(List.of(alice, bob), alice));
        assertEquals(alice, StellarCloudMemberSelection.next(List.of(alice, bob), bob));
        assertEquals(bob, StellarCloudMemberSelection.next(List.of(bob), alice));
        assertNull(StellarCloudMemberSelection.next(List.of(), alice));
    }
}
