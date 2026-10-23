package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：確保優化同時接入主機與所有原初模組，而非只有孤立工具類。 */
class PrimordialStructureDetectionSourceTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial");

    @Test
    void hostAndModulesUseTheSameSparsePatternAndFormationQueue() throws IOException {
        for (String name : new String[]{"PrimordialOmegaEngineMachine", "PrimordialOmegaEngineModuleBase"}) {
            String source = Files.readString(ROOT.resolve(name + ".java"));
            assertTrue(source.contains("PrimordialStructurePattern.optimize(super.getPattern())"), name);
            assertTrue(source.contains("structureCheck.asyncCheck(this, period)"), name);
            assertTrue(source.contains("structureCheck.cancel()"), name);
        }
    }
}
