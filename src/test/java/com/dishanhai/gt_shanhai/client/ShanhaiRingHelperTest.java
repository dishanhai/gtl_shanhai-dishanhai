package com.dishanhai.gt_shanhai.client;

// Author: dishanhai
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShanhaiRingHelperTest {
    @Test
    void currentDependencyProvidesAllThreeNonemptyRingPatterns() {
        String[][][] rings = ShanhaiRingHelper.ringPatterns();
        assertEquals(3, rings.length);
        for (String[][] ring : rings) {
            assertTrue(Arrays.stream(ring).flatMap(Arrays::stream)
                    .anyMatch(row -> row.chars().anyMatch(c -> c != ' ')));
        }
        assertSame(rings, ShanhaiRingHelper.ringPatterns());
    }

    @Test
    void nativeRingCentersKeepTheirSpecialOffsetForEveryHorizontalFacing() {
        BlockPos host = new BlockPos(100, 64, -200);
        for (int width : new int[]{94, 96}) {
            String[][] ring = new String[width][1];
            for (String[] plane : ring) plane[0] = " ";
            for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                assertEquals(host.relative(facing.getOpposite(), width == 94 ? 123 : 122),
                        ShanhaiRingHelper.ringPosition(host, facing, ring, width / 2, 0, 0));
            }
        }
    }
}
