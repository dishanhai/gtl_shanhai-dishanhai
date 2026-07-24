package com.dishanhai.gt_shanhai.common.machine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ZeroPhotonCondenserSourceTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "misc", "ZeroPhotonCondenserMachine.java");
    private static final Path MACHINES = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "DShanhaiMachines.java");
    private static final Path BLOCKS = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "block", "DShanhaiBlocks.java");
    private static final Path MOD = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "GTDishanhaiMod.java");
    private static final Path BLOCKSTATE = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "blockstates", "casing_zero_photon.json");
    private static final Path MODEL = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "models", "block", "casing_zero_photon.json");
    private static final Path ITEM_MODEL = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "models", "item", "casing_zero_photon.json");
    private static final Path TEXTURE = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "textures", "block", "casing_zero_photon.png");

    @Test
    void zeroPhotonStructureExposesParallelHatchAndMachineReadsItsCapacity() throws Exception {
        String machine = Files.readString(MACHINE);
        String machines = Files.readString(MACHINES);

        assertTrue(machine.contains("GTLRecipeModifiers.getHatchParallel(this)"));
        assertTrue(machine.contains("PartAbility.PARALLEL_HATCH"));
        assertTrue(machine.contains("GTDishanhaiMod.ZERO_PHOTON_CASING.get()"));
        assertTrue(machine.contains("Math.max(4, hatchParallel)"));
        assertTrue(machines.contains("GTDishanhaiMod.ZERO_PHOTON_CASING"));
        assertTrue(machines.contains("appearanceBlock(GTDishanhaiMod.ZERO_PHOTON_CASING::get)"));
        assertFalse(machines.contains("Block zpCasing = GTDishanhaiMod.ZERO_PHOTON_CASING.get()"));
        assertTrue(machines.contains("block/casing_zero_photon"));
        assertTrue(machines.contains("零点光子外壳搭建"));
    }

    @Test
    void zeroPhotonCasingHasIndependentRegisteredBlockAndRenderAssets() throws Exception {
        String mod = Files.readString(MOD);
        String blocks = Files.readString(BLOCKS);
        String blockstate = Files.readString(BLOCKSTATE);
        String model = Files.readString(MODEL);
        String itemModel = Files.readString(ITEM_MODEL);

        assertTrue(mod.contains("ZERO_PHOTON_CASING = BLOCKS.register("));
        assertTrue(mod.contains("ZERO_PHOTON_CASING_ITEM = ITEMS.register("));
        assertFalse(blocks.contains("CASING_ZERO_PHOTON"));
        assertTrue(blockstate.contains("gt_shanhai:block/casing_zero_photon"));
        assertTrue(model.contains("gt_shanhai:block/casing_zero_photon"));
        assertTrue(itemModel.contains("gt_shanhai:block/casing_zero_photon"));
        assertTrue(Files.exists(TEXTURE));
    }

}
