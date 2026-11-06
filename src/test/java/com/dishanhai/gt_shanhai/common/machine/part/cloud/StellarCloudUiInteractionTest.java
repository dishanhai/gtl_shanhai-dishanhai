package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：按服務端快照操作，展開與請求不得改動原件身份。 */
class StellarCloudUiInteractionTest {
    private static final String CONTRACT = "com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudUiContract$";
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), MEMBER = new UUID(0, 3);

    private static Class<?> type(String name) {
        return assertDoesNotThrow(() -> Class.forName(CONTRACT + name), "缺少雲庫互動契約：" + name);
    }

    private static Object construct(String name, Object... args) throws Exception {
        Constructor<?> ctor = type(name).getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object call(Object target, String name) throws Exception {
        return call(target, name, new Class<?>[0]);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(String type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type(type), name);
    }

    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        final List<Object[]> arguments = new ArrayList<>();
        final AtomicLong now = new AtomicLong();
        final Object controller;

        Fixture() throws Exception {
            Class<?> actions = type("LibraryActions");
            Object spy = Proxy.newProxyInstance(actions.getClassLoader(), new Class<?>[]{actions}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "LibraryActions spy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError(method);
                    };
                }
                calls.add(method.getName());
                arguments.add(args == null ? new Object[0] : args.clone());
                return null;
            });
            Constructor<?> ctor = type("LibraryController").getDeclaredConstructor(actions, LongSupplier.class);
            ctor.setAccessible(true);
            controller = ctor.newInstance(spy, (LongSupplier) now::get);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        Object snapshot(int page, int pages, UUID selected, boolean visible, List<UUID> ids,
                        boolean ready, boolean enabled, boolean leased, boolean requested) throws Exception {
            Object on = construct("ControlState", true, "");
            Object off = construct("ControlState", false, "gt_shanhai.cloud.disabled");
            Map controls = new EnumMap(type("ActionKind"));
            for (String action : List.of("UPLOAD", "WITHDRAW", "QUICK_UPLOAD", "GRANT", "REVOKE"))
                controls.put(constant("ActionKind", action), enabled ? on : off);
            List<Object> rows = new ArrayList<>();
            for (UUID id : ids) rows.add(construct("RowState", id, leased, requested, enabled ? on : off));
            Object selection = construct("SelectionState", selected, selected != null, visible, leased, requested);
            int count = pages == 3 ? 73 : ids.size();
            return construct("LibraryState", ready, "search", "gtceu:lathe", List.of("gtceu:lathe"), page,
                    pages, count, count, rows, selection, MEMBER, true, true, false, controls);
        }

        void snapshot(Object state) throws Exception {
            call(controller, "acceptSnapshot", new Class<?>[]{type("LibraryState")}, state);
        }
        boolean invoke(String name) throws Exception { return (boolean) call(controller, name); }
        boolean invoke(String name, int value) throws Exception {
            return (boolean) call(controller, name, new Class<?>[]{int.class}, value);
        }
        int pending() throws Exception { return ((OptionalInt) call(controller, "pendingRequestId")).orElseThrow(); }
        boolean result(int id, String action, String kind, UUID target) throws Exception {
            Object result = construct("ActionResult", id, constant("ActionKind", action),
                    constant("ResultKind", kind), target, "gt_shanhai.cloud.result");
            return (boolean) call(controller, "acceptResult", new Class<?>[]{type("ActionResult")}, result);
        }
        long count(String action) { return calls.stream().filter(action::equals).count(); }
        Object[] last() { return arguments.getLast(); }
    }

    @Test void fourExpansionCombinationsKeepTheCompleteSnapshotAndSendNoActions() throws Exception {
        Fixture f = new Fixture();
        Object snapshot = f.snapshot(1, 3, A, true, List.of(A, B), true, true, false, false);
        f.snapshot(snapshot);
        Object initial = call(f.controller, "localState");
        assertEquals(false, call(initial, "detailsExpanded"));
        assertEquals(false, call(initial, "permissionsExpanded"));
        assertEquals(0, call(initial, "scrollY"));
        String[] toggles = {"toggleDetails", "togglePermissions", "toggleDetails", "togglePermissions"};
        boolean[][] expected = {{true, false}, {true, true}, {false, true}, {false, false}};
        for (int i = 0; i < toggles.length; i++) {
            call(f.controller, toggles[i]);
            assertEquals(snapshot, call(f.controller, "state"));
            Object local = call(f.controller, "localState");
            assertEquals(expected[i][0], call(local, "detailsExpanded"));
            assertEquals(expected[i][1], call(local, "permissionsExpanded"));
        }
        assertEquals(initial, call(f.controller, "localState"));
        assertTrue(f.calls.isEmpty());
    }

    @Test void selectionCarriesVisibleUuidAndInvalidCellsNeverSend() throws Exception {
        Fixture f = new Fixture();
        f.snapshot(f.snapshot(1, 3, A, true, List.of(A, B), true, true, false, false));
        assertFalse(f.invoke("select", -1));
        assertFalse(f.invoke("select", 2));
        assertTrue(f.invoke("select", 1));
        assertEquals(List.of("select"), f.calls);
        assertArrayEquals(new Object[]{1, B}, f.last());
    }

    @Test void pageBoundariesAndEmptyRowsCannotSendIntents() throws Exception {
        Fixture f = new Fixture();
        f.snapshot(f.snapshot(0, 1, null, false, List.of(), true, true, false, false));
        assertFalse(f.invoke("previousPage"));
        assertFalse(f.invoke("nextPage"));
        assertFalse(f.invoke("select", 0));
        f.snapshot(f.snapshot(1, 3, A, true, List.of(A), true, true, false, false));
        assertTrue(f.invoke("nextPage"));
        assertEquals(List.of("nextPage"), f.calls);
        assertEquals(1, call(call(f.controller, "state"), "page"), "頁碼應等待服務端快照確認");
    }

    @Test void existingPagingClampsEmptyExactBoundaryAndDeletedLastPage() {
        assertEquals(-1, StellarCloudGridPage.index(0, 0, 0));
        assertEquals(0, StellarCloudGridPage.clamp(1, 36));
        assertEquals(35, StellarCloudGridPage.index(0, 35, 36));
        assertEquals(36, StellarCloudGridPage.index(1, 0, 37));
        assertEquals(-1, StellarCloudGridPage.index(1, 1, 37));
        assertEquals(72, StellarCloudGridPage.index(2, 0, 73));
        assertEquals(1, StellarCloudGridPage.clamp(2, 72));
    }

    @Test void filteredSelectionCannotWithdrawUntilVisibleAgain() throws Exception {
        Fixture f = new Fixture();
        f.snapshot(f.snapshot(0, 1, A, false, List.of(B), true, false, false, false));
        assertFalse(f.invoke("withdrawSelected"));
        assertEquals(A, call(call(call(f.controller, "state"), "selection"), "id"));
        f.snapshot(f.snapshot(0, 1, A, true, List.of(A), true, true, false, false));
        assertTrue(f.invoke("withdrawSelected"));
        assertEquals(A, f.last()[1]);
    }

    @Test void unreadyAndDisabledControlsRejectOrdinaryAndQuickOperations() throws Exception {
        Fixture f = new Fixture();
        f.snapshot(f.snapshot(0, 1, A, true, List.of(A), false, true, false, false));
        assertFalse(f.invoke("uploadOriginal"));
        assertFalse(f.invoke("withdrawSelected"));
        assertFalse(f.invoke("quickUpload", 0));
        assertFalse(f.invoke("quickWithdraw", 0));
        f.snapshot(f.snapshot(0, 1, A, true, List.of(A), true, false, true, true));
        assertFalse(f.invoke("uploadOriginal"));
        assertFalse(f.invoke("withdrawSelected"));
        assertFalse(f.invoke("quickUpload", 0));
        assertFalse(f.invoke("quickWithdraw", 0));
        assertTrue(f.calls.isEmpty());
    }

    @Test void quickActionsUseInventoryBoundsAndVisibleIdentity() throws Exception {
        Fixture f = new Fixture();
        f.snapshot(f.snapshot(0, 1, A, true, List.of(A, B), true, true, false, false));
        assertFalse(f.invoke("quickUpload", -1));
        assertFalse(f.invoke("quickUpload", 36));
        assertFalse(f.invoke("quickWithdraw", 2));
        assertTrue(f.invoke("quickWithdraw", 1));
        int request = f.pending();
        assertArrayEquals(new Object[]{request, 1, B}, f.last());
        assertTrue(f.result(request, "QUICK_WITHDRAW", "SUCCESS", B));
        assertTrue(f.invoke("quickUpload", 35));
        assertEquals(35, f.last()[1]);
    }

    @Test void pendingRequestRejectsMismatchedReceiptsAndTimeoutRefreshesOnce() throws Exception {
        Fixture f = new Fixture();
        f.snapshot(f.snapshot(0, 1, A, true, List.of(A, B), true, true, false, false));
        assertTrue(f.invoke("withdrawSelected"));
        int request = f.pending();
        assertFalse(f.invoke("withdrawSelected"));
        assertFalse(f.result(request + 1, "WITHDRAW", "SUCCESS", A));
        assertFalse(f.result(request, "WITHDRAW", "SUCCESS", B));
        assertFalse(f.result(request, "UPLOAD", "SUCCESS", A));
        f.now.set(5000);
        call(f.controller, "tick");
        call(f.controller, "tick");
        assertEquals(1, f.count("withdrawSelected"));
        assertEquals(1, f.count("refresh"));
        assertEquals(request, f.pending());
        assertFalse(f.invoke("uploadOriginal"));
    }

    @Test void selectingAnotherRowDoesNotRetargetAnOutstandingWithdrawal() throws Exception {
        Fixture f = new Fixture();
        f.snapshot(f.snapshot(0, 1, A, true, List.of(A, B), true, true, false, false));
        assertTrue(f.invoke("withdrawSelected"));
        int request = f.pending();
        assertTrue(f.invoke("select", 1));
        f.snapshot(f.snapshot(0, 1, B, true, List.of(A, B), true, true, false, false));
        assertFalse(f.result(request, "WITHDRAW", "SUCCESS", B));
        assertEquals(request, f.pending());
        assertTrue(f.result(request, "WITHDRAW", "SUCCESS", A));
        assertEquals(B, call(call(call(f.controller, "state"), "selection"), "id"));
        assertTrue(f.invoke("withdrawSelected"));
        int next = f.pending();
        assertNotEquals(request, next);
        assertTrue(f.result(next, "WITHDRAW", "SUCCESS", B));
    }

    @Test void requestedLeaseWaitsAndDrainedEntryCanBeWithdrawnOnlyByClick() throws Exception {
        Fixture f = new Fixture();
        f.snapshot(f.snapshot(0, 1, A, true, List.of(A), true, true, true, false));
        assertTrue(f.invoke("withdrawSelected"));
        assertTrue(f.result(f.pending(), "WITHDRAW", "WAITING", A));
        f.snapshot(f.snapshot(0, 1, A, true, List.of(A), true, false, true, true));
        assertFalse(f.invoke("withdrawSelected"));
        f.snapshot(f.snapshot(0, 1, A, true, List.of(A), true, true, false, true));
        assertEquals(1, f.count("withdrawSelected"));
        assertTrue(f.invoke("withdrawSelected"));
        assertEquals(2, f.count("withdrawSelected"));
    }
}
