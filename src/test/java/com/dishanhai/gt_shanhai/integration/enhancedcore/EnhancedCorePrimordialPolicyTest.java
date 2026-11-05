package com.dishanhai.gt_shanhai.integration.enhancedcore;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadScheduler;

import static org.junit.jupiter.api.Assertions.*;

class EnhancedCorePrimordialPolicyTest {
    @Test
    void cloudMustDrainPaidLocalBatchesBeforeTakingExecution() {
        assertEquals(EnhancedCorePrimordialPolicy.Owner.LOCAL,
                EnhancedCorePrimordialPolicy.owner(false, 3));
        assertEquals(EnhancedCorePrimordialPolicy.Owner.DRAIN_LOCAL,
                EnhancedCorePrimordialPolicy.owner(true, 3));
        assertEquals(EnhancedCorePrimordialPolicy.Owner.CLOUD,
                EnhancedCorePrimordialPolicy.owner(true, 0));
    }

    @Test
    void admissionSharesTheSameFiniteParallelAndThreadCapacity() {
        var scheduler = new PrimordialThreadScheduler<String>();
        assertEquals(4, scheduler.admissionShare(10, 3));
        scheduler.restore(0, 4, "paid-first");
        assertEquals(3, scheduler.admissionShare(10, 3));
        scheduler.restore(1, 3, "paid-second");
        scheduler.restore(2, 3, "paid-third");
        assertEquals(0, scheduler.admissionShare(10, 3));
        var full = new PrimordialThreadScheduler<String>();
        full.restore(0, 10, "capacity-reserved");
        assertEquals(0, full.admissionShare(10, 3));
        assertEquals(Long.MAX_VALUE, new PrimordialThreadScheduler<>().admissionShare(Long.MAX_VALUE, 1));
    }
}
