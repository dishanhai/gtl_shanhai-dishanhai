package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「禁用原初系列機器」的契約：只砍全外部配方類型的模組，主機／被動模組／自建配方類型一律保留。
 */
class PrimordialSeriesMachinesContractTest {

    private static final Path MACHINES_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "DShanhaiMachines.java");
    private static final Path ENGINE_STRUCTURE_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialOmegaEngineStructure.java");
    private static final Path CONFIG_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "config", "DShanhaiConfig.java");
    private static final Path CONFIG_SCREEN_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "config", "DShanhaiConfigScreen.java");

    @Test
    void switchDefaultsOffAndIsRegisteredOnTheConfigScreen() throws Exception {
        assertFalse(DShanhaiConfig.COMMON.disablePrimordialSeriesMachines.getDefault(),
                "禁用原初系列機器必須默認關閉");
        String config = Files.readString(CONFIG_SOURCE);
        assertTrue(config.contains("builder.push(\"primordial_series\")"));
        String screen = Files.readString(CONFIG_SCREEN_SOURCE);
        assertTrue(screen.contains("cfg.disablePrimordialSeriesMachines.get()"));
        assertTrue(screen.contains("cfg.disablePrimordialSeriesMachines::set"));
    }

    @Test
    void hostAndPassiveModulesAreNeverDisableable() {
        assertFalse(PrimordialSeriesMachines.disableableIds().contains("primordial_omega_engine"));
        assertFalse(PrimordialSeriesMachines.disableableIds().contains("guixu_vision_core"));
        assertFalse(PrimordialSeriesMachines.disableableIds().contains("primordial_mass_energy_core"));
        assertFalse(PrimordialSeriesMachines.disableableIds().contains("guixu_penrose_containment_field"));
    }

    @Test
    void machinesWithModOwnedRecipeTypesStayOutOfTheDisableableList() {
        for (String path : new String[] {
                "primordial_divergence_generator",
                "primordial_matter_recombinator_core",
                "primordial_causal_weaving_matrix",
                "primordial_singularity_inversion_core",
                "primordial_myriad_proliferation_core",
                "primordial_coin_forge",
                "primordial_matter_caster",
                "primordial_assembly_line_module",
                "primordial_engraving_module",
                "primordial_cosmic_reactor",
                "primordial_eternal_smelting_furnace",
                "primordial_deep_space_extraction_core",
                "primordial_biological_core",
                "primordial_void_induction_armature"}) {
            assertFalse(PrimordialSeriesMachines.disableableIds().contains(path),
                    "帶 gt_shanhai 自建配方類型的機器不得列入可禁用名單: " + path);
        }
    }

    @Test
    void criticalProcessingModuleOnlyUsesExternalTypes() {
        assertTrue(PrimordialSeriesMachines.disableableIds().contains("primordial_critical_processing_module"));
        String machines = readSource(MACHINES_SOURCE);
        int start = machines.indexOf("PRIMORDIAL_CRITICAL_PROCESSING_MODULE = GTDishanhaiRegistration.REGISTRATE");
        assertTrue(start >= 0, "找不到臨界加工模塊註冊語句");
        int end = machines.indexOf(".register();", start);
        String block = machines.substring(start, end);
        assertFalse(block.contains("DShanhaiRecipeTypes."),
                "原初臨界加工模塊必須只使用外部配方類型，否則不能列入可禁用名單");
    }

    @Test
    void engineStructureSkipsDisabledModules() throws Exception {
        String source = Files.readString(ENGINE_STRUCTURE_SOURCE);
        assertTrue(source.contains("PrimordialSeriesMachines.isDisabled(new ResourceLocation(\"gt_shanhai\", id))"));
        assertTrue(source.contains("optionalPrimordialModule("));
    }

    @Test
    void everyDisableableMachineIsRegisteredWithoutModOwnedRecipeTypes() {
        String machines = readSource(MACHINES_SOURCE);
        for (String path : PrimordialSeriesMachines.disableableIds()) {
            String id = "gt_shanhai:" + path;
            int start = machines.indexOf(".multiblock(\"" + path + "\"");
            assertTrue(start >= 0, "可禁用名單中的機器找不到註冊語句: " + id);
            int end = machines.indexOf(".register();", start);
            assertTrue(end > start, "註冊語句未閉合: " + id);
            String block = machines.substring(start, end);
            assertFalse(block.contains("DShanhaiRecipeTypes."),
                    "可禁用機器不得使用 gt_shanhai 自建配方類型（自寫配方要用）: " + id);
            assertFalse(block.contains("GTRecipeTypes.DUMMY_RECIPES"),
                    "被動模組（DUMMY）不得列入可禁用名單: " + id);
        }
    }

    @Test
    void everyPrimordialMachineIsClassified() {
        String machines = readSource(MACHINES_SOURCE);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\.multiblock\\(\"(primordial_[a-z0-9_]+)\"")
                .matcher(machines);
        java.util.Set<String> registered = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            registered.add(matcher.group(1));
        }
        assertFalse(registered.isEmpty(), "未掃到任何原初機器註冊語句");
        for (String path : registered) {
            boolean classified = PrimordialSeriesMachines.disableableIds().contains(path)
                    || PrimordialSeriesMachines.alwaysKeptIds().contains(path);
            if (classified) {
                continue;
            }
            int start = machines.indexOf(".multiblock(\"" + path + "\"");
            String block = machines.substring(start, machines.indexOf(".register();", start));
            assertTrue(block.contains("DShanhaiRecipeTypes.")
                            || block.contains("GTRecipeTypes.DUMMY_RECIPES")
                            || block.contains("buildDeepSpaceExtractionRecipeTypes()"),
                    "新原初機器必須明確歸類（可禁用 / 永遠保留 / 帶自建配方類型）: gt_shanhai:" + path);
        }
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new AssertionError("讀取失敗: " + path, e);
        }
    }
}
