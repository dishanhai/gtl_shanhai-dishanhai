package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class ShanhaiSdaPatternTransferSourceTest {

    private static final Path SDA_ITEM = source("SuperDiskArrayItem.java");
    private static final Path SDA_INVENTORY = source("SuperDiskArrayInventory.java");
    private static final Path AE_KEY_CODEC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "ae", "DShanhaiAEKeyCodec.java");
    private static final Path TRANSFER = source("ShanhaiSdaPatternTransfer.java");
    private static final Path TARGET = source("PatternInventoryTargetHelper.java");

    @Test
    void sdaRightClickTransfersIntoRecognizedPatternInventories() throws Exception {
        String item = Files.readString(SDA_ITEM);
        String target = Files.readString(TARGET);

        assertTrue(item.contains("PatternInventoryTargetHelper.find(context)"));
        assertTrue(item.contains("SuperDiskArrayInventory.claimOwnership(stack)"));
        assertTrue(item.contains("ShanhaiSdaPatternTransfer.transfer(source, target)"));
        assertTrue(target.contains("PatternProviderLogicHost"));
        assertTrue(target.contains("MEPatternBufferPartMachine patternBuffer"));
        assertTrue(target.contains("patternBuffer.getTerminalPatternInventory()"));
    }

    @Test
    void transferKeepsPatternNbtAndRemovesOnlyCommittedItems() throws Exception {
        String source = Files.readString(TRANSFER);

        assertTrue(source.contains("itemKey.toStack()"));
        assertTrue(source.contains("PatternDetailsHelper.isEncodedPattern(pattern)"));
        assertTrue(source.indexOf("target.simulateAdd(pattern)")
                < source.indexOf("source.extract("));
        assertTrue(source.contains("source.restoreExtractedKey(key)"));
    }

    @Test
    void sdaAdvertisesMaxTypeCountInsteadOfLegacy9999Limit() throws Exception {
        String item = Files.readString(SDA_ITEM);

        assertTrue(item.contains("TOTAL_TYPES = Integer.MAX_VALUE"));
        assertFalse(item.contains("TOTAL_TYPES = 9999"));
    }

    @Test
    void sdaMainInventoryAcceptsGenericAeKeysAndCodecSupportsFluids() throws Exception {
        String inventory = Files.readString(SDA_INVENTORY);
        String codec = Files.readString(AE_KEY_CODEC);

        assertTrue(inventory.contains("if (what == null || amount <= 0) return 0L;"));
        assertFalse(inventory.contains("!(what instanceof AEItemKey)"));
        assertTrue(inventory.contains("if (what instanceof AEItemKey itemKey)"));
        assertTrue(codec.contains("import appeng.api.stacks.AEFluidKey;"));
        assertTrue(codec.contains("AEFluidKey.of(fluid)"));
    }

    private static Path source(String fileName) {
        return Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", fileName);
    }
}
