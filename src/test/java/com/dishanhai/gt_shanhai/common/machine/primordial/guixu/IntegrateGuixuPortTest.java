package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IntegrateGuixuPortTest {
    private static final Path JAVA = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test
    void crossTypeCandidatesRotateFairlyAndDeduplicateWithoutDroppingTail() throws Exception {
        var rotation = Class.forName("com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuRecipeRotation")
                .getMethod("roundRobin", List.class);
        assertEquals(List.of("a1", "b1", "c1", "a2", "b2", "a3"),
                List.copyOf((java.util.Set<?>) rotation.invoke(null,
                        List.of(List.of("a1", "a2", "a3"), List.of("b1", "b2"), List.of("c1")))));
        assertEquals(List.of("same", "b", "a"), List.copyOf((java.util.Set<?>) rotation.invoke(null,
                List.of(List.of("same", "a"), List.of("same", "b"), List.of()))));
    }

    @Test
    void updatedStructureRetainsModuleSlotsPhysicalIoAndNewGlass() throws Exception {
        String source = Files.readString(JAVA.resolve("common/machine/primordial/guixu/GuixuVisionCoreStructure.java"));
        for (String symbol : List.of("MODULE_SLOT_FILLED", "MODULE_CAVITY_FILLED", "CAVITY_MOUTH_FILLED",
                "MODULE_SHELL_BRONZE", "MODULE_SHELL_STEAM", "gtceu:fusion_glass", "gtlcore:power_module_4")) {
            assertTrue(source.contains(symbol), symbol);
        }
        for (String ability : List.of("IMPORT_ITEMS", "EXPORT_ITEMS", "IMPORT_FLUIDS", "EXPORT_FLUIDS")) {
            assertTrue(source.contains("PartAbility." + ability));
        }
        var moduleCells = new java.util.HashSet<net.minecraft.core.BlockPos>();
        com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader.INSTANCE
                .loadShapeInfoSlices("multiblock/guixu_vision_core.bin", "guixu_vision_core", (col, aisle, row, symbol) -> {
                    if (symbol == 'M' || symbol == 'N') moduleCells.add(new net.minecraft.core.BlockPos(
                            aisle + GuixuHostGeometry.TEMPLATE_MIN_X,
                            GuixuHostGeometry.TEMPLATE_SIZE_Y - 1 - row + GuixuHostGeometry.TEMPLATE_MIN_Y,
                            col + GuixuHostGeometry.TEMPLATE_MIN_Z));
                    return symbol;
                });
        var expected = new java.util.HashSet<net.minecraft.core.BlockPos>();
        for (var slot : GuixuHostGeometry.slots()) expected.add(GuixuHostGeometry.controllerPos().offset(slot.offset()));
        assertEquals(expected, moduleCells);
    }

    @Test
    void automaticTypeSearchKeepsLocalEnergyDirectionsSeparateAndModeNamesReachJade() throws Exception {
        String logic = Files.readString(JAVA.resolve("common/machine/primordial/guixu/GuixuVisionCoreRecipeLogic.java"));
        assertTrue(logic.contains("plannedConsumer"));
        assertTrue(logic.contains("recipeConsumesEnergy(recipe) != consumer"));
        String config = Files.readString(Path.of("src/main/resources/gt_shanhai.gtceu.mixin.json"));
        assertTrue(config.contains("ShanhaiMachineModeNameMixin"));
        String module = Files.readString(JAVA.resolve("common/machine/primordial/PrimordialOmegaEngineModuleBase.java"));
        assertTrue(module.contains("isCrossTypeParallelActive()"));
        assertTrue(module.contains("getAllSelectableRecipeTypes()"));
    }
}
