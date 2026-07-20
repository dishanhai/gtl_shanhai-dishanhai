package com.dishanhai.gt_shanhai.common.machine.primordial.module.taixu;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialTaixuCosmicForgeModuleTest {

    private static final Path MODULE_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "module", "taixu",
            "PrimordialTaixuCosmicForgeModule.java");
    private static final Path LOGIC_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "module", "taixu",
            "PrimordialTaixuCosmicForgeModuleLogic.java");

    @Test
    void taixuForgeUsesPrimordialModuleLogicSoHostMyriadMultiplierApplies() throws Exception {
        String module = Files.readString(MODULE_SOURCE);
        String logic = Files.readString(LOGIC_SOURCE);

        assertTrue(logic.contains("extends PrimordialModuleRecipeLogic"),
                "太虚锻炉必须走 PrimordialModuleRecipeLogic，才能应用主机万象倍率");
        assertTrue(module.contains("public PrimordialTaixuCosmicForgeModuleLogic createRecipeLogic(Object... args)"));
        assertTrue(module.contains("return new PrimordialTaixuCosmicForgeModuleLogic(this);"));
        assertTrue(module.contains("public PrimordialTaixuCosmicForgeModuleLogic getRecipeLogic()"));
    }
}
