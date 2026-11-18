package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StellarRemovalPolicyTest {
    @Test void replayRemovalNeverCreatesAnExternalReceipt() {
        AtomicInteger receipts = new AtomicInteger();
        StellarRemovalPolicy.handoff(true, receipts::incrementAndGet);
        assertEquals(0, receipts.get());
        StellarRemovalPolicy.handoff(false, receipts::incrementAndGet);
        assertEquals(1, receipts.get());
    }

    @Test void receiptsWaitWhileTheWorldIsFrozenOrSaveGuarded() {
        assertEquals(false, StellarRemovalPolicy.canDeliver(false, false));
        assertEquals(false, StellarRemovalPolicy.canDeliver(true, true));
        assertEquals(true, StellarRemovalPolicy.canDeliver(true, false));
    }

    @Test void replayClearsCatalystsWithoutCreatingDropsWhileNormalRemovalDropsThem() {
        var slots = new ArrayList<>(List.of("catalyst", "", "second"));
        var drops = new ArrayList<String>();
        var changes = new AtomicInteger();
        StellarRemovalPolicy.clearInventory(true,
                () -> StellarRemovalPolicy.clearSlots(slots.size(), i -> !slots.get(i).isEmpty(),
                        i -> slots.set(i, ""), changes::incrementAndGet),
                () -> { drops.addAll(slots); slots.replaceAll(ignored -> ""); });
        assertEquals(List.of("", "", ""), slots);
        assertEquals(List.of(), drops);
        assertEquals(2, changes.get());
        slots.set(0, "new catalyst");
        StellarRemovalPolicy.clearInventory(false, () -> { throw new AssertionError(); },
                () -> { drops.add(slots.get(0)); slots.set(0, ""); });
        assertEquals(List.of("new catalyst"), drops);
    }
}
