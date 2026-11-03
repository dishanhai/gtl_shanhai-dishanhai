package com.dishanhai.gt_shanhai.common.item.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：終端不能把符合空氣條件的位置列為缺件或手動安裝位。 */
class ShanhaiStructureAirMatchingTest {
    @Test
    void requiredAirIsSatisfiedWithoutAnyPlaceableCandidate() throws Exception {
        assertTrue(matchesCurrentBlock(true, true, false));
        assertFalse(matchesCurrentBlock(true, false, false), "Occupied air space must remain blocked");
    }

    @Test
    void emptySpaceDoesNotSatisfyRequiredCasings() throws Exception {
        assertFalse(matchesCurrentBlock(false, true, false));
        assertTrue(matchesCurrentBlock(false, false, true));
    }

    @Test
    void optionalAirDoesNotDisableAlternativeHatchCandidates() throws Exception {
        assertTrue(matchesCurrentBlock(true, true, false));
        assertTrue(matchesCurrentBlock(true, false, true));
        assertFalse(matchesCurrentBlock(true, false, false));
    }

    private static boolean matchesCurrentBlock(boolean allowsAir, boolean currentIsAir,
                                               boolean candidateMatches) throws Exception {
        return ShanhaiStructurePlanner.matchesCurrentBlock(allowsAir, currentIsAir, candidateMatches);
    }
}
