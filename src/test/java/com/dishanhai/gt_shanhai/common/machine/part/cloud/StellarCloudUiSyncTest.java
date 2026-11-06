package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudUiContract.*;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：真正經過 wire codec 的快照與回執，以及另兩頁的非樂觀操作。 */
class StellarCloudUiSyncTest {
    private static final ControlState ON = new ControlState(true, "");
    private static final ControlState OFF = new ControlState(false, "gt_shanhai.cloud.status.denied");

    @Test void immutableLibrarySnapshotAndReceiptRoundTripKeepIdentityAndHiddenSelection() {
        UUID id = new UUID(1, 2), member = new UUID(3, 4);
        var rows = new ArrayList<>(List.of(new RowState(id, true, true, OFF)));
        var controls = new EnumMap<ActionKind, ControlState>(ActionKind.class);
        controls.put(ActionKind.UPLOAD, ON);
        var state = new LibraryState(true, "長名稱", "gtceu:lathe", List.of("", "gtceu:lathe"), 1, 3, 73, 37, rows,
                new SelectionState(member, true, false, false, false), member, true, true, true, controls);
        rows.clear(); controls.clear();
        assertEquals(1, state.visibleRows().size());
        assertThrows(UnsupportedOperationException.class, () -> state.controls().clear());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StellarCloudUiSync.writeLibrary(buffer, state);
            assertEquals(state, StellarCloudUiSync.readLibrary(buffer));
            var receipt = new ActionResult(42, ActionKind.QUICK_WITHDRAW, ResultKind.WAITING, id, "gt_shanhai.cloud.status.withdraw_queued");
            StellarCloudUiSync.writeResult(buffer, receipt);
            assertEquals(receipt, StellarCloudUiSync.readResult(buffer));
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }

    @Test void configAndCoreWireStatesPreserveOfflineUnboundAndDisabledReasons() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var config = new ConfiguratorView(true, true, "gui.gt_shanhai.stellar_cloud.source_native", "gui.gt_shanhai.stellar_cloud.draining", 12, OFF);
            StellarCloudUiSync.writeConfigurator(buffer, config);
            assertEquals(config, StellarCloudUiSync.readConfigurator(buffer));
            var core = new CoreView(true, null, false, false, ON, OFF);
            StellarCloudUiSync.writeCore(buffer, core);
            assertEquals(core, StellarCloudUiSync.readCore(buffer));
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }

    @Test void configWaitsForSnapshotAndMatchingReceiptAndNeverRetriesOnTimeout() {
        var calls = new AtomicInteger(); var refreshes = new AtomicInteger(); var request = new AtomicInteger(); var clock = new AtomicLong();
        var controller = new ConfiguratorController(new ConfiguratorActions() {
            public void setEnabled(int id, boolean value) { request.set(id); calls.incrementAndGet(); }
            public void refresh() { refreshes.incrementAndGet(); }
        }, clock::get);
        assertFalse(controller.setEnabled(true));
        var state = new ConfiguratorView(true, false, "gui.gt_shanhai.stellar_cloud.source_native", "gui.gt_shanhai.stellar_cloud.disabled", 0, ON);
        controller.acceptSnapshot(state);
        assertTrue(controller.setEnabled(true));
        assertEquals(state, controller.state());
        assertEquals("gt_shanhai.cloud.ui.pending", controller.feedbackKey());
        assertFalse(controller.setEnabled(true));
        clock.set(5000); controller.tick(); controller.tick();
        assertEquals(1, calls.get()); assertEquals(1, refreshes.get());
        assertFalse(controller.acceptResult(new ActionResult(request.get(), ActionKind.BIND, ResultKind.SUCCESS, null, "x")));
        assertTrue(controller.acceptResult(new ActionResult(request.get(), ActionKind.SET_ENABLED, ResultKind.REJECTED, null, OFF.reasonKey())));
        assertEquals(state, controller.state());
        assertEquals(OFF.reasonKey(), controller.feedbackKey());
        assertTrue(controller.setEnabled(true));
    }

    @Test void coreRejectsUnbindingWhenDisabledAndUsesSeparateBindingReceipts() {
        var request = new AtomicInteger(); var calls = new AtomicInteger();
        var controller = new CoreController(new CoreActions() {
            public void changeBinding(int id, boolean bind) { request.set(id); calls.incrementAndGet(); }
            public void refresh() { fail("No timeout"); }
        }, () -> 0);
        assertFalse(controller.bind());
        controller.acceptSnapshot(new CoreView(true, null, false, false, ON, OFF));
        assertFalse(controller.unbind()); assertTrue(controller.bind()); assertFalse(controller.bind());
        assertFalse(controller.acceptResult(new ActionResult(request.get(), ActionKind.UNBIND, ResultKind.SUCCESS, null, "x")));
        assertTrue(controller.acceptResult(new ActionResult(request.get(), ActionKind.BIND, ResultKind.SUCCESS, null, "gt_shanhai.cloud.ae_core.status.bound")));
        assertNull(controller.state().boundPlayer(), "回執不能樂觀改寫節點身份");
        assertEquals(1, calls.get());
    }
}
