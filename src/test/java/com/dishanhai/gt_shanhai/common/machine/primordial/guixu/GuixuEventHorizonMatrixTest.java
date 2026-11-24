package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuixuEventHorizonMatrixTest {
    @Test
    void sixthMissFromZeroFailsAndRestoredSupplyCannotUndoFailure() {
        int pressure = 0;
        for (int i = 1; i <= 5; i++) {
            pressure = GuixuEventHorizonMatrix.nextPressure(pressure, false);
            assertEquals(i * 5, pressure);
        }
        pressure = GuixuEventHorizonMatrix.nextPressure(pressure, false);
        assertEquals(GuixuEventHorizonMatrix.FAILURE, pressure);
        assertEquals(pressure, GuixuEventHorizonMatrix.nextPressure(pressure, true));
    }

    @Test
    void supplyRecoversPressureAndOfflineFieldDoesNotAccumulateIt() {
        assertEquals(3, GuixuEventHorizonMatrix.nextPressure(5, true));
        assertEquals(0, GuixuEventHorizonMatrix.nextPressure(1, true));
        assertEquals(GuixuEventHorizonMatrix.OFFLINE,
                GuixuEventHorizonMatrix.nextPressure(GuixuEventHorizonMatrix.OFFLINE, false));
    }

    @Test
    void fourfoldSupplyAndBoostApplyOnlyAtZeroPressure() {
        var field = new GuixuContainmentField();
        field.open(false);
        assertEquals(40_000L, GuixuEventHorizonMatrix.spacetimeCost(field.requiredSpacetime()));
        for (int i = 0; i < GuixuContainmentField.CATALYSIS_PER_DOUBLING; i++) field.tickSecond(true);
        assertEquals(80_000L, GuixuEventHorizonMatrix.spacetimeCost(field.requiredSpacetime()));
        var base = GuixuContainmentBonus.of(field.status(), field.stability());
        var bonus = GuixuEventHorizonMatrix.boost(base, 0);
        assertEquals(16L, bonus.parallelMultiplier());
        assertEquals(0.25D, bonus.durationMultiplier());
        assertEquals(25, bonus.extraOutput());
        assertEquals(base.energyMultiplier(), bonus.energyMultiplier());
        assertSame(base, GuixuEventHorizonMatrix.boost(base, 5));
        assertSame(base, GuixuEventHorizonMatrix.boost(base, GuixuEventHorizonMatrix.OFFLINE));
        assertTrue(GuixuEventHorizonMatrix.boost(base, GuixuEventHorizonMatrix.FAILURE).locked());
    }

    @Test
    void footprintReadsOptionalCellsAtTheSamePositionsAsGtceu() throws Exception {
        var pattern = new BlockPattern(new TraceabilityPredicate[1][1][1],
                GuixuVisionCoreStructure.patternDirections(), new int[][]{{1, 1}}, new int[5]);
        var actualOffset = BlockPattern.class.getDeclaredMethod("setActualRelativeOffset", int.class, int.class,
                int.class, Direction.class, Direction.class, boolean.class);
        actualOffset.setAccessible(true);
        var slices = StructureResourceLoader.INSTANCE.loadShapeInfoSlices(GuixuVisionCoreStructure.RESOURCE_PATH,
                GuixuVisionCoreStructure.RESOURCE_ID, (col, aisle, row, symbol) -> symbol);
        int tested = 0;
        for (int col = 0; col < slices.length; col++) {
            for (int aisle = 0; aisle < slices[col].length; aisle++) {
                for (int row = 0; row < slices[col][aisle].length(); row++) {
                    char symbol = slices[col][aisle].charAt(row);
                    if ("PQTS~".indexOf(symbol) < 0) continue;
                    int x = col + GuixuHostGeometry.TEMPLATE_MIN_Z - GuixuHostGeometry.CONTROLLER_Z;
                    int y = row + GuixuHostGeometry.CONTROLLER_Y - GuixuHostGeometry.TEMPLATE_MIN_Y
                            - GuixuHostGeometry.TEMPLATE_SIZE_Y + 1;
                    int z = aisle + GuixuHostGeometry.TEMPLATE_MIN_X - GuixuHostGeometry.CONTROLLER_X;
                    for (Direction facing : Direction.Plane.HORIZONTAL) {
                        for (Direction upwards : Direction.Plane.HORIZONTAL) {
                            for (boolean flipped : new boolean[]{false, true}) {
                                BlockPos offset = (BlockPos) actualOffset.invoke(pattern, x, y, z, facing, upwards, flipped);
                                var axes = GuixuAnnihilation.HostAxes.of(facing, upwards, flipped);
                                assertEquals(symbol, axes.symbol(offset), () -> facing + " " + upwards + " " + flipped);
                                tested++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(tested > 8);
    }
}
