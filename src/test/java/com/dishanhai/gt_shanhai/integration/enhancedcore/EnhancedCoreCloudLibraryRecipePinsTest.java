package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EnhancedCoreCloudLibraryRecipePinsTest {
    private record Probe(String io) {}

    @Test
    void equalIoProbesRetainDifferentExactRecipeIds() {
        var pins = new EnhancedCoreCloudLibraryRecipePins<Probe>();
        var first = new Probe("same"); var second = new Probe("same");
        pins.put(first, "gt_shanhai:first"); pins.put(second, "gt_shanhai:second");
        assertEquals("gt_shanhai:first", pins.get(first));
        assertEquals("gt_shanhai:second", pins.get(second));
        assertNull(pins.get(new Probe("same")));
        pins.clear(); assertNull(pins.get(first)); assertNull(pins.get(second));
    }
}
