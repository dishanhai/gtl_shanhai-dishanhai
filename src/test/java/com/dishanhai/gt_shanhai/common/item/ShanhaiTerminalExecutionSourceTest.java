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
    private static final Path HEIGHT_VALIDATOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "terminal", "ShanhaiStructureBuildHeightValidator.java");
    private static final Path BEHAVIOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiUltimateTerminalBehavior.java");
    private static final Path CONFIG = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiUltimateTerminalConfig.java");
    private static final Path ZH_CN = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "lang", "zh_cn.json");

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
    void executorReservesBuildMaterialsBeforePlacementInsteadOfPerBlockAeExtraction() throws Exception {
        String executor = Files.readString(EXECUTOR);
        String materials = Files.readString(MATERIALS);

        int execute = executor.indexOf("public Result execute(");
        int creative = executor.indexOf("public Result executeCreative(");
        String survivalBuild = executor.substring(execute, creative);
        assertTrue(survivalBuild.contains("materials.prepareBuildBatch(player, ae, plan)"));
        assertFalse(survivalBuild.contains("materials.takeOne("),
                "生存建造不得每放一个方块都重新向 AE extract 一次");
        assertTrue(survivalBuild.indexOf("prepareBuildBatch") < survivalBuild.indexOf("removeExisting"));

        assertTrue(materials.contains("bulkExtractFromAe"));
        assertTrue(materials.contains("ae.storage().extract(key, amount, Actionable.SIMULATE"));
        assertTrue(materials.contains("ae.storage().extract(key, amount, Actionable.MODULATE"));
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
        assertTrue(behavior.contains("executor.executeCreative(serverPlayer, plan, ae)"));

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

    @Test
    void buildHeightGuardStopsEveryBuildPathBeforeMutationOrAeRequests() throws Exception {
        String behavior = Files.readString(BEHAVIOR);
        String executor = Files.readString(EXECUTOR);
        String validator = Files.readString(HEIGHT_VALIDATOR);

        assertTrue(validator.contains("y < minBuildHeight || y >= maxBuildHeight"));
        assertTrue(validator.contains("record Result("));
        assertTrue(validator.contains(".filter(entry -> !entry.candidates().isEmpty())"));
        assertTrue(validator.contains("GTCEU_MAX_BUILD_HEIGHT = 320"));
        assertTrue(validator.contains("Math.min(worldMaxBuildHeight, GTCEU_MAX_BUILD_HEIGHT)"));
        assertTrue(behavior.contains("ShanhaiStructureBuildHeightValidator.validateForGtceu("));
        assertTrue(executor.contains("ShanhaiStructureBuildHeightValidator.validateForGtceu("));
        assertTrue(count(behavior, "if (rejectOutOfBuildHeight(serverPlayer, terminal, plan))") == 3);
        assertTrue(behavior.contains("ShanhaiTerminalCraftingManager.clear(terminal)"));
        assertTrue(behavior.contains("message.gt_shanhai.ultimate_terminal.build_height_exceeded"));
        assertTrue(behavior.contains("message.gt_shanhai.ultimate_terminal.gtceu_height_exceeded"));
        assertTrue(behavior.contains("level.getMaxBuildHeight() > result.maxBuildHeight()"));
        assertTrue(behavior.contains("result.upperLimitExceeded()"));

        int execute = executor.indexOf("public Result execute(");
        int creative = executor.indexOf("public Result executeCreative(");
        int dismantle = executor.indexOf("public Result dismantle(");
        String survivalBuild = executor.substring(execute, creative);
        String creativeBuild = executor.substring(creative, dismantle);
        String dismantleSource = executor.substring(dismantle);
        assertTrue(survivalBuild.contains("validateBuildHeight(level, plan)"));
        assertTrue(creativeBuild.contains("validateBuildHeight(level, plan)"));
        assertFalse(dismantleSource.contains("validateBuildHeight(level, plan)"));
    }

    @Test
    void aeModeGatesEveryTerminalAeInventoryRequestAndReturnPath() throws Exception {
        String behavior = Files.readString(BEHAVIOR);
        String config = Files.readString(CONFIG);
        String lang = Files.readString(ZH_CN);

        assertTrue(config.contains("AE_MODE_KEY = \"AeMode\""));
        assertTrue(config.contains("LEGACY_AE_REQUEST_KEY = \"AeRequestMode\""));
        assertTrue(config.contains("isAeMode(ItemStack stack)"));
        assertTrue(config.contains("setAeMode(ItemStack stack, boolean aeMode)"));
        assertTrue(behavior.contains("resolveAeContext(serverPlayer, terminal)"));
        assertTrue(behavior.contains("resolveAeContext(player, terminal)"));
        assertTrue(behavior.contains("if (!ShanhaiUltimateTerminalConfig.isAeMode(terminal)) return null;"));
        assertTrue(count(behavior, "ShanhaiTerminalAeBinding.resolve(") == 1);
        assertFalse(behavior.contains("isAeRequestMode"));
        assertTrue(behavior.contains("materials.preflight(plan, serverPlayer, ae)"));
        assertTrue(behavior.contains("materials.prioritizer(player, ae)"));
        assertTrue(behavior.contains("executor.execute(serverPlayer, plan, ae)"));
        assertTrue(behavior.contains("executor.dismantle(serverPlayer, plan, ae)"));
        assertTrue(behavior.contains("ShanhaiTerminalCraftingManager.clear(terminal)"));
        assertTrue(behavior.contains("gui.gt_shanhai.ultimate_terminal.ae_mode"));
        assertTrue(lang.contains("\"gui.gt_shanhai.ultimate_terminal.ae_mode\": \"AE 模式\""));
    }

    @Test
    void absoluteReplacementReturnsObstructionsThroughDismantleBatchStorage() throws Exception {
        String executor = Files.readString(EXECUTOR);

        int execute = executor.indexOf("public Result execute(");
        int creative = executor.indexOf("public Result executeCreative(");
        String survivalBuild = executor.substring(execute, creative);
        assertTrue(survivalBuild.contains("entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE"));
        assertTrue(survivalBuild.contains("canStoreDismantled(player, ae, forcedReturns)"));
        assertTrue(survivalBuild.contains("storeDismantled(player, ae, forcedReturns)"));
        assertTrue(survivalBuild.contains("forcedReturns.size()"));
        assertTrue(survivalBuild.indexOf("canStoreDismantled(player, ae, forcedReturns)")
                < survivalBuild.indexOf("removeExisting"));

        String creativeBuild = executor.substring(creative, executor.indexOf("public Result dismantle("));
        assertTrue(creativeBuild.contains("entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE"));
        assertTrue(creativeBuild.contains("storeDismantled(player, ae, forcedReturns)"));
        assertTrue(creativeBuild.contains("storeDismantled(player, null, forcedReturns)"));
    }

    private static int count(String value, String needle) {
        int result = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            result++;
            index += needle.length();
        }
        return result;
    }
}
