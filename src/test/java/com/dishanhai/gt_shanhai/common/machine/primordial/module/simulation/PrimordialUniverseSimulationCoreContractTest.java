package com.dishanhai.gt_shanhai.common.machine.primordial.module.simulation;

import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialUniverseSimulationCoreContractTest {

    @Test
    void moduleAndLogicUseTheStandardPrimordialParallelProcessingHierarchy() throws Exception {
        assertEquals(PrimordialParallelProcessingModuleBase.class,
                PrimordialUniverseSimulationCore.class.getSuperclass());
        assertEquals(PrimordialModuleRecipeLogic.class,
                PrimordialUniverseSimulationCoreLogic.class.getSuperclass());

        Method createLogic = PrimordialUniverseSimulationCore.class.getMethod("createRecipeLogic", Object[].class);
        assertEquals(PrimordialUniverseSimulationCoreLogic.class, createLogic.getReturnType());
        Method getLogic = PrimordialUniverseSimulationCore.class.getMethod("getRecipeLogic");
        assertEquals(PrimordialUniverseSimulationCoreLogic.class, getLogic.getReturnType());
    }

    @Test
    void machineDefinitionFieldIsPublicStatic() throws Exception {
        Field field = DShanhaiMachines.class.getField("PRIMORDIAL_UNIVERSE_SIMULATION_CORE");
        assertTrue(Modifier.isPublic(field.getModifiers()));
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertEquals(MultiblockMachineDefinition.class, field.getType());
    }

    @Test
    void registrationUsesCosmosSimulationAndTheEngineModuleSlot() throws Exception {
        String machines = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "machine", "DShanhaiMachines.java"));
        int start = machines.indexOf("PRIMORDIAL_UNIVERSE_SIMULATION_CORE = GTDishanhaiRegistration.REGISTRATE");
        assertTrue(start >= 0);
        String initializer = machines.substring(start, machines.indexOf(".register();", start));
        assertTrue(initializer.contains("\"primordial_universe_simulation_core\""));
        assertTrue(initializer.contains("PrimordialUniverseSimulationCore::new"));
        assertTrue(initializer.contains("GTLRecipeTypes.COSMOS_SIMULATION_RECIPES"));
        assertTrue(initializer.contains("PrimordialAssemblyLineModuleStructure::createPattern"));

        String structure = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "machine", "primordial", "PrimordialOmegaEngineStructure.java"));
        assertTrue(structure.contains("\"primordial_universe_simulation_core\""));
        assertTrue(structure.contains("Predicates.blocks(universeSimulationCore)"));
    }

    @Test
    void languageFilesProvideChineseAndEnglishNames() throws Exception {
        assertLanguage("zh_cn.json", "原初寰宇模拟核心", "§b寰宇模拟中", List.of(
                "§b推演寰宇，把宇宙模拟收进原初引擎",
                "§7配方类型：宇宙模拟",
                "§7只提供原初模块通用并行加工",
                "§7需安装在引擎模块位",
                "§d按模块等级提供并行处理能力，直接从电网取电"));
        assertLanguage("en_us.json", "Primordial Universe Simulation Core", "§bSimulating the Cosmos", List.of(
                "§bProjects a cosmos and runs universe simulation inside the primordial engine",
                "§7Recipe type: Cosmos Simulation",
                "§7Provides only standard primordial parallel processing",
                "§7Must be installed in an engine module slot",
                "§dParallel capacity scales with the installed module tier; draws power directly from the grid"));
    }

    private static void assertLanguage(String fileName, String expectedName, String expectedMode,
                                       List<String> expectedTooltips) throws Exception {
        Path path = Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang", fileName);
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals(expectedName, json.get("block.gt_shanhai.primordial_universe_simulation_core").getAsString());
        assertEquals(expectedName,
                json.get("gt_shanhai.machine.primordial_universe_simulation_core.name").getAsString());
        assertEquals(expectedMode,
                json.get("gt_shanhai.machine.primordial_universe_simulation_core.mode").getAsString());
        assertEquals(expectedName, json.get("module.primordial_universe_simulation_core.name").getAsString());
        for (int i = 0; i < expectedTooltips.size(); i++) {
            assertEquals(expectedTooltips.get(i), json.get(
                    "gt_shanhai.multiblock.primordial_universe_simulation_core.tooltip." + i).getAsString());
        }
    }
}
