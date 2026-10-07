package com.dishanhai.gt_shanhai.common.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DShanhaiDuplicateRecipeWarningsTest {

    @Test
    void packShadowNamesTheWinnerAndTheCoveredPacks() {
        DShanhaiDuplicateRecipeWarnings.Warning warning = DShanhaiDuplicateRecipeWarnings.packShadow(
                "gt_shanhai:assembler/a", List.of("kubejs", "gt_shanhai"));
        assertEquals("gt_shanhai:assembler/a | 保留 kubejs | 被蓋掉 gt_shanhai", warning.line());
        assertNull(DShanhaiDuplicateRecipeWarnings.packShadow("gt_shanhai:assembler/a", List.of("gt_shanhai")));
    }

    @Test
    void kjsOverrideIgnoresItsOwnCachePack() {
        assertNull(DShanhaiDuplicateRecipeWarnings.kjsShadowsPacks(
                "dishanhai:line", List.of(DShanhaiDuplicateRecipeWarnings.CACHE_PACK)));
        DShanhaiDuplicateRecipeWarnings.Warning warning = DShanhaiDuplicateRecipeWarnings.kjsShadowsPacks(
                "dishanhai:line", List.of(DShanhaiDuplicateRecipeWarnings.CACHE_PACK, "gt_shanhai"));
        assertEquals("dishanhai:line | 保留 KJS | 被蓋掉 gt_shanhai", warning.line());
    }

    @Test
    void cacheHitDoesNotCompareScriptAgainstPacks() {
        String fingerprint = DShanhaiDuplicateRecipeWarnings.fingerprint(
                "gtceu:assembler", 20, List.of("in 1x minecraft:stone"));
        List<DShanhaiDuplicateRecipeWarnings.Warning> warnings = DShanhaiDuplicateRecipeWarnings.assemble(
                List.of(new DShanhaiDuplicateRecipeWarnings.PackGroup(
                        "dishanhai:line", List.of(DShanhaiDuplicateRecipeWarnings.CACHE_PACK, "gt_shanhai"))),
                Set.of("dishanhai:line"),
                true,
                List.of(new DShanhaiDuplicateRecipeWarnings.PrintedRecipe("dishanhai:line", fingerprint)));
        assertEquals(1, warnings.size());
        assertEquals("保留 " + DShanhaiDuplicateRecipeWarnings.CACHE_PACK + " | 被蓋掉 gt_shanhai", warnings.get(0).hint());
    }

    @Test
    void sameContentDifferentIdIsOneWarning() {
        String fingerprint = DShanhaiDuplicateRecipeWarnings.fingerprint(
                "gtceu:lathe", 40, List.of("in 1x gtceu:steel_ingot", "out 1x gtceu:steel_rod"));
        List<DShanhaiDuplicateRecipeWarnings.Warning> warnings = DShanhaiDuplicateRecipeWarnings.contentDuplicates(List.of(
                new DShanhaiDuplicateRecipeWarnings.PrintedRecipe("gt_shanhai:b", fingerprint),
                new DShanhaiDuplicateRecipeWarnings.PrintedRecipe("gt_shanhai:a", fingerprint)));
        assertEquals("gt_shanhai:a | 與 gt_shanhai:b 內容相同", warnings.get(0).line());
    }

    @Test
    void circuitOrTypeDifferenceIsNotADuplicate() {
        String plain = DShanhaiDuplicateRecipeWarnings.fingerprint(
                "gtceu:lathe", 40, List.of("in 1x gtceu:steel_ingot"));
        String circuit = DShanhaiDuplicateRecipeWarnings.fingerprint(
                "gtceu:lathe", 40, List.of("in 1x gtceu:steel_ingot", "in circuit:1"));
        String otherType = DShanhaiDuplicateRecipeWarnings.fingerprint(
                "gtceu:cutter", 40, List.of("in 1x gtceu:steel_ingot"));
        List<DShanhaiDuplicateRecipeWarnings.Warning> warnings = DShanhaiDuplicateRecipeWarnings.contentDuplicates(List.of(
                new DShanhaiDuplicateRecipeWarnings.PrintedRecipe("gt_shanhai:plain", plain),
                new DShanhaiDuplicateRecipeWarnings.PrintedRecipe("gt_shanhai:circuit", circuit),
                new DShanhaiDuplicateRecipeWarnings.PrintedRecipe("gt_shanhai:cutter", otherType)));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void repeatedRegistrationClears() {
        DShanhaiDuplicateRecipeWarnings.clear();
        DShanhaiDuplicateRecipeWarnings.noteRepeatedRegistration("gt_shanhai:once");
        assertEquals("gt_shanhai:once | KJS 再次註冊，後寫的覆蓋先寫的",
                DShanhaiDuplicateRecipeWarnings.repeatedRegistrations().get(0).line());
        DShanhaiDuplicateRecipeWarnings.clear();
        assertTrue(DShanhaiDuplicateRecipeWarnings.repeatedRegistrations().isEmpty());
    }
}
