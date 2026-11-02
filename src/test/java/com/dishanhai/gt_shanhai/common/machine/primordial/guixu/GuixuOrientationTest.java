package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGeometry;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import static org.junit.jupiter.api.Assertions.*;

class GuixuOrientationTest {
    private static final BlockPos CONTROLLER = new BlockPos(931, 83, -747);

    @Test void blackHoleIsBehindTheControllerInEveryHorizontalOrientation() {
        for (Direction front : Direction.Plane.HORIZONTAL) {
            BlockPos center = HostFxGeometry.centerWorld(CONTROLLER, front);
            BlockPos delta = center.subtract(CONTROLLER);
            assertEquals(-72, delta.getX()*front.getStepX()+delta.getZ()*front.getStepZ(),
                    "The back casing, not the overlay, must face the black hole: " + front);
            assertEquals(-11, delta.getY());
        }
    }

    @Test void canonicalControllerFacesAwayFromTheBlackHole() {
        assertEquals(Direction.EAST, GuixuHostGeometry.CONTROLLER_FACING);
        for (var slot : GuixuHostGeometry.slots())
            assertEquals(slot.offset(), GuixuHostGeometry.rotate(Direction.EAST, slot.offset()));
    }

    @Test void nativePatternAxesAndRenderedCellsAgreeWithoutMirroringTheStructure() throws Exception {
        // 只在隔離載入器把現用 GTCEu 的 Direction 呼叫由 SRG 名稱映射為開發名稱。
        // 實際執行原 jar 的軸計算，避免以測試自造的旋轉公式驗證另一份公式。
        ClassLoader loader = mappedPatternLoader();
        var structure = loader.loadClass(GuixuVisionCoreStructure.class.getName());
        var method = structure.getDeclaredMethod("patternDirections");
        method.setAccessible(true);
        Object[] axes = (Object[])method.invoke(null);
        Direction[][] worldAxes = new Direction[Direction.values().length][3];
        var actualFacing = axes[0].getClass().getMethod("getActualFacing", Direction.class);
        for (Direction front : Direction.Plane.HORIZONTAL)
            for (int i = 0; i < 3; i++) worldAxes[front.ordinal()][i] = (Direction)actualFacing.invoke(axes[i], front);
        int[] cells = {0};
        StructureResourceLoader.INSTANCE.loadShapeInfoSlices(GuixuVisionCoreStructure.RESOURCE_PATH,
                GuixuVisionCoreStructure.RESOURCE_ID, (col, aisle, row, symbol) -> {
                    if (symbol == ' ') return symbol;
                    int gx = aisle + GuixuHostGeometry.TEMPLATE_MIN_X;
                    int gy = GuixuHostGeometry.TEMPLATE_SIZE_Y - 1 - row + GuixuHostGeometry.TEMPLATE_MIN_Y;
                    int gz = col + GuixuHostGeometry.TEMPLATE_MIN_Z;
                    for (Direction front : Direction.Plane.HORIZONTAL) {
                        int[] relative = {gz - GuixuHostGeometry.CONTROLLER_Z,
                                GuixuHostGeometry.CONTROLLER_Y - gy, gx - GuixuHostGeometry.CONTROLLER_X};
                        BlockPos nativeOffset = BlockPos.ZERO;
                        for (int i = 0; i < 3; i++)
                            nativeOffset = nativeOffset.relative(worldAxes[front.ordinal()][i], relative[i]);
                        assertEquals(CONTROLLER.offset(nativeOffset),
                                HostFxGeometry.worldFromGenerator(CONTROLLER, front, gx, gy, gz));
                    }
                    cells[0]++;
                    return symbol;
                });
        assertTrue(cells[0] > 25_000, "Use the complete formal structure, including asymmetric station cells");
    }

    private static ClassLoader mappedPatternLoader() {
        String structure = GuixuVisionCoreStructure.class.getName();
        String relative = "com.gregtechceu.gtceu.api.pattern.util.RelativeDirection";
        var names = java.util.Map.of("m_122427_", "getClockWise", "m_122428_", "getCounterClockWise",
                "m_122424_", "getOpposite", "m_122434_", "getAxis", "m_122436_", "getNormal");
        return new ClassLoader(GuixuOrientationTest.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(structure) && !name.equals(relative) && !name.startsWith(relative + "$"))
                    return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (var stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (stream == null) throw new ClassNotFoundException(name);
                        var writer = new ClassWriter(0);
                        new ClassReader(stream).accept(new ClassRemapper(writer, new Remapper() {
                            @Override public String mapMethodName(String owner, String methodName, String descriptor) {
                                return owner.equals("net/minecraft/core/Direction")
                                        ? names.getOrDefault(methodName, methodName) : methodName;
                            }
                        }), 0);
                        byte[] bytes = writer.toByteArray();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        };
    }

    @Test void renderingYawInverseAndModulePositionsStayInTheSameFrame() {
        for (Direction front : Direction.Plane.HORIZONTAL)
            for (var slot : GuixuHostGeometry.slots()) {
                BlockPos offset = slot.offset();
                BlockPos rotated = GuixuHostGeometry.rotate(front, offset);
                float[] corner = HostFxGeometry.rotateCorner(front, offset.getX(), offset.getY(), offset.getZ());
                assertArrayEquals(new float[]{rotated.getX(), rotated.getY(), rotated.getZ()}, corner, .0001F);
                assertArrayEquals(new float[]{offset.getX(), offset.getY(), offset.getZ()},
                        HostFxGeometry.unrotateCorner(front, corner[0], corner[1], corner[2]), .0001F);
                Vector3f gpu = new Vector3f(offset.getX(), offset.getY(), offset.getZ())
                        .rotateY((float)Math.toRadians(HostFxGeometry.yawDegrees(front)));
                assertEquals(rotated.getX(), gpu.x, .0001F);
                assertEquals(rotated.getZ(), gpu.z, .0001F);
                BlockPos world = CONTROLLER.offset(rotated);
                float[] local = HostFxGeometry.worldToLocal(CONTROLLER, front, world);
                assertEquals(world, HostFxGeometry.worldFromLocal(CONTROLLER, front, local[0], local[1], local[2]));
                assertTrue(GuixuHostGeometry.candidateHostPositions(world, slot.side()).contains(CONTROLLER));
            }
    }

    @Test void cameraAtTheBlackHoleCenterHasZeroLocalOffsetInAllFourOrientations() {
        for (Direction front : Direction.Plane.HORIZONTAL) {
            BlockPos center = HostFxGeometry.centerWorld(CONTROLLER, front);
            assertArrayEquals(new float[]{0, 0, 0}, HostFxGeometry.cameraInRingLocal(front,
                    center.getX() + .5 - CONTROLLER.getX(), center.getY() + .5 - CONTROLLER.getY(),
                    center.getZ() + .5 - CONTROLLER.getZ()), .0001F);
        }
    }
}
