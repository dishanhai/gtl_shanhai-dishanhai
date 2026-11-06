package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudUiContract.*;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：走主介面實際使用的可見回饋接點，不只檢查回執是否已接收。 */
class StellarCloudLibraryFeedbackTest {
    private static final String PREFIX = "gt_shanhai.cloud.";
    private static final UUID ORIGINAL = new UUID(0, 1), OTHER = new UUID(0, 2);

    private LibraryController controller() {
        var actions = (LibraryActions) Proxy.newProxyInstance(LibraryActions.class.getClassLoader(),
                new Class<?>[]{LibraryActions.class}, (proxy, method, args) -> null);
        var controller = new LibraryController(actions, () -> 0);
        controller.acceptSnapshot(snapshot(true, true));
        return controller;
    }

    private LibraryState snapshot(boolean ready, boolean authorized) {
        var enabled = new ControlState(true, "");
        return new LibraryState(ready, "", "", List.of(), 0, 1, 1, 1,
                List.of(new RowState(ORIGINAL, false, false, enabled)),
                new SelectionState(ORIGINAL, true, true, false, false), null, true, authorized, false,
                Map.of(ActionKind.UPLOAD, enabled, ActionKind.WITHDRAW, enabled));
    }

    private String visible(LibraryController controller, String serverKey) {
        String overlay = !controller.state().synchronizedReady() ? WAITING
                : !controller.state().authorized() ? PREFIX + "status.denied" : controller.feedbackKey();
        return StellarCloudLibraryUi.Feedback.visible(controller, serverKey, overlay);
    }

    private ActionResult complete(LibraryController controller, ActionKind action) {
        assertTrue(action == ActionKind.UPLOAD ? controller.uploadOriginal() : controller.withdrawSelected());
        var result = new ActionResult(controller.pendingRequestId().orElseThrow(), action, ResultKind.SUCCESS,
                action == ActionKind.UPLOAD ? null : ORIGINAL,
                PREFIX + (action == ActionKind.UPLOAD ? "status.uploaded" : "status.withdrawn"));
        assertTrue(controller.acceptResult(result));
        assertEquals(result.messageKey(), visible(controller, result.messageKey()));
        return result;
    }

    @Test void staleUuidRejectionIsVisibleAfterEitherSuccessfulOriginalAction() {
        for (var action : List.of(ActionKind.UPLOAD, ActionKind.WITHDRAW)) {
            var controller = controller();
            var result = complete(controller, action);
            assertTrue(controller.select(0));
            assertEquals(PREFIX + "ui.list_changed", visible(controller, PREFIX + "ui.list_changed"));
            assertSame(result, controller.lastResult(), "显示最新服务端反馈不必清除历史回执");
        }
    }

    @Test void emptyCellRejectionIsVisibleEvenWithoutAnyControllerSelectionIntent() {
        for (var action : List.of(ActionKind.UPLOAD, ActionKind.WITHDRAW)) {
            var controller = controller();
            complete(controller, action);
            // 空格取消選取走 CellQuickAction 的獨立封包，不能靠 controller.select 清除舊回執。
            assertEquals(PREFIX + "ui.list_changed", visible(controller, PREFIX + "ui.list_changed"));
            assertEquals(PREFIX + "ui.list_changed", visible(controller, PREFIX + "ui.list_changed"));
        }
    }

    @Test void pendingCoversServerTextUntilRequestActionAndTargetAllMatch() {
        var controller = controller();
        complete(controller, ActionKind.UPLOAD);
        assertTrue(controller.withdrawSelected());
        int request = controller.pendingRequestId().orElseThrow();
        assertTrue(controller.select(0));
        for (var mismatch : List.of(
                new ActionResult(request + 1, ActionKind.WITHDRAW, ResultKind.SUCCESS, ORIGINAL, PREFIX + "status.withdrawn"),
                new ActionResult(request, ActionKind.UPLOAD, ResultKind.SUCCESS, ORIGINAL, PREFIX + "status.uploaded"),
                new ActionResult(request, ActionKind.WITHDRAW, ResultKind.SUCCESS, OTHER, PREFIX + "status.withdrawn"))) {
            assertFalse(controller.acceptResult(mismatch));
            assertEquals(request, controller.pendingRequestId().orElseThrow());
            assertEquals(PREFIX + "ui.pending", visible(controller, PREFIX + "ui.list_changed"));
        }
        assertTrue(controller.acceptResult(new ActionResult(request, ActionKind.WITHDRAW, ResultKind.SUCCESS,
                ORIGINAL, PREFIX + "status.withdrawn")));
        assertTrue(controller.pendingRequestId().isEmpty());
        assertEquals(PREFIX + "ui.list_changed", visible(controller, PREFIX + "ui.list_changed"));
    }

    @Test void replayedOldReceiptCannotCoverTheNewerServerMessage() {
        var controller = controller();
        var old = complete(controller, ActionKind.UPLOAD);
        assertFalse(controller.acceptResult(old));
        assertEquals(PREFIX + "ui.list_changed", visible(controller, PREFIX + "ui.list_changed"));
        assertEquals(PREFIX + "status.ready", visible(controller, PREFIX + "status.ready"));
    }

    @Test void synchronizationAndAuthorizationStillOwnTheOverlay() {
        var controller = controller();
        complete(controller, ActionKind.UPLOAD);
        controller.acceptSnapshot(snapshot(false, true));
        assertEquals(WAITING, visible(controller, PREFIX + "status.uploaded"));
        controller.acceptSnapshot(snapshot(true, false));
        assertEquals(PREFIX + "status.denied", visible(controller, PREFIX + "status.uploaded"));
    }
}
