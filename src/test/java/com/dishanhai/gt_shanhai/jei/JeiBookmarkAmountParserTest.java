package com.dishanhai.gt_shanhai.jei;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiBookmarkAmountParserTest {

    @Test
    void parsesSaturatedLongAmountsWithoutOverflow() {
        long half = Long.MAX_VALUE / 2L;

        assertEquals(Long.MAX_VALUE, JeiBookmarkAmountParser.parse(Long.MAX_VALUE));
        assertEquals(half, JeiBookmarkAmountParser.parse(half));
        assertEquals(Long.MAX_VALUE,
                JeiBookmarkAmountParser.parse("Long.MAX_VALUE"));
        assertEquals(half,
                JeiBookmarkAmountParser.parse("Long.MAX_VALUE / 2"));
        assertEquals(Long.MAX_VALUE,
                JeiBookmarkAmountParser.parse(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
    }

    @Test
    void extremelyHighStorageIsAlwaysTreatedAsEnough() {
        long half = Long.MAX_VALUE / 2L;

        assertTrue(JeiBookmarkAmountParser.isEffectivelyEnough(half, Long.MAX_VALUE));
        assertTrue(JeiBookmarkAmountParser.isEffectivelyEnough(Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(JeiBookmarkAmountParser.isEffectivelyEnough(half - 1L, Long.MAX_VALUE));
        assertFalse(JeiBookmarkAmountParser.isEffectivelyEnough(10L, 11L));
    }
}
