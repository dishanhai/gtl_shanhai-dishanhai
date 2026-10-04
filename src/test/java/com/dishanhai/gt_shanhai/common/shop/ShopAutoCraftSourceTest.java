package com.dishanhai.gt_shanhai.common.shop;

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
        assertTrue(text.contains("entry.getEffectiveCost(ShopMembership.discountPercent("));
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
        assertTrue(Files.readString(Path.of("src/main/resources/gt_shanhai.mixin.json"))
                .contains("\"ShopCraftingSimulationInventoryMixin\""));
    }

    @Test
    void confirmationAndCancellationAreBoundToAnExactPlan() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertTrue(text.contains("session.planId.equals(planId)"));
        assertTrue(text.contains("READY.remove(player.getUUID(), session)"));
        assertTrue(text.contains("session.grid != grid"));
        assertTrue(text.contains("current.shortages.equals(session.shortages)"));
        for (String packet : new String[]{"ShopAutoCraftPlanPacket", "ShopAutoCraftConfirmPacket"}) {
            String packetSource = source("network/" + packet + ".java");
            assertTrue(packetSource.contains("buf.readUUID()"));
            assertTrue(packetSource.contains("buf.writeUUID(planId)"));
        }
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
        assertTrue(text.contains("private boolean finished"));
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
}
