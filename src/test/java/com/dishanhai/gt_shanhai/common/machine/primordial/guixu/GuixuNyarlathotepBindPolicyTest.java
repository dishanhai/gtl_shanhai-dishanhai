package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuNyarlathotepBindPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：不啟動 Forge 的真實地址與綁定規則測試。 */
class GuixuNyarlathotepBindPolicyTest {
    private static final Address HOST = new Address("minecraft:overworld", 17L);
    private static final Address TARGET = new Address("minecraft:the_end", 23L);

    @Test
    void emptyEndpointsAllowBindingButAnotherOwnerCannotBeOverwritten() {
        assertEquals(Result.LINKED, decide(true, null, TARGET, null, HOST));
        assertEquals(Result.HOST_OCCUPIED, decide(true, new Address("minecraft:the_end", 24L), TARGET, null, HOST));
        assertEquals(Result.TARGET_OCCUPIED, decide(true, null, TARGET, new Address("minecraft:the_nether", 17L), HOST));
    }

    @Test
    void dimensionAndPositionBothIdentifyAnEndpoint() {
        assertNotEquals(HOST, new Address("minecraft:the_end", HOST.position()));
        assertNotEquals(HOST, new Address(HOST.dimension(), HOST.position() + 1));
        assertEquals(Result.ALREADY_LINKED, decide(true, TARGET, TARGET, HOST, HOST));
    }

    @Test
    void samePairCanRepairEitherSideAfterUnlinkingWhileOtherSideWasUnloaded() {
        assertEquals(Result.LINKED, decide(true, null, TARGET, HOST, HOST));
        assertEquals(Result.LINKED, decide(true, TARGET, TARGET, null, HOST));
    }

    @Test
    void unavailableEndpointRejectsBindingAndKeepsConfiguredAddresses() {
        Address hostBinding = TARGET;
        Address targetBinding = HOST;
        assertEquals(Result.UNAVAILABLE, decide(false, hostBinding, TARGET, targetBinding, HOST));
        assertEquals(TARGET, hostBinding);
        assertEquals(HOST, targetBinding);
        assertEquals(Result.ALREADY_LINKED, decide(true, hostBinding, TARGET, targetBinding, HOST));
    }
}
