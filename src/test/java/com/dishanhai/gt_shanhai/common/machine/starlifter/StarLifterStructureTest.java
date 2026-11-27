package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.google.gson.JsonParser;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarLifterStructureTest {
    @Test
    void jsonMatchesTheGtlBinaryAndUsesOnlyDefinedPredicates() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/gt_shanhai/multiblock/star_lifter.json")) {
            assertNotNull(stream);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var aisles = json.getAsJsonArray("aisles");
            var binary = StructureResourceLoader.INSTANCE.loadAisles$gtladditions(StarLifterStructure.RESOURCE,
                    StarLifterStructure.ID);
            var symbols = Set.of(' ', '.', '~', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K');
            int controllers = 0;
            int interfaces = 0;
            int air = 0;
            int blocks = 0;
            assertEquals(aisles.size(), binary.length);
            for (int z = 0; z < binary.length; z++) {
                var rows = aisles.get(z).getAsJsonArray();
                assertEquals(rows.size(), binary[z].length);
                for (int y = 0; y < rows.size(); y++) {
                    String row = binary[z][y];
                    assertEquals(rows.get(y).getAsString(), row, "aisle " + z + " row " + y);
                    for (int x = 0; x < row.length(); x++) {
                        char symbol = row.charAt(x);
                        if (!symbols.contains(symbol)) throw new AssertionError("Unknown predicate " + symbol);
                        if (symbol == '~') controllers++;
                        if (symbol == 'K') interfaces++;
                        if (symbol == '.') air++;
                        else if (symbol != ' ') blocks++;
                    }
                }
            }
            assertEquals(1, controllers);
            assertEquals(json.getAsJsonArray("interfaces").size(), interfaces);
            assertEquals(json.getAsJsonArray("air").size(), air);
            assertEquals(json.getAsJsonObject("counts").entrySet().stream()
                    .mapToInt(entry -> entry.getValue().getAsInt()).sum(), blocks);
        }
    }

    @Test
    void shellAndControllerShareTheHorizontalTransform() {
        var controller = new BlockPos(100, 150, -100);
        for (var front : Direction.Plane.HORIZONTAL) {
            var center = StarLifterStructure.center(controller, front);
            var installation = new StarLifterInstallation("controller:test", center,
                    StarLifterGeometry.DEFAULT_RADIUS, StarLifterStructure.heading(front), 0);
            var centerCell = BlockPos.containing(center);
            assertEquals(controller.relative(front.getOpposite(), 90).relative(front.getCounterClockWise(), 2),
                    centerCell);
            assertTrue(StarLifterStructure.replaces(installation, centerCell.relative(front, 30)));
            assertFalse(StarLifterStructure.replaces(installation, controller));
            assertFalse(StarLifterStructure.replaces(installation, centerCell));
        }
    }

    @Test
    void switchesRebuildExactlyTheSectionsThatContainMovableBlocks() {
        var controller = new BlockPos(602, 204, 514);
        var aisles = StructureResourceLoader.INSTANCE.loadAisles$gtladditions(StarLifterStructure.RESOURCE,
                StarLifterStructure.ID);
        for (var front : Direction.Plane.HORIZONTAL) {
            var center = StarLifterStructure.center(controller, front);
            var installation = new StarLifterInstallation("controller:test", center,
                    StarLifterGeometry.DEFAULT_RADIUS, StarLifterStructure.heading(front), 0);
            var actual = new LongOpenHashSet();
            installation.forEachShellSection(section -> assertTrue(actual.add(section), "duplicate section"));

            var expected = new LongOpenHashSet();
            var right = front.getClockWise();
            for (int z = 0; z < aisles.length; z++) {
                for (int y = 0; y < aisles[z].length; y++) {
                    String row = aisles[z][y];
                    for (int x = 0; x < row.length(); x++) {
                        if (row.charAt(x) < 'B' || row.charAt(x) > 'I') continue;
                        int wx = controller.getX() + (x - 178) * front.getStepX() + (z - 91) * right.getStepX();
                        int wz = controller.getZ() + (x - 178) * front.getStepZ() + (z - 91) * right.getStepZ();
                        expected.add(SectionPos.asLong(SectionPos.blockToSectionCoord(wx),
                                SectionPos.blockToSectionCoord(controller.getY() + y - 72),
                                SectionPos.blockToSectionCoord(wz)));
                    }
                }
            }
            assertEquals(expected, actual, front.toString());
            var bounds = installation.bounds();
            int oldSectionCount = (SectionPos.posToSectionCoord(bounds.maxX) - SectionPos.posToSectionCoord(bounds.minX) + 1)
                    * (SectionPos.posToSectionCoord(bounds.maxY) - SectionPos.posToSectionCoord(bounds.minY) + 1)
                    * (SectionPos.posToSectionCoord(bounds.maxZ) - SectionPos.posToSectionCoord(bounds.minZ) + 1);
            assertTrue(actual.size() < oldSectionCount / 2);
        }
    }
}
