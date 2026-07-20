package com.dishanhai.gt_shanhai.common.machine.primordial.module;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialModuleHostMultiplierCoverageTest {

    private static final Path MODULE_ROOT = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "module");

    @Test
    void recipeModulesUsePrimordialModuleLogicSoHostMultiplierCannotBeSkipped() throws Exception {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(MODULE_ROOT)) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(PrimordialModuleHostMultiplierCoverageTest::isConcreteModuleSource)
                    .forEach(path -> checkModule(path, missing));
        }

        assertTrue(missing.isEmpty(),
                "原初配方模块必须显式接入 PrimordialModuleRecipeLogic，漏接模块: " + missing);
    }

    private static boolean isConcreteModuleSource(Path path) {
        String name = path.getFileName().toString();
        return !name.endsWith("Logic.java")
                && !name.endsWith("Structure.java")
                && !name.equals("PrimordialParallelProcessingModuleBase.java")
                && !name.equals("PrimordialMassEnergyCore.java");
    }

    private static void checkModule(Path path, List<String> missing) {
        try {
            String source = Files.readString(path);
            boolean isModule = source.contains("extends PrimordialOmegaEngineModuleBase")
                    || source.contains("extends PrimordialParallelProcessingModuleBase");
            if (!isModule) {
                return;
            }
            if (!source.contains("createRecipeLogic(Object... args)")
                    || !source.contains("getRecipeLogic()")) {
                missing.add(MODULE_ROOT.relativize(path).toString());
            }
        } catch (Exception e) {
            missing.add(path + " (" + e.getMessage() + ")");
        }
    }
}
