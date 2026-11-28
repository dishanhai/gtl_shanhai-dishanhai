package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.google.gson.JsonParser;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
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
            var symbols = Set.of(' ', '.', '~', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
                    'b', 'c', 'd', 'e', 'f', 'g', 'h');
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
            var size = json.getAsJsonArray("size");
            assertEquals(new StarLifterStructure.Specifications(size.get(0).getAsInt(), size.get(1).getAsInt(),
                    size.get(2).getAsInt(), blocks, interfaces), StarLifterStructure.specifications());
        }
    }

    @Test
    void cabinStaysSolidAndOutsideTheContinuousRingSweep() {
        var aisles = StructureResourceLoader.INSTANCE.loadAisles$gtladditions(StarLifterStructure.RESOURCE,
                StarLifterStructure.ID);
        var fixed = new java.util.LinkedHashMap<BlockPos, Double>();
        var expectedPreview = new java.util.HashMap<BlockPos, Character>();
        int latchCount = 0;
        for (int z = 0; z < aisles.length; z++) {
            for (int y = 0; y < aisles[z].length; y++) {
                String row = aisles[z][y];
                for (int x = 0; x < row.length(); x++) {
                    char symbol = row.charAt(x);
                    if (!Character.isLowerCase(symbol) && "JKLM~".indexOf(symbol) < 0) continue;
                    int mirrorZ = 2 * 91 - z;
                    assertEquals(symbol, aisles[mirrorZ][y].charAt(x), "Fixed cabin mirror at " + new BlockPos(x, y, z));
                    double halfWidth = symbol == 'M' ? StarLifterInnerLatchBlock.HALF_WIDTH : .5;
                    fixed.put(new BlockPos(x - 88, y - 72, z - 89), halfWidth);
                    expectedPreview.put(new BlockPos(x - 88, y - 72, z - 89), symbol);
                    if (symbol == 'M') latchCount++;
                    if (symbol == 'K') {
                        assertEquals(' ', row.charAt(x + 1), "hatch connection space");
                        assertEquals(178, x, "all ports sit in the control face");
                        assertTrue(Math.abs(y - 72) <= 1 && Math.abs(z - 91) <= 2, "port outside central panel");
                    }
                }
            }
        }
        assertTrue(fixed.size() > 9);
        var preview = new java.util.HashMap<BlockPos, Character>();
        StarLifterStructure.forEachFixedPreviewBlock((x, y, z, symbol) -> {
            assertEquals(symbol, StarLifterStructure.previewSymbol(x, y, z));
            preview.put(new BlockPos(x, y, z), symbol);
        });
        assertEquals(expectedPreview, preview);
        assertEquals(52, latchCount, "Two unbroken 13-block inner latch columns on each side");
        assertEquals(java.util.List.of(new Vec3(72, 0, -7.5), new Vec3(72, 0, 11.5)),
                StarLifterStructure.claspInnerCenters());
        for (int z : new int[]{-8, -7, 11, 12}) {
            for (int y = -7; y <= 7; y++) {
                assertEquals(Math.abs(y) == 7 ? .5 : StarLifterInnerLatchBlock.HALF_WIDTH,
                        fixed.get(new BlockPos(72, y, z)), "Both columns meet the upper and lower jaws");
            }
        }
        double scale = StarLifterGeometry.DEFAULT_RADIUS;
        for (int i = 0; i < StarLifterGeometry.RINGS.size(); i++) {
            boolean outer = i == StarLifterGeometry.OUTER_RING;
            var ring = StarLifterGeometry.RINGS.get(i);
            var orientation = ring.orientation(0);
            double azimuth = Math.toRadians(ring.axisAzimuth());
            double axisX = Math.cos(azimuth);
            double axisZ = Math.sin(azimuth);
            StarLifterGeometry.ring(i, new StarLifterGeometry.Mesh() {
                @Override
                public void quad(StarLifterGeometry.Surface surface, StarLifterGeometry.Point a,
                                 StarLifterGeometry.Point b, StarLifterGeometry.Point c, StarLifterGeometry.Point d) {}

                @Override
                public void band(StarLifterGeometry.Surface surface, double start, double end,
                                 double inner, double radius, double bottom, double top) {
                    double minAxial = Double.POSITIVE_INFINITY;
                    double maxAxial = Double.NEGATIVE_INFINITY;
                    double maxRadial = 0;
                    if (!outer) {
                        // These bounds on each convex prism hold throughout its rotation.
                        for (double height : new double[]{bottom, top}) {
                            for (double angle : new double[]{start, end}) {
                                for (double edge : new double[]{inner, radius}) {
                                    var vertex = orientation.transform(new Vector3f((float) (edge * Math.cos(angle)),
                                            (float) height, (float) (edge * Math.sin(angle)))).mul((float) scale);
                                    double axial = vertex.x * axisX + vertex.z * axisZ;
                                    minAxial = Math.min(minAxial, axial);
                                    maxAxial = Math.max(maxAxial, axial);
                                    maxRadial = Math.max(maxRadial,
                                            Math.hypot(-vertex.x * axisZ + vertex.z * axisX, vertex.y));
                                }
                            }
                        }
                    }
                    for (var entry : fixed.entrySet()) {
                        BlockPos cell = entry.getKey();
                        double halfWidth = entry.getValue();
                        double distance;
                        if (outer) {
                            double x = Math.max(0, Math.abs(cell.getX()) - halfWidth);
                            double z = Math.max(0, Math.abs(cell.getZ()) - halfWidth);
                            double furthest = Math.hypot(Math.abs(cell.getX()) + halfWidth,
                                    Math.abs(cell.getZ()) + halfWidth);
                            double radial = Math.max(Math.hypot(x, z) - radius * scale,
                                    inner * Math.cos((end - start) / 2) * scale - furthest);
                            distance = Math.max(radial,
                                    Math.max(cell.getY() - .5 - top * scale, bottom * scale - cell.getY() - .5));
                        } else {
                            double axial = cell.getX() * axisX + cell.getZ() * axisZ;
                            double padding = halfWidth * (Math.abs(axisX) + Math.abs(axisZ));
                            double axialGap = Math.max(axial - padding - maxAxial, minAxial - axial - padding);
                            double tangent = -cell.getX() * axisZ + cell.getZ() * axisX;
                            double radialGap = Math.hypot(Math.max(0, Math.abs(tangent) - padding),
                                    Math.max(0, Math.abs(cell.getY()) - .5)) - maxRadial;
                            distance = Math.hypot(Math.max(0, axialGap), Math.max(0, radialGap));
                        }
                        assertTrue(distance >= .25, () -> "insufficient ring clearance at " + cell);
                    }
                }
            });
        }
        var controller = new BlockPos(100, 150, -100);
        for (var front : Direction.Plane.HORIZONTAL) {
            var center = StarLifterStructure.center(controller, front);
            var installation = new StarLifterInstallation("controller:test", center,
                    StarLifterGeometry.DEFAULT_RADIUS, StarLifterStructure.heading(front), 0);
            for (var cell : fixed.keySet()) {
                var pos = BlockPos.containing(center).relative(front, cell.getX()).above(cell.getY())
                        .relative(front.getClockWise(), cell.getZ());
                assertFalse(StarLifterStructure.replaces(installation, pos), () -> "fixed cabin hidden at " + pos);
            }
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
