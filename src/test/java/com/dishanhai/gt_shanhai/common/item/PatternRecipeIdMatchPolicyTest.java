package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternRecipeIdMatchPolicyTest {

    @Test
    void acceptsCanonicalRecipePathForMatterExoticizerAlias() {
        assertTrue(PatternRecipeIdMatchPolicy.hasTypePrefix(
                "gtceu:matter_exoticizer", "kubejs:matter_exotic/plasma"));
        assertTrue(PatternRecipeIdMatchPolicy.matches(
                "gtceu:matter_exoticizer", "kubejs:matter_exotic/plasma",
                "gtceu:matter_exotic", "kubejs:matter_exotic/plasma"));
        assertFalse(PatternRecipeIdMatchPolicy.hasTypePrefix(
                "gtceu:matter_exoticizer", "kubejs:matter_exoticizer/plasma"));
    }

    @Test
    void acceptsExactSmeltingProxyIdWithoutElectricFurnacePath() {
        assertTrue(PatternRecipeIdMatchPolicy.hasTypePrefix(
                "minecraft:smelting", "minecraft:iron_ingot_from_smelting_raw_iron"));
        assertTrue(PatternRecipeIdMatchPolicy.matches(
                "minecraft:smelting", "minecraft:iron_ingot_from_smelting_raw_iron",
                "gtceu:electric_furnace", "minecraft:iron_ingot_from_smelting_raw_iron"));
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "minecraft:smelting", "minecraft:iron_ingot_from_smelting_raw_iron",
                "gtceu:electric_furnace", "minecraft:gold_ingot_from_smelting_raw_gold"));
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "minecraft:smelting", "minecraft:iron_ingot_from_smelting_raw_iron",
                "gtceu:macerator", "minecraft:iron_ingot_from_smelting_raw_iron"));
    }

    @Test
    void matchesExactTypeNamespaceAndPathSuffix() {
        assertTrue(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:macerator/essence",
                "gtceu:macerator", "gt_shanhai:macerator/essence"));
    }

    @Test
    void rejectsAnotherNamespaceEvenWhenTypeAndSuffixMatch() {
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:macerator/essence",
                "gtceu:macerator", "kubejs:macerator/essence"));
    }

    @Test
    void rejectsAnotherRecipeTypeOrSuffix() {
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:macerator/essence",
                "gtceu:cutter", "gt_shanhai:macerator/essence"));
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:macerator/essence",
                "gtceu:macerator", "gt_shanhai:macerator/essence_alt"));
    }

    @Test
    void rejectsMissingIdsAndIdsWithoutTheRecipeTypePathPrefix() {
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "", "gtceu:macerator", "gt_shanhai:macerator/essence"));
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:essence",
                "gtceu:macerator", "gt_shanhai:essence"));
        assertTrue(PatternRecipeIdMatchPolicy.hasTypePrefix(
                "gtceu:macerator", "gt_shanhai:macerator/essence"));
        assertFalse(PatternRecipeIdMatchPolicy.hasTypePrefix(
                "gtceu:cutter", "gt_shanhai:macerator/essence"));
    }
}
