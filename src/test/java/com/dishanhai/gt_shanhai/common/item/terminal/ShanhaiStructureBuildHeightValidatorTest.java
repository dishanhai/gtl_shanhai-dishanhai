package com.dishanhai.gt_shanhai.common.item.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ShanhaiStructureBuildHeightValidatorTest {

    @Test
    void acceptsBoundaryBlocksAndRejectsPositionsOutsideTheWorldHeight() {
        ShanhaiStructureBuildHeightValidator.Result result =
                ShanhaiStructureBuildHeightValidator.validatePositions(
                        List.of(pos(-64), pos(319), pos(-65), pos(320)), -64, 320);

        assertFalse(result.valid());
        assertEquals(2, result.violationCount());
        assertEquals(new BlockPos(0, -65, 0), result.firstViolation());
        assertEquals(-64, result.minBuildHeight());
        assertEquals(319, result.maxBuildY());
        assertTrue(result.upperLimitExceeded());
    }

    @Test
    void acceptsAPlanWhoseEntireFootprintIsInsideTheWorldHeight() {
        ShanhaiStructureBuildHeightValidator.Result result =
                ShanhaiStructureBuildHeightValidator.validatePositions(
                        List.of(pos(-64), pos(0), pos(319)), -64, 320);

        assertTrue(result.valid());
        assertEquals(0, result.violationCount());
        assertEquals(null, result.firstViolation());
        assertFalse(result.upperLimitExceeded());
    }

    @Test
    void gtceuUpperLimitStaysAt320InTallerDimensions() {
        assertEquals(320, ShanhaiStructureBuildHeightValidator.effectiveMaxBuildHeight(512));
        assertEquals(320, ShanhaiStructureBuildHeightValidator.effectiveMaxBuildHeight(320));
        assertEquals(256, ShanhaiStructureBuildHeightValidator.effectiveMaxBuildHeight(256));
    }

    private static BlockPos pos(int y) {
        return new BlockPos(0, y, 0);
    }
}
