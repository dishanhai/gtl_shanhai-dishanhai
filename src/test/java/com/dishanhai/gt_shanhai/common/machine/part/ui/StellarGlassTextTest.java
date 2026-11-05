package com.dishanhai.gt_shanhai.common.machine.part.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StellarGlassTextTest {
    @Test
    void smokeGlassKeepsSelectionAndWarningsVisibleWithoutDarkInk() {
        assertEquals("§a[1] §7Idle", StellarGlassText.forSmokeGlass("§2[1] §8Idle"));
        assertEquals("§c§lWarn§r §eWait §bInfo §dMode",
                StellarGlassText.forSmokeGlass("§4§lWarn§r §6Wait §3Info §5Mode"));
        assertEquals("§rText §9Link", StellarGlassText.forSmokeGlass("§fText §1Link"));
        String normal = "§a[1] §7Idle";
        assertSame(normal, StellarGlassText.forSmokeGlass(normal));
    }

    @Test
    void selectedAndUnselectedMothersKeepDistinctReadableColors() {
        assertEquals("§2[1]", StellarGlassText.forClearGlass("§a[1]"));
        assertEquals("§8[1]", StellarGlassText.forClearGlass("§7[1]"));
        assertNotEquals(StellarGlassText.forClearGlass("§a[1]"), StellarGlassText.forClearGlass("§7[1]"));
    }

    @Test
    void warningColorsAndTextFormattingSurviveTheThemeChange() {
        assertEquals("§4§lWarn§r §6Wait §3Info §5Mode",
                StellarGlassText.forClearGlass("§c§lWarn§r §eWait §bInfo §dMode"));
        assertEquals("§rText §1Link", StellarGlassText.forClearGlass("§FText §9Link"));
    }

    @Test
    void textWithoutLightColorCodesIsReusedAndMappingIsIdempotent() {
        String text = "§l[1]§r / 32";
        assertSame(text, StellarGlassText.forClearGlass(text));
        String converted = StellarGlassText.forClearGlass("§a[1]§f");
        assertSame(converted, StellarGlassText.forClearGlass(converted));
    }
}
