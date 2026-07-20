package com.dishanhai.gt_shanhai.common.machine.primordial.module.engraving;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialEngravingModuleTest {

    private static final Path MODULE_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "module", "engraving",
            "PrimordialEngravingModule.java");
    private static final Path LOGIC_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "module", "engraving",
            "PrimordialEngravingModuleLogic.java");

    @Test
    void engravingModuleUsesPrimordialModuleLogicSoHostMyriadMultiplierApplies() throws Exception {
        assertTrue(Files.exists(LOGIC_SOURCE),
                "世线蚀刻核心必须有自己的 Logic，才能接入万象主机输出倍率");

        String module = Files.readString(MODULE_SOURCE);
        String logic = Files.readString(LOGIC_SOURCE);

        assertTrue(logic.contains("extends PrimordialModuleRecipeLogic"),
                "世线蚀刻核心必须走 PrimordialModuleRecipeLogic，才能应用主机万象倍率");
        assertTrue(module.contains("public PrimordialEngravingModuleLogic createRecipeLogic(Object... args)"));
        assertTrue(module.contains("return new PrimordialEngravingModuleLogic(this);"));
        assertTrue(module.contains("public PrimordialEngravingModuleLogic getRecipeLogic()"));
    }
}
