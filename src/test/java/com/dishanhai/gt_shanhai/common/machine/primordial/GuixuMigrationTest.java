package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuHostGeometry;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuRecipeMath;
import com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxHidePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

class GuixuMigrationTest {
    private static final Path JAVA = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test
    void machineIsRegisteredWithItsRendererAndBlockEntityRendering() throws Exception {
        String machines = Files.readString(JAVA.resolve("common/machine/DShanhaiMachines.java"));
        assertTrue(machines.contains("GuixuVisionCoreMachines.init()"));
        String registration = Files.readString(JAVA.resolve("common/machine/primordial/guixu/GuixuVisionCoreMachines.java"));
        assertTrue(registration.contains(".hasTESR(true)"));
        assertTrue(registration.contains("GuixuHostFxRenderer"));
        assertFalse(registration.contains("com.shanhai"));
    }

    @Test
    void structureAndShaderArePresentInTheirRuntimeNamespaces() {
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/gtladditions/structures/multiblock/guixu_vision_core.bin")));
        for (String ext : new String[]{"json", "vsh", "fsh"}) {
            assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/gt_shanhai/shaders/core/guixu_blackhole." + ext)));
        }
    }

    @Test
    void geometryValidatesTwentyFourSlots() throws Exception {
        Class<?> geometry = Class.forName("com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuHostGeometry");
        assertEquals(24, geometry.getField("SLOT_COUNT").getInt(null));
        geometry.getMethod("verify").invoke(null);
    }

    @Test
    void moduleConditionsUseTheEffectiveLevel() throws Exception {
        String condition = Files.readString(JAVA.resolve("api/ModuleLevelCondition.java"));
        assertTrue(condition.contains("mb.getModuleLevel()"));
        String module = Files.readString(JAVA.resolve("common/machine/primordial/PrimordialOmegaEngineModuleBase.java"));
        assertTrue(module.contains("getHostMatterLevelOverride()"));
        assertTrue(module.contains("canonicalModuleIdForLevel"));
    }

    @Test
    void blackHoleMixinIsRegisteredWithProductionMethodName() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/gt_shanhai.minecraft.mixin.json"));
        assertTrue(config.contains("ShanhaiBlackHoleGameRendererMixin"));
        String mixin = Files.readString(JAVA.resolve("mixin/ShanhaiBlackHoleGameRendererMixin.java"));
        assertTrue(mixin.contains("m_109089_"));
        assertFalse(mixin.contains("method = \"renderLevel\""));
    }

    @Test
    void everySlotCanFindItsHostInAllFourOrientations() {
        BlockPos host = new BlockPos(931, 83, -747);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (var slot : GuixuHostGeometry.slots()) {
                BlockPos module = GuixuHostGeometry.slotPos(host, facing, slot);
                assertTrue(GuixuHostGeometry.candidateHostPositions(module, slot.side()).contains(host),
                        facing + " / " + slot);
            }
        }
    }

    @Test
    void nativeLoaderReadsTheBinaryAndItsModuleCellsMatchTheGeometry() {
        var modules = new java.util.HashSet<BlockPos>();
        var controllers = new java.util.ArrayList<BlockPos>();
        var slices = com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader.INSTANCE
                .loadShapeInfoSlices("multiblock/guixu_vision_core.bin", "guixu_vision_core",
                        (col, aisle, row, symbol) -> {
                            if (symbol == 'M' || symbol == '~') {
                                var position = new BlockPos(
                                        aisle + GuixuHostGeometry.TEMPLATE_MIN_X,
                                        GuixuHostGeometry.TEMPLATE_SIZE_Y - 1 - row + GuixuHostGeometry.TEMPLATE_MIN_Y,
                                        col + GuixuHostGeometry.TEMPLATE_MIN_Z);
                                if (symbol == 'M') modules.add(position);
                                else controllers.add(position);
                            }
                            return symbol;
                        });
        assertEquals(GuixuHostGeometry.TEMPLATE_SIZE_Z, slices.length);
        assertEquals(GuixuHostGeometry.TEMPLATE_SIZE_X, slices[0].length);
        assertEquals(GuixuHostGeometry.TEMPLATE_SIZE_Y, slices[0][0].length());
        assertEquals(java.util.List.of(GuixuHostGeometry.controllerPos()), controllers);
        var expected = new java.util.HashSet<BlockPos>();
        for (var slot : GuixuHostGeometry.slots()) {
            expected.add(GuixuHostGeometry.controllerPos().offset(slot.offset()));
        }
        assertEquals(expected, modules);
    }

    @Test
    void gateIsRequiredForBonusAndEnergyReductionMatchesSource() {
        assertEquals(1, GuixuVisionCoreMachine.productionMultiplierOf(0, 5));
        assertEquals(7, GuixuVisionCoreMachine.productionMultiplierOf(1, 5));
        assertEquals(23, GuixuVisionCoreMachine.productionMultiplierOf(17, 5));
        assertEquals(1.0D, GuixuRecipeMath.reductionFactor(0));
        assertEquals(0.05D, GuixuRecipeMath.reductionFactor(17), 1e-12);
        assertEquals(1.0D - Math.pow(0.95D, 17.0D), GuixuRecipeMath.reductionFactor(1), 1e-12);
    }

    @Test
    void inheritedFieldsAndBothGuixuSlotsAreManaged() {
        GuixuVisionCoreMachine.assertFieldHolderComplete();
    }

    @Test
    void hostConfigurationIncludesNativeMinimumDurationControl() throws Exception {
        String source = Files.readString(JAVA.resolve("common/machine/primordial/guixu/GuixuVisionCoreMachine.java"));
        assertTrue(source.contains("new LimitedDurationConfigurator(this)"));
        assertTrue(source.contains("super.attachCleanConfigurators(panel)"));
    }

    @Test
    void hiddenBlocksAreRestoredWhenTheRendererOrControllerDisappears() {
        assertFalse(HostFxHidePolicy.needsRestore(false, null, null, 9000));
        assertTrue(HostFxHidePolicy.needsRestore(true, null, null, 9000));
        assertFalse(HostFxHidePolicy.needsRestore(true, 8000L, null, 9000));
        assertTrue(HostFxHidePolicy.needsRestore(true, 6000L, null, 9000));
        assertTrue(HostFxHidePolicy.needsRestore(true, 9000L, 7000L, 9000));
        assertFalse(HostFxHidePolicy.keepInLedger(false));
        assertTrue(HostFxHidePolicy.keepInLedger(true));
    }

    @Test
    void everyHostConnectionEntryUsesItsOwnSlotGeometry() throws Exception {
        String module = Files.readString(JAVA.resolve("common/machine/primordial/PrimordialOmegaEngineModuleBase.java"));
        assertTrue(module.contains("public boolean isValidHost(MetaMachine machine)"));
        assertTrue(module.contains("engine.getModuleScanPositions()).contains(getPos())"));
        assertTrue(module.contains("&& isValidHost(engine)"));
    }

    @Test
    void restoreRechecksServerChangesAndTurningOffHidingRestoresImmediately() throws Exception {
        String hide = Files.readString(JAVA.resolve("client/renderer/hostfx/HostFxHide.java"));
        int start = hide.indexOf("private static int restoreOne(");
        String restore = hide.substring(start, hide.indexOf("/**", start));
        assertTrue(restore.indexOf("HostFxHidePolicy.keepInLedger(level.getBlockState(pos).isAir())")
                < restore.indexOf("level.setBlock(pos, e.getValue()"));
        assertTrue(restore.contains("HostFxHidePolicy.keepInLedger(level.getBlockState(pos).isAir())"));
        String renderer = Files.readString(JAVA.resolve("client/renderer/hostfx/GuixuHostFxRenderer.java"));
        assertTrue(renderer.contains("HostFxHide.restoreImmediately(clientLevel, controllerPos)"));
    }

    @Test
    void wirelessEnergyOverrideIsInstalledOnTheNativeAggregationHook() throws Exception {
        var logic = com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreRecipeLogic.class;
        assertEquals(long.class, logic.getDeclaredMethod("getWirelessRecipeEut",
                com.gregtechceu.gtceu.api.recipe.GTRecipe.class).getReturnType());

        // 從實際依賴 jar 的位元碼核對呼叫點，避免再次測到父類未使用的同名方法。
        var calls = new java.util.HashSet<String>();
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "com/gtladd/gtladditions/api/machine/logic/GTLAddMultipleRecipesLogic.class")) {
            assertNotNull(stream);
            new org.objectweb.asm.ClassReader(stream).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                        String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("buildFinalWirelessRecipe")) return null;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                String descriptor, boolean isInterface) {
                            calls.add(name + descriptor);
                        }
                    };
                }
            }, 0);
        }
        assertTrue(calls.contains("getWirelessRecipeEut(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)J"));
        var reduce = GuixuRecipeMath.class.getMethod("reducedEnergyEut", long.class, int.class);
        assertEquals(1000L, reduce.invoke(null, 1000L, 0));
        assertEquals(50L, reduce.invoke(null, 1000L, 17));
        assertEquals(1L, reduce.invoke(null, 1L, 17));
        assertEquals(0L, reduce.invoke(null, 0L, 17));
        assertEquals(-1000L, reduce.invoke(null, -1000L, 17));
    }
}
