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
    void executorBuildsAvailableMaterialsWithoutFullPreflight() throws Exception {
        String source = Files.readString(EXECUTOR);

        assertFalse(source.contains("if (!preflight.success())"),
                "生存施工不得再用全量材料预检阻塞部分施工");
        assertTrue(source.contains("BuildBatch buildBatch = materials.prepareBuildBatch(player, ae, plan)"));
        assertTrue(source.contains("Map<AEItemKey, Long> availablePreview = buildBatch.availableAmounts()"));
        assertTrue(source.indexOf("availablePreview") < source.indexOf("removeExisting"));
        assertTrue(source.contains("skippedMissing++"));
        assertTrue(source.contains("部分施工完成，缺材料跳过"));
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
        int bulk = materials.indexOf("private long bulkExtractFromAe");
        String bulkSource = materials.substring(bulk);
        assertTrue(bulkSource.contains("Actionable.SIMULATE"));
        assertTrue(bulkSource.contains("Math.min(available, amount)"));
        assertTrue(bulkSource.contains("Actionable.MODULATE"));
    }

    @Test
    void executorAndMaterialBatchSupportFluidStructureSlots() throws Exception {
        String executor = Files.readString(EXECUTOR);
        String materials = Files.readString(MATERIALS);

        assertTrue(materials.contains("AEFluidKey"));
        assertTrue(materials.contains("ReservedFluid"));
        assertTrue(materials.contains("takeOneFluid"));
        assertTrue(materials.contains("bulkExtractFluidFromAe"));
        assertTrue(materials.contains("extractFluidFromPlayer"));
        assertTrue(materials.contains("refundFluidAmount"));
        assertTrue(materials.contains("canRefundFluidForStructure"));
        assertTrue(materials.contains("canRefundFluidAmount"));
        assertTrue(materials.contains("Actionable.SIMULATE, ae.source())"));
        assertTrue(materials.contains("BucketItem"));
        assertTrue(materials.contains("shortage.getKey() instanceof AEFluidKey"));
        assertTrue(executor.contains("entry.desiredState().getFluidState()"));
        assertTrue(executor.contains("placeFluid"));
        assertTrue(executor.contains("buildBatch.takeOneFluid"));
        assertTrue(executor.contains("restoreFluid"));
        assertTrue(executor.indexOf("materials.canRefundFluidForStructure") < executor.indexOf("removeExisting"),
                "舊流體必須在替換世界前先確認能完整回收，避免部分插入後回滾造成重複或丟失");
    }

    @Test
    void dismantleUsesDedicatedAeFirstAndSdaFallbackStorage() throws Exception {
        String executor = Files.readString(EXECUTOR);

        int dismantleMethod = executor.indexOf("public Result dismantle");
        String dismantleSource = executor.substring(dismantleMethod);
        assertTrue(dismantleSource.contains("canStoreDismantled(player, ae, dismantleReturns)"));
        assertTrue(dismantleSource.contains("storeDismantled(player, ae, removedStacks)"));
        assertTrue(dismantleSource.indexOf("canStoreDismantled") < dismantleSource.indexOf("removeExisting"));
        assertFalse(dismantleSource.contains("materials.refund(player"));
        assertFalse(dismantleSource.contains("entry.kind() != ShanhaiStructurePlan.Kind.BLOCKED"));
        assertFalse(dismantleSource.contains("entry.kind() == ShanhaiStructurePlan.Kind.BLOCKED"));
        assertTrue(dismantleSource.contains("restoreExisting(level, removedEntries.get(i), removedStacks.get(i),"));
        assertTrue(dismantleSource.contains("removedEntries.get(i).currentFluid()"));
    }

    @Test
    void dismantleOnlyReservesAndRemovesRecognizedStructureParts() throws Exception {
        String executor = Files.readString(EXECUTOR);
        int dismantle = executor.indexOf("public Result dismantle(");
        String body = executor.substring(dismantle, executor.indexOf("private Result validateBuildHeight", dismantle));

        assertTrue(body.contains(".filter(ShanhaiStructurePlan.Entry::canDismantle)"),
                "容量預檢必須先排除未替換的草方塊等非結構方塊");
        assertTrue(body.contains("if (!entry.canDismantle()) continue;"),
                "實際拆除必須使用同一個結構部件判定");
    }

    @Test
    void dismantleEligibilityRequiresPositiveStructureMembership() throws Exception {
        String plan = Files.readString(EXECUTOR.resolveSibling("ShanhaiStructurePlan.java"));

        assertTrue(plan.contains("boolean currentMatchesStructure"));
        assertTrue(plan.contains("public boolean canDismantle()"));
        assertTrue(plan.contains("return currentMatchesStructure && !current.isEmpty() && kind != Kind.MANUAL;"),
                "有方塊、或被標記為待強制替換／倉室提示，都不代表它屬於結構");
    }

    @Test
    void chamberHintsPreserveMembershipInsteadOfTreatingTheWholeFootprintAsStructure() throws Exception {
        String planner = Files.readString(EXECUTOR.resolveSibling("ShanhaiStructurePlanner.java"));

        assertTrue(planner.contains("candidateItems, chamberCapable, currentMatchesStructure"),
                "只有候選部件或已識別的同系列升級部件可回收");
        String preserved = "entry.candidates(), true, entry.currentMatchesStructure()";
        int first = planner.indexOf(preserved);
        assertTrue(first >= 0 && planner.indexOf(preserved, first + 1) > first,
                "轉成倉室提示時仍須保留原有結構匹配結果");
        assertFalse(planner.contains("chamberCapable, currentAllowed || kind == ShanhaiStructurePlan.Kind.FORCE_REPLACE"));
    }

    @Test
    void dismantleRecognizesBothOldAndSelectedReplacementFamilyTiers() throws Exception {
        String planner = Files.readString(EXECUTOR.resolveSibling("ShanhaiStructurePlanner.java"));

        assertTrue(planner.contains("boolean currentMatchesStructure = currentAllowed"));
        assertTrue(planner.contains("|| (replacementApplies && contains(replacementFamily, currentBlock))"),
                "已完成升級的目標等級即使不在原候選列表，也仍是結構部件");
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
        assertTrue(Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/terminal/UltimateTerminalScreen.java")).contains("\"ae_mode\""));
        assertTrue(lang.contains("\"gui.gt_shanhai.ultimate_terminal.ae_mode\": \"AE 模式\""));
    }

    @Test
    void absoluteReplacementReturnsObstructionsThroughDismantleBatchStorage() throws Exception {
        String executor = Files.readString(EXECUTOR);

        int execute = executor.indexOf("public Result execute(");
        int creative = executor.indexOf("public Result executeCreative(");
        String survivalBuild = executor.substring(execute, creative);
        assertTrue(survivalBuild.contains("entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE"));
        assertTrue(survivalBuild.contains("canStoreDismantled(player, ae, forcedReturnCandidates)"));
        assertTrue(survivalBuild.contains("storeDismantled(player, ae, forcedReturns)"));
        assertTrue(survivalBuild.contains("forcedReturns.size()"));
        assertTrue(survivalBuild.indexOf("canStoreDismantled(player, ae, forcedReturnCandidates)")
                < survivalBuild.indexOf("removeExisting"));

        String creativeBuild = executor.substring(creative, executor.indexOf("public Result dismantle("));
        assertTrue(creativeBuild.contains("entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE"));
        assertTrue(creativeBuild.contains("storeDismantled(player, ae, forcedReturns)"));
        assertTrue(creativeBuild.contains("storeDismantled(player, null, forcedReturns)"));
    }

    @Test
    void fluidRefundChecksCapacityBeforeDepositingAndAccumulatesContainers() throws Exception {
        String materials = Files.readString(MATERIALS);
        int refund = materials.indexOf("private boolean refundFluidAmount(");
        String refundBody = materials.substring(refund, materials.indexOf("private long returnFluidToAe", refund));
        int capacityCheck = refundBody.indexOf("canRefundFluidAmount(player, ae, fluid, amount)");
        assertTrue(capacityCheck >= 0 && capacityCheck < refundBody.indexOf("returnFluidToAe("),
                "回收空間不足時不可先部分存入 AE，否則世界回滾會重複舊流體");
        assertTrue(refundBody.contains("remaining -= returned"), "只能扣除實際回收量");

        int fill = materials.indexOf("private int fillFluidRecursive(");
        String fillBody = materials.substring(fill, materials.indexOf("private FluidContainerMutation fillFluidIntoStack", fill));
        assertTrue(fillBody.contains("filled +="), "同一批流體需累計多個容器的接收量");
        assertFalse(fillBody.contains("return (int) filled.amount()"), "第一個容器僅收一部分時不能提前結束");
        assertFalse(fillBody.contains("if (nestedFilled > 0) return nestedFilled"));
    }

    @Test
    void creativeFluidReplacementAlsoChecksCapacityBeforeRemovingTheOldBlock() throws Exception {
        String executor = Files.readString(EXECUTOR);
        int creative = executor.indexOf("public Result executeCreative(");
        String creativeBody = executor.substring(creative, executor.indexOf("public Result dismantle("));
        int capacityCheck = creativeBody.indexOf("materials.canRefundFluidForStructure(");
        assertTrue(capacityCheck >= 0 && capacityCheck < creativeBody.indexOf("removeExisting("));
    }

    @Test
    void rollbackRestoresTheScannedBlockStateInsteadOfTheDefaultFacing() throws Exception {
        String executor = Files.readString(EXECUTOR);
        int restore = executor.indexOf("private void restoreExisting(");
        String restoreBody = executor.substring(restore, executor.indexOf("private boolean place(", restore));
        assertTrue(restoreBody.contains("entry.currentState()"));
        assertFalse(restoreBody.contains("block.defaultBlockState()"),
                "回滾必須保留原有朝向與其他方塊屬性");
    }

    @Test
    void rollbackAfterSuccessfulPlacementRefundsTheConsumedMaterial() throws Exception {
        String executor = Files.readString(EXECUTOR);
        int fluidRollback = executor.indexOf("if (removedFluid != Fluids.EMPTY && !restoreFluid(");
        String rollbackBody = executor.substring(fluidRollback, executor.indexOf("changed++;", fluidRollback));
        assertTrue(rollbackBody.contains("materials.refund(player, ae, entry.desired().copyWithCount(1))"),
                "成功放置已消耗 material，回滾必須按施工計畫退還一個材料");
        assertFalse(rollbackBody.contains("materials.refund(player, ae, material)"));
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
