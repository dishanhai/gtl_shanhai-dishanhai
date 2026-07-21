package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShanhaiTerminalCraftingSourceTest {

    private static final Path MANAGER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "terminal", "ShanhaiTerminalCraftingManager.java");

    @Test
    void craftingSessionHasExplicitConfirmationStates() throws Exception {
        String source = Files.readString(MANAGER);

        assertTrue(source.contains("CALCULATING"));
        assertTrue(source.contains("READY_TO_SUBMIT"));
        assertTrue(source.contains("SUBMITTED"));
        assertTrue(source.contains("READY_TO_BUILD"));
        assertTrue(source.contains("CALC_TIMEOUT_TICKS = 400"));
    }

    @Test
    void aePlanningIsAsynchronousAndUsesMissingItemReports() throws Exception {
        String source = Files.readString(MANAGER);

        assertTrue(source.contains("beginCraftingCalculation"));
        assertTrue(source.contains("CalculationStrategy.REPORT_MISSING_ITEMS"));
        assertTrue(source.contains("Future<ICraftingPlan>"));
        assertTrue(source.contains("TickEvent.Phase.END"));
        assertTrue(source.contains("requestableShortages"));
        assertTrue(source.contains("describeFailedPlan"));
    }

    @Test
    void buildReadinessRequiresARescanAndMatchingFingerprint() throws Exception {
        String source = Files.readString(MANAGER);

        assertTrue(source.contains("currentPlan.fingerprint().equals(session.planFingerprint)"));
        assertTrue(source.contains("materials.shortages(currentPlan"));
        assertTrue(source.contains("session.phase = Phase.READY_TO_BUILD"));
        assertTrue(source.contains("ShanhaiUltimateTerminalConfig.getTerminalUuid"));
    }

    @Test
    void structureFingerprintMustNotDependOnCandidatePriority() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "terminal", "ShanhaiStructurePlan.java"));

        assertTrue(source.contains("entry.pos().asLong()"));
        assertTrue(source.contains("entry.kind()"));
        assertTrue(source.contains("entry.chamberCapable()"));
        assertTrue(source.contains("machineId"));
        assertTrue(source.contains("mirrored"));
        assertTrue(source.contains("repeatCount"));
        assertTrue(!source.contains("desiredId"),
                "结构指纹不能依赖候选物品 ID，否则 AE 候选优先级变化会让同一结构反复失配");
    }
}
