package com.dishanhai.gt_shanhai.common.machine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemovedAssemblyMachinesRegistrationTest {
    @Test
    void removesMachinesAndExclusiveBlocksButPreservesDataHubMaterials() throws Exception {
        String machines = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java"));
        String blocks = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/block/DShanhaiBlocks.java"));
        String hub = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/misc/SingularityDataHubMachine.java"));
        String addon = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/GTDishanhaiGTAddon.java"));
        int blocksInit = addon.indexOf("DShanhaiBlocks.init()");
        int machinesInit = addon.indexOf("DShanhaiMachines.init()");
        assertTrue(blocksInit >= 0, "DShanhaiBlocks.init()");
        assertTrue(machinesInit > blocksInit, "機殼必須在機器註冊之前進入 Registrate");
        for (String name : new String[]{"SPACE_SCALER", "INTEGRATED_ASSEMBLY_MATRIX", "INTEGRATED_ASSEMBLY_FACILITY"}) {
            assertFalse(machines.contains(name), name);
        }
        for (String name : new String[]{"NEUTRONIUM_FRAME", "COSMIC_NEUTRONIUM_FRAME", "SHIELDED_ACCELERATOR_CASING", "NIOBIUM_CAVITY_CASING", "COOLANT_DELIVERY_CASING", "NEUTRONIUM_GEARBOX"}) {
            assertFalse(blocks.contains(" " + name + " ="), name);
        }
        for (String name : new String[]{"CASING_ASSEMBLY", "CASING_MOLECULAR", "CASING_TRANSCENDENT", "CASING_RHENIUM", "CASING_QUANTUM_GLASS"}) {
            assertTrue(blocks.contains(" " + name + " ="), name);
            assertTrue(hub.contains("DShanhaiBlocks." + name), name);
        }
        assertTrue(machines.contains("SINGULARITY_DATA_HUB ="));
    }
}
