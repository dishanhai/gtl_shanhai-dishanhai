package com.dishanhai.gt_shanhai.common.item.generator;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternGeneratorCoreTest {
    @Test
    void blankFilterTagStaysExactMatch() {
        assertNull(PatternGeneratorSettings.filterTag(null));
        assertNull(PatternGeneratorSettings.filterTag(""));
        assertNull(PatternGeneratorSettings.filterTag("   "));
        assertEquals(new ResourceLocation("forge", "ingots"), PatternGeneratorSettings.filterTag("forge:ingots"));
    }

    @Test
    void keywordsIgnoreQuotesAndDuplicates() {
        assertEquals(List.of("iron", "ingot"), PatternGeneratorFilter.keywords("  \"Iron\"  ingot iron "));
    }

    @Test
    void circuitAndTierFilters() {
        assertTrue(PatternGeneratorFilter.matchesCircuit(PatternGeneratorFilter.ANY_CIRCUIT, 0));
        assertTrue(PatternGeneratorFilter.matchesCircuit(0, 0));
        assertFalse(PatternGeneratorFilter.matchesCircuit(1, 0));
        assertTrue(PatternGeneratorFilter.matchesTier(-1, -1, 4));
        assertTrue(PatternGeneratorFilter.matchesTier(2, 5, 4));
        assertFalse(PatternGeneratorFilter.matchesTier(5, 2, 4));
        assertFalse(PatternGeneratorFilter.matchesTier(3, -1, 2));
    }

    @Test
    void nameBitsMatchEitherIdOrTranslatedIndex() {
        var items = new BitSet();
        items.set(7);
        assertTrue(PatternGeneratorFilter.allows(
                List.of(new PatternGeneratorFilter.Material("gtceu:iron_ingot", false, 7)),
                List.of("铁"), items, new BitSet(), java.util.Set.of()));
        assertFalse(PatternGeneratorFilter.allows(
                List.of(new PatternGeneratorFilter.Material("gtceu:iron_ingot", false, 7)),
                List.of("iron"), items, new BitSet(), java.util.Set.of("item:gtceu:iron_ingot")));
    }

    @Test
    void layoutPagesStayInsideBounds() {
        assertEquals(15, PatternGeneratorLayout.capacity(1));
        assertEquals(2, PatternGeneratorLayout.pages(16, 1));
        assertEquals(1, PatternGeneratorLayout.page(9, 16, 1));
        assertEquals(1, PatternGeneratorLayout.column(15));
        assertEquals(0, PatternGeneratorLayout.row(15));
        assertEquals(10, PatternGeneratorLayout.columns(99));
    }

    @Test
    void scaleRejectsOverflow() {
        assertEquals(12L, PatternGeneratorScale.apply(3L, 4));
        assertEquals(4096, PatternGeneratorScale.outputFactor(64, 64));
        assertThrows(ArithmeticException.class, () -> PatternGeneratorScale.apply(Long.MAX_VALUE, 2));
        assertEquals(1, PatternGeneratorScale.clamp(0));
        assertEquals(64, PatternGeneratorScale.clamp(1000));
    }

    @Test
    void presetsRoundTripAndRejectForeignFiles(@TempDir Path directory) throws Exception {
        var preset = PatternGeneratorPresetFiles.create("铁锭", "山海", "id", "{scale:4}",
                List.of("gtceu:iron_ingot"));
        PatternGeneratorPresetFiles.save(directory, preset);
        var listing = PatternGeneratorPresetFiles.list(directory);
        assertEquals(1, listing.presets().size());
        assertEquals("铁锭", listing.presets().get(0).name());
        assertEquals(List.of("gtceu:iron_ingot"), listing.presets().get(0).recipes());
        assertEquals(0, listing.unreadable());

        java.nio.file.Files.writeString(directory.resolve("foreign.json"),
                "{\"format\":\"gtl_enhancedcore:pattern_generator\",\"version\":1}\n");
        assertEquals(1, PatternGeneratorPresetFiles.list(directory).unreadable());
        assertThrows(IllegalArgumentException.class,
                () -> PatternGeneratorPresetFiles.create(" ", "a", "b", "{}", List.of()));
    }
}
