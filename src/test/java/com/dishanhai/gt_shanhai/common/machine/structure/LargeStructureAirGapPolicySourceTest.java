package com.dishanhai.gt_shanhai.common.machine.structure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LargeStructureAirGapPolicySourceTest {

    private static final Path GRAVITATIONAL_WAVE_TRANSMITTER = Path.of("src", "main", "java", "com",
            "dishanhai", "gt_shanhai", "common", "machine", "structure", "STRStructure.java");
    private static final Path WORLDLINE_CRACKING_HUB = Path.of("src", "main", "java", "com",
            "dishanhai", "gt_shanhai", "common", "machine", "worldline_cracking",
            "WorldlineCrackingHubStructure.java");

    @Test
    void largeStructureBlankSlotsAcceptAnyBlockInsteadOfOnlyAir() throws IOException {
        assertBlankSlotAcceptsAnyBlock(GRAVITATIONAL_WAVE_TRANSMITTER);
        assertBlankSlotAcceptsAnyBlock(WORLDLINE_CRACKING_HUB);
    }

    private static void assertBlankSlotAcceptsAnyBlock(Path structure) throws IOException {
        String source = Files.readString(structure);
        String file = structure.toString();

        assertTrue(source.contains(".where(' ', Predicates.any())"),
                file + " must treat blank structure slots as wildcard fill");
        assertFalse(source.contains(".where(' ', Predicates.blocks(Blocks.AIR))"),
                file + " must not force blank structure slots to be air");
    }
}
