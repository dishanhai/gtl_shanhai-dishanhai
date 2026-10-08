package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShanhaiVoltageTiersTest {

    @Test
    void namedTiersMatchTheEditorTable() {
        assertEquals(8L, ShanhaiVoltageTiers.eutOfLabel("ULV  8"));
        assertEquals(536870912L, ShanhaiVoltageTiers.eutOfLabel("OpV  536870912"));
        assertEquals(2147483647L, ShanhaiVoltageTiers.eutOfLabel("MAX  2147483647"));
        assertEquals("MAX", ShanhaiVoltageTiers.nameFor("2147483647"));
        assertEquals("UXV", ShanhaiVoltageTiers.nameFor("134217728"));
    }

    @Test
    void extendedTiersRunFromMaxMinusTwoToLongMax() {
        assertEquals(134217728L, ShanhaiVoltageTiers.extended(-2));
        assertEquals(536870912L, ShanhaiVoltageTiers.extended(-1));
        assertEquals(8589934592L, ShanhaiVoltageTiers.extended(1));
        assertEquals(Long.MAX_VALUE, ShanhaiVoltageTiers.extended(16));
        assertEquals(Long.MAX_VALUE, ShanhaiVoltageTiers.eutOfLabel("MAX+16  " + Long.MAX_VALUE));
        assertEquals("MAX+16", ShanhaiVoltageTiers.nameFor(Long.toString(Long.MAX_VALUE)));
        assertNull(ShanhaiVoltageTiers.nameFor("1"));
        assertEquals(33, ShanhaiVoltageTiers.labels().size());
        assertEquals(33, ShanhaiVoltageTiers.names().size());
        assertEquals(2147483647L, ShanhaiVoltageTiers.eutOfName("MAX"));
        assertEquals(Long.MAX_VALUE, ShanhaiVoltageTiers.eutOfName("MAX+16"));
    }
}
