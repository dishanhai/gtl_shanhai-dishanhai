package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Regression contracts for the dishanhai shop's native AE planning integration. */
class ShopAutoCraftSourceTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    private static String source(String file) throws Exception {
        return Files.readString(ROOT.resolve(file));
    }

    @Test
    void usedMaterialsNeverBecomeAdditionalCraftingTargets() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertFalse(text.contains("expandShopDependencies"),
                "Consumed intermediate stock must not create replenishment orders");
        assertFalse(text.contains("buildShopGoodsKeyIndex"));
        assertFalse(text.contains("appendDependenciesFirst"));
    }

    @Test
    void shortagesUseActualPriceAndOutstandingFinalOutputs() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertTrue(text.contains("entry.getEffectiveCost(discount)"));
        assertTrue(text.contains("cpu.getJobStatus()"));
        assertTrue(text.contains("status.crafting()"));
        assertFalse(text.contains("getRequestedAmount("),
                "AE waiting amounts include intermediate inputs, not just promised final output");
        assertTrue(text.contains("ShopAutoCraftAmounts.missing("));
        assertFalse(text.contains("clampToLong"));
    }

    @Test
    void goalsShareStockReservationsAndOnlyOneCalculationRunsAtATime() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertTrue(text.contains("session.nextItem"));
        assertTrue(text.contains("new ShopAutoCraftActionSource("));
        assertTrue(text.contains("session.reserved"));
        assertTrue(text.contains("realUsedItems("),
                "Virtual presence inputs must not be reserved as consumable materials");
        String mixin = source("mixin/ShopCraftingSimulationInventoryMixin.java");
        assertTrue(mixin.contains("NetworkCraftingSimulationState.class"));
        assertTrue(mixin.contains("ShopAutoCraftActionSource"));
        assertTrue(mixin.contains("ShopAutoCraftAmounts.remainingStock("));
        assertTrue(mixin.contains("method = \"simulateExtractParent\""),
                "GTLCore's lazy inventory must also honor shop reservations");
        assertFalse(mixin.contains("list.set("),
                "The native list is skipped by GTLCore and cannot enforce reservations");
        assertTrue(MixinConfigs.readAll()
                .contains("\"ShopCraftingSimulationInventoryMixin\""));
    }

    @Test
    void confirmationAndCancellationAreBoundToAnExactPlan() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertTrue(text.contains("session.planId.equals(planId)"));
        assertTrue(text.contains("READY.remove(player.getUUID(), session)"));
        assertTrue(text.contains("session.grid != grid"));
        assertTrue(text.contains("ShopAutoCraftAmounts.matchesPlannedOutputs(plannedOutputs, current.shortages)"));
        for (String packet : new String[]{"ShopAutoCraftPlanPacket", "ShopAutoCraftConfirmPacket"}) {
            String packetSource = source("network/" + packet + ".java");
            assertTrue(packetSource.contains("buf.readUUID()"));
            assertTrue(packetSource.contains("buf.writeUUID(planId)"));
        }
    }

    @Test
    void workerSimulationNeverReadsTheLiveNetworkIncludingFuzzySearch() throws Exception {
        String text = source("mixin/ShopCraftingSimulationInventoryMixin.java");
        assertTrue(text.contains("method = \"findFuzzyParent\""));
        assertTrue(text.contains("@At(\"HEAD\")"));
        assertFalse(text.contains("getInventory()"));
        assertFalse(text.contains(".extract("));
        assertTrue(text.contains("shopSource.stockSnapshot(storage)"));
    }

    @Test
    void staleConfirmationNeverRestartsAndUsesCurrentRetainedStock() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        String confirmation = text.substring(text.indexOf("public static void confirmPlan("),
                text.indexOf("private static boolean targetStillValid("));
        assertFalse(text.contains("restartPlan("));
        assertFalse(confirmation.contains("beginPlan("));
        assertFalse(confirmation.contains("beginCartPlan("));
        assertFalse(confirmation.contains("retainedChanged"));
        assertTrue(confirmation.contains("ShopAutoCraftAmounts.submissionStock(current.retained, consumptions)"));
        assertTrue(confirmation.contains("consumptions.add(item.used)"));
        assertTrue(confirmation.indexOf("hasReservedStock(") < confirmation.indexOf("submitJob("));
        assertTrue(confirmation.indexOf("READY.remove(") < confirmation.indexOf("submitJob("));
    }

    @Test
    void materialTraversalIsCachedOnceAfterCalculation() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertEquals(1, text.split("realUsedItems\\(item.result\\)", -1).length - 1);
        assertTrue(text.contains("item.used = Map.copyOf(realUsedItems(item.result))"));
    }

    @Test
    void previewKeepsKeyIdentityAndUnboundedTotals() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertTrue(text.contains("Map<AEKey, BigInteger> merged"));
        assertFalse(text.contains("Map<String, Long> merged"));
        assertFalse(text.contains("Long::sum"));
        assertTrue(text.contains("willCraft"));
    }

    @Test
    void screenConsumesOnlyOneLeftClickAndCancelsOnEscape() throws Exception {
        String text = source("client/gui/shop/ShopAutoCraftConfirmScreen.java");
        assertTrue(text.contains("btn != 0"));
        assertTrue(text.contains("actions.finished()"));
        assertTrue(text.contains("void onClose()"));
        assertTrue(text.contains("new ShopAutoCraftConfirmPacket(planId, true)"));
        assertTrue(text.contains("new ShopAutoCraftConfirmPacket(planId, false)"));
    }

    @Test
    void nativeCpuUsesRemainingJobAmountAndRequesterOutputsAreExcluded() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertTrue(text.contains("gtShanhai$getRemainingAmount()"));
        assertTrue(text.contains("link.isStandalone()"));
        assertTrue(text.contains("cpu instanceof QuantumCraftingCPU"));
        assertFalse(text.contains("BigInteger.valueOf(output.amount())"),
                "Vanilla CPU status contains the original order size, not the remaining size");
    }

    @Test
    void prerequisitesAndPurchasesShareStockAndPendingOutputs() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        String ingredients = text.substring(text.indexOf("private static Snapshot snapshotIngredients("),
                text.indexOf("private static Snapshot snapshotLines("));
        assertTrue(ingredients.contains("return snapshotDemand(player, demand, grid, identity);"));
        String purchases = text.substring(text.indexOf("private static Snapshot snapshotLines("),
                text.indexOf("private static Snapshot snapshotDemand("));
        assertTrue(purchases.contains("return snapshotDemand(player, demand, grid, identity);"));
        assertTrue(text.contains("ShopAutoCraftAmounts.missing(demand, stock, pending)"));
    }

    @Test
    void aTimedOutGoalKeepsTheRestOfTheBatchAndCartChangesInvalidateIt() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        int timeout = text.indexOf("if (session.ticks <= CALC_TIMEOUT_TICKS) continue;");
        int advance = text.indexOf("session.nextItem++", timeout);
        String handling = text.substring(timeout, advance);
        assertTrue(handling.contains("item.future.cancel(true)"));
        assertTrue(handling.contains("item.errorKey = \"calculation_timeout\""));
        assertFalse(handling.contains("CALCULATING.remove("));
        assertTrue(text.contains("case CART -> session.lines.equals(cartLines(player))"));
    }
}
