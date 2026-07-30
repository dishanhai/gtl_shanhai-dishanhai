package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StellarPatternWarningPolicyTest {

    @Test
    void knownStellarWithoutMatchingTypeIsWrongHost() {
        assertTrue(StellarPatternWarningPolicy.isWrongHost(
                "gtceu:assembler",
                true,
                true,
                List.of("gtceu:alloy_smelter"),
                (patternType, hostType) -> false));
    }

    @Test
    void exactAndSharedTypesAreAcceptedAndUnknownMetadataIsIgnored() {
        assertFalse(StellarPatternWarningPolicy.isWrongHost(
                "gtceu:assembler",
                true,
                true,
                List.of("gtceu:assembler"),
                (patternType, hostType) -> false));
        assertFalse(StellarPatternWarningPolicy.isWrongHost(
                "gtceu:chemical_reactor",
                true,
                true,
                List.of("gtceu:large_chemical_reactor"),
                (patternType, hostType) -> patternType.contains("chemical_reactor")
                        && hostType.contains("chemical_reactor")));
        assertFalse(StellarPatternWarningPolicy.isWrongHost(
                "gtceu:assembler",
                false,
                true,
                List.of(),
                (patternType, hostType) -> false));
        assertFalse(StellarPatternWarningPolicy.isWrongHost(
                "gtceu:assembler",
                true,
                false,
                List.of(),
                (patternType, hostType) -> false));
    }

    @Test
    void knownStellarWithNoHostTypesIsWrongHost() {
        assertTrue(StellarPatternWarningPolicy.isWrongHost(
                "gtceu:assembler",
                true,
                true,
                List.of(),
                (patternType, hostType) -> false));
    }

    @Test
    void warningSlotCodecRoundTripsLargeSlotIndices() {
        BitSet slots = new BitSet();
        slots.set(0);
        slots.set(161);

        assertEquals(slots, StellarPatternWarningPolicy.decodeWarningSlots(
                StellarPatternWarningPolicy.encodeWarningSlots(slots)));
    }
}
