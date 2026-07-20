package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShanhaiTerminalExecutionSourceTest {

    private static final Path BINDING = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "terminal", "ShanhaiTerminalAeBinding.java");
    private static final Path MATERIALS = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "terminal", "ShanhaiTerminalMaterialService.java");
    private static final Path EXECUTOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "terminal", "ShanhaiStructureExecutor.java");
    private static final Path BEHAVIOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiUltimateTerminalBehavior.java");

    @Test
    void bindingResolvesGridStorageAndActionHostTogether() throws Exception {
        String source = Files.readString(BINDING);

        assertTrue(source.contains("record Context(IGrid grid, MEStorage storage, IActionHost host"));
        assertTrue(source.contains("node.getGrid()"));
        assertTrue(source.contains("node.isOnline()"));
        assertTrue(source.contains("IActionSource.ofPlayer(player, host)"));
    }

    @Test
    void materialOperationsSimulateBeforeMutatingAe() throws Exception {
        String source = Files.readString(MATERIALS);

        assertTrue(source.contains("Actionable.SIMULATE"));
        assertTrue(source.contains("Actionable.MODULATE"));
        assertTrue(source.indexOf("extractFromPlayer") < source.indexOf("extractFromAe"));
        assertTrue(source.indexOf("returnToPlayer") < source.indexOf("returnToAe"));
        assertTrue(source.contains("record RequestTarget(AEKey key, long amount, String displayName)"));
        assertTrue(source.contains("resolveCraftingKey"));
        assertTrue(source.contains("getFuzzyCraftable"));
        assertTrue(source.contains("canStoreDismantled"));
        assertTrue(source.contains("storeDismantled"));
        assertTrue(source.contains("if (ae != null) return canInsertAllToAe(ae, itemAmounts)"));
        assertTrue(source.contains("if (ae != null) return insertAllToAe(ae, itemAmounts)"));
        assertTrue(source.contains("packDismantledAsSda"));
        assertTrue(source.contains("DShanhaiVirtualCellSavedData.get(server)"));
    }

    @Test
    void executorPreflightsBeforeRemovingExistingParts() throws Exception {
        String source = Files.readString(EXECUTOR);

        assertTrue(source.contains("preflight(plan"));
        assertTrue(source.contains("if (!preflight.success())"));
        assertTrue(source.indexOf("if (!preflight.success())") < source.indexOf("removeExisting"));
        assertTrue(source.contains("refund"));
    }

    @Test
    void dismantleUsesDedicatedAeFirstAndSdaFallbackStorage() throws Exception {
        String executor = Files.readString(EXECUTOR);

        int dismantleMethod = executor.indexOf("public Result dismantle");
        String dismantleSource = executor.substring(dismantleMethod);
        assertTrue(dismantleSource.contains("canStoreDismantled(player, ae, dismantleReturns)"));
        assertTrue(dismantleSource.contains("storeDismantled(player, ae, removedStacks)"));
        assertTrue(dismantleSource.indexOf("canStoreDismantled") < dismantleSource.indexOf("removeExisting"));
        assertFalse(dismantleSource.contains("materials.refund"));
        assertFalse(dismantleSource.contains("entry.kind() != ShanhaiStructurePlan.Kind.BLOCKED"));
        assertFalse(dismantleSource.contains("entry.kind() == ShanhaiStructurePlan.Kind.BLOCKED"));
        assertTrue(dismantleSource.contains("restoreExisting(level, removedEntries.get(i), removedStacks.get(i))"));
    }

    @Test
    void creativePlayersBuildImmediatelyWithoutInventoryOrAeResources() throws Exception {
        String behavior = Files.readString(BEHAVIOR);
        String executor = Files.readString(EXECUTOR);

        int creativeBranch = behavior.indexOf("if (serverPlayer.isCreative())");
        int craftingPhase = behavior.indexOf("ShanhaiTerminalCraftingManager.Phase phase");
        assertTrue(creativeBranch >= 0 && creativeBranch < craftingPhase);
        assertTrue(behavior.contains("ShanhaiTerminalCraftingManager.clear(terminal)"));
        assertTrue(behavior.contains("executor.executeCreative(serverPlayer, plan)"));

        int creativeMethod = executor.indexOf("public Result executeCreative");
        int dismantleMethod = executor.indexOf("public Result dismantle");
        assertTrue(creativeMethod >= 0 && creativeMethod < dismantleMethod);
        String creativeSource = executor.substring(creativeMethod, dismantleMethod);
        assertFalse(creativeSource.contains("preflight("));
        assertFalse(creativeSource.contains("takeOne("));
        assertFalse(creativeSource.contains("canReturnAll("));
        assertFalse(creativeSource.contains("refund("));
        assertTrue(creativeSource.contains("entry.desired().copyWithCount(1)"));
    }
}
