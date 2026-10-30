package com.dishanhai.gt_shanhai.common.machine.part;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StellarPatternWarningHistoryTest {
    @Test
    void directLinkCancellationPreservesTheLastObservedQuantityWhenCpuInformationIsUnavailable() {
        var history = new StellarPatternWarningHistory();
        history.record(DIM, POS, warning("job-a", 3), 7);
        history.updateOrder("job-a", StellarPatternOrderInfo.State.CANCELLED, -1);
        assertEquals(StellarPatternOrderInfo.State.CANCELLED, history.entries(DIM, POS).getFirst().order().state());
        assertEquals(48, history.entries(DIM, POS).getFirst().order().remaining());
    }
    @Test
    void completingNewOrdersDoesNotDeleteOlderHistoryOrUnfinishedOrders() {
        var history = new StellarPatternWarningHistory();
        history.record(DIM, POS, warning("unfinished", 9000), 7);
        for (int index = 0; index < 513; index++) {
            String id = "finished-" + index;
            history.record(DIM, POS, warning(id, index), 7);
            history.updateOrder(id, StellarPatternOrderInfo.State.COMPLETED, 0);
        }
        assertEquals(514, history.entries(DIM, POS).size());
        assertTrue(history.entries(DIM, POS).stream().anyMatch(entry -> entry.order().id().equals("unfinished")));
        assertTrue(history.entries(DIM, POS).stream().anyMatch(entry -> entry.order().id().equals("finished-0")));
        assertEquals(514, StellarPatternWarningHistory.load(history.save(new CompoundTag())).entries(DIM, POS).size());
    }
    @Test
    void onlyTheRealLinkDoneFlagResolvesAndCancellationTakesPrecedence() {
        boolean[] flags = {false, false};
        var link = new appeng.api.networking.crafting.ICraftingLink() {
            public boolean isCanceled() { return flags[0]; }
            public boolean isDone() { return flags[1]; }
            public void cancel() { flags[0] = true; }
            public boolean isStandalone() { return false; }
            public void writeToNBT(CompoundTag tag) {}
            public java.util.UUID getCraftingID() { return new java.util.UUID(1, 2); }
        };
        assertEquals(StellarPatternOrderInfo.State.RUNNING, StellarPatternOrderInfo.stateOf(link));
        flags[1] = true;
        assertTrue(StellarPatternOrderInfo.stateOf(link).resolved());
        flags[0] = true;
        assertEquals(StellarPatternOrderInfo.State.CANCELLED, StellarPatternOrderInfo.stateOf(link));
        assertFalse(StellarPatternOrderInfo.stateOf(link).resolved());
        assertEquals(StellarPatternOrderInfo.State.UNTRACKED, StellarPatternOrderInfo.stateOf(null));
    }

    @Test
    void longUnicodeDiagnosticsSurviveTheActualCompressedNbtFormat() throws Exception {
        String reason = "長原因與完整識別資訊".repeat(12000);
        var history = new StellarPatternWarningHistory();
        history.record(DIM, POS, new StellarPatternStuckReport.Entry(1, "", reason, reason, reason, List.of(), "type", "recipe"), null);
        var bytes = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(history.save(new CompoundTag()), bytes);
        var restored = StellarPatternWarningHistory.load(net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(reason, restored.entries(DIM, POS).getFirst().reason());
        assertEquals(reason, restored.entries(DIM, POS).getFirst().inputs());
    }
    private static final ResourceLocation DIM = new ResourceLocation("minecraft", "overworld");
    private static final BlockPos POS = new BlockPos(1, 64, 2);

    private static StellarPatternStuckReport.Entry warning(String orderId, int slot) {
        var order = new StellarPatternOrderInfo(orderId, "Player (UUID)", "CPU A", "Copper coin", "dishanhai:copper_coin",
                64, 48, StellarPatternOrderInfo.State.RUNNING);
        return new StellarPatternStuckReport.Entry(slot, "", "original cause", "input", "output", List.of(),
                "gtceu:coin_forge", "recipe", StellarPatternStuckReport.Cause.COIN_MINT_LOCKED,
                StellarPatternStuckReport.Diagnosis.EMPTY, order);
    }

    @Test
    void completionAndCancellationRemainDistinctAndSurviveSaveLoad() {
        var history = new StellarPatternWarningHistory();
        history.record(DIM, POS, warning("job-a", 3), 7);
        history.record(DIM, POS, warning("job-b", 3), 8);
        long id = history.build(DIM, POS).id();
        assertEquals(2, history.entries(DIM, POS).size(), "同槽位的不同 AE 訂單必須各自保留");
        assertEquals(StellarPatternOrderInfo.State.RUNNING, history.entries(DIM, POS).getFirst().order().state());
        history.updateOrder("job-a", StellarPatternOrderInfo.State.COMPLETED, 0);
        history.updateOrder("job-b", StellarPatternOrderInfo.State.CANCELLED, 48);
        var restored = StellarPatternWarningHistory.load(history.save(new CompoundTag()));
        assertEquals(id, restored.build(DIM, POS).id());
        assertEquals(StellarPatternOrderInfo.State.COMPLETED, restored.entries(DIM, POS).get(0).order().state());
        assertEquals(StellarPatternOrderInfo.State.CANCELLED, restored.entries(DIM, POS).get(1).order().state());
        assertEquals(48, restored.entries(DIM, POS).get(1).order().remaining());
        assertEquals("original cause", restored.entries(DIM, POS).getFirst().reason());
        assertEquals(java.util.Set.of(7, 8), restored.requesters(DIM, POS));
        assertArrayEquals(new int[]{2, 0, 0, 1, 1}, restored.build(DIM, POS).counts());
    }

    @Test
    void nestedDispatchRestoresItsRequestingPlayerEvenWhenTheInnerDispatchHasNoPlayer() {
        assertNull(StellarPatternCraftingContext.currentAePlayerId());
        StellarPatternCraftingContext.push(42);
        try {
            StellarPatternCraftingContext.push(null);
            try { assertNull(StellarPatternCraftingContext.currentAePlayerId()); }
            finally { StellarPatternCraftingContext.pop(); }
            assertEquals(42, StellarPatternCraftingContext.currentAePlayerId());
        } finally { StellarPatternCraftingContext.pop(); }
        assertNull(StellarPatternCraftingContext.currentAePlayerId());
        assertNull(StellarPatternCraftingContext.currentOrder());
    }

    @Test
    void terminalOrdersCannotBeResetByAnotherSlotObservationAndUntrackedOrdersNeverBecomeResolved() {
        var history = new StellarPatternWarningHistory();
        history.record(DIM, POS, warning("job-a", 1), 7);
        history.updateOrder("job-a", StellarPatternOrderInfo.State.CANCELLED, 48);
        history.record(DIM, POS, warning("job-a", 1), 7);
        assertEquals(StellarPatternOrderInfo.State.CANCELLED, history.entries(DIM, POS).getFirst().order().state());
        history.record(DIM, POS, new StellarPatternStuckReport.Entry(2, "", "reason", "in", "out", List.of(), "type", "recipe"), null);
        assertEquals(StellarPatternOrderInfo.State.UNTRACKED, history.entries(DIM, POS).getLast().order().state());
        assertFalse(history.entries(DIM, POS).getLast().order().state().resolved());
    }
}
