package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShanhaiPatternDebugToolSourceTest {

    private static final Path BEHAVIOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiPatternTestBehavior.java");
    private static final Path ITEMS = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "DShanhaiItems.java");
    private static final Path ENCODER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiPatternEncoder.java");
    private static final Path ADDON = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "GTDishanhaiGTAddon.java");

    @Test
    void toolInheritsGtlcoreCapabilitiesButOwnsCompatibleStateAndResolution() throws Exception {
        String source = Files.readString(BEHAVIOR);

        assertTrue(source.contains("extends PatternTestBehavior"));
        assertTrue(source.contains("getConfig(heldItemHolder.getHeld())"));
        assertTrue(source.contains("stack.getOrCreateTag()"));
        assertTrue(source.contains("normalizeRecipeTypeId"));
        assertTrue(source.contains("value.contains(\":\") ? value : \"gtceu:\" + value"));
        assertFalse(source.contains("GTRecipeTypes.get(\"gtceu:\" +"));
    }

    @Test
    void generatedPatternsReceiveAuthoritativeShanhaiRecipeType() throws Exception {
        String source = Files.readString(BEHAVIOR);

        assertTrue(source.contains("ShanhaiPatternEncoder.encode(recipe, serverPlayer, false)"));
        assertTrue(source.contains("PatternRecipeTypeHelper.writeAuthoritativeRecipeType(patternItemStack, recipe)"));
    }

    @Test
    void moreThanTwentyGeneratedPatternsArePackedIntoOneSda() throws Exception {
        String source = Files.readString(BEHAVIOR);

        assertTrue(source.contains("if (generatedPatterns.size() > 20)"));
        assertTrue(source.contains("packPatternsAsSda(serverPlayer, generatedPatterns)"));
        assertTrue(source.contains("DShanhaiVirtualCellSavedData.get(server)"));
        assertTrue(source.contains("updateCellBig(uuid, \"sda\", SuperDiskArrayItem.TOTAL_BYTES, amounts)"));
        assertFalse(source.contains("serverPlayer.drop(patternItemStack"));
    }

    @Test
    void debugEncodingNeverWrapsOrDeletesProgrammedCircuits() throws Exception {
        String encoder = Files.readString(ENCODER);
        String behavior = Files.readString(BEHAVIOR);

        assertTrue(encoder.contains("IntCircuitBehaviour.isIntegratedCircuit(stack)"));
        assertTrue(encoder.indexOf("IntCircuitBehaviour.isIntegratedCircuit(stack)")
                < encoder.indexOf("VirtualItemProviderHelper.createBoundProvider(stack)"));
        assertFalse(behavior.contains("pattern.setDefaultFilter()"));
    }

    @Test
    void debugEncodingForceWrapsOtherNonConsumableItems() throws Exception {
        String source = Files.readString(ENCODER);

        assertTrue(source.contains("isNonConsumable(content)"));
        assertTrue(source.contains("VirtualItemProviderHelper.createBoundProvider(stack)"));
        assertTrue(source.contains("respectAutoWrapExclusions"));
        assertTrue(source.contains("!respectAutoWrapExclusions"));
    }

    @Test
    void componentItemRegistersTheShanhaiBehavior() throws Exception {
        String source = Files.readString(ITEMS);
        String addon = Files.readString(ADDON);

        assertTrue(source.contains("REGISTRATE.item(\"debug_pattern_test\", ComponentItem::create)"));
        assertTrue(source.contains("GTItems.attach(ShanhaiPatternTestBehavior.INSTANCE)"));
        assertTrue(addon.contains("DShanhaiItems.init()"));
    }
}
