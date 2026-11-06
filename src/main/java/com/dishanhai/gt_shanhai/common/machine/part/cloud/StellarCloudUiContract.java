package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 山海：只描述畫面快照與既有動作意圖，不能代替 Session 的服務端授權。 */
public final class StellarCloudUiContract {
    public static final String WAITING = "gt_shanhai.cloud.ui.waiting";
    private static final ControlState NOT_READY = new ControlState(false, WAITING);

    public enum ActionKind { UPLOAD, WITHDRAW, QUICK_UPLOAD, QUICK_WITHDRAW, GRANT, REVOKE, SET_ENABLED, BIND, UNBIND }
    public enum ResultKind { SUCCESS, WAITING, REJECTED, RECOVERY }
    public record ControlState(boolean enabled, String reasonKey) {
        public ControlState {
            reasonKey = enabled ? "" : reasonKey == null || reasonKey.isEmpty() ? WAITING : reasonKey;
        }
    }
    public record LocalState(boolean detailsExpanded, boolean permissionsExpanded, int scrollY) {}
    public record RowState(UUID id, boolean leased, boolean withdrawalRequested, ControlState quickWithdraw) {}
    public record SelectionState(UUID id, boolean exists, boolean visibleOnPage, boolean leased, boolean withdrawalRequested) {}
    public record LibraryState(boolean synchronizedReady, String search, String type, List<String> availableTypes,
                               int page, int pages, int total, int filteredCount, List<RowState> visibleRows,
                               SelectionState selection, UUID selectedMember, boolean owner, boolean authorized,
                               boolean indexing, Map<ActionKind, ControlState> controls) {
        public LibraryState {
            availableTypes = List.copyOf(availableTypes);
            visibleRows = List.copyOf(visibleRows);
            controls = Map.copyOf(controls);
        }
    }
    public record ConfiguratorView(boolean synchronizedReady, boolean enabled, String sourceKey, String statusKey,
                                   int publishedCount, ControlState toggle) {}
    public record CoreView(boolean synchronizedReady, UUID boundPlayer, boolean networkOnline, boolean hostReady,
                           ControlState bind, ControlState unbind) {}
    public record ActionResult(int requestId, ActionKind action, ResultKind kind, UUID target, String messageKey) {}

    public interface LibraryActions {
        void changeSearch(String value);
        void selectType(String typeId);
        void previousPage();
        void nextPage();
        void select(int visibleIndex, UUID expectedId);
        void nextMember();
        void uploadOriginal(int requestId);
        void withdrawSelected(int requestId, UUID expectedId);
        void quickUpload(int requestId, int inventorySlot);
        void quickWithdraw(int requestId, int visibleIndex, UUID expectedId);
        void changeAccess(int requestId, UUID expectedMember, boolean grant);
        void refresh();
    }
    public interface ConfiguratorActions {
        void setEnabled(int requestId, boolean enabled);
        void refresh();
    }
    public interface CoreActions {
        void changeBinding(int requestId, boolean bind);
        void refresh();
    }

    public static ControlState control(String reason) {
        return new ControlState(reason != null && reason.isEmpty(), reason);
    }

    /** 超時只索取快照，保留原請求；不得因為沒有回執而重送原件操作。 */
    private static final class Pending {
        private final LongSupplier clock;
        private int sequence;
        private ActionResult intent;
        private long sentAt;
        private boolean refreshed;
        Pending(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
        OptionalInt id() { return intent == null ? OptionalInt.empty() : OptionalInt.of(intent.requestId()); }
        ControlState control() {
            return new ControlState(false, refreshed ? "gt_shanhai.cloud.ui.timeout" : "gt_shanhai.cloud.ui.pending");
        }
        int begin(ActionKind kind, UUID target) {
            if (intent != null) return -1;
            sequence = sequence == Integer.MAX_VALUE ? 1 : sequence + 1;
            intent = new ActionResult(sequence, kind, ResultKind.WAITING, target, WAITING);
            sentAt = clock.getAsLong();
            refreshed = false;
            return sequence;
        }
        boolean accept(ActionResult result) {
            if (intent == null || result == null || result.requestId() != intent.requestId()
                    || result.action() != intent.action() || !Objects.equals(result.target(), intent.target())) return false;
            intent = null;
            return true;
        }
        void tick(Runnable refresh) {
            if (intent != null && !refreshed && clock.getAsLong() - sentAt >= 5000) {
                refreshed = true;
                refresh.run();
            }
        }
    }

    public static final class LibraryController {
        private final LibraryActions actions;
        private final Pending pending;
        private LibraryState state = new LibraryState(false, "", "", List.of(), 0, 1, 0, 0, List.of(),
                new SelectionState(null, false, false, false, false), null, false, false, false, Map.of());
        private LocalState local = new LocalState(false, false, 0);
        private ActionResult result;
        public LibraryController(LibraryActions actions, LongSupplier clock) {
            this.actions = Objects.requireNonNull(actions);
            pending = new Pending(clock);
        }
        public void acceptSnapshot(LibraryState snapshot) { state = Objects.requireNonNull(snapshot); }
        public boolean acceptResult(ActionResult result) {
            if (!pending.accept(result)) return false;
            this.result = result;
            return true;
        }
        public LibraryState state() { return state; }
        public LocalState localState() { return local; }
        public OptionalInt pendingRequestId() { return pending.id(); }
        public ActionResult lastResult() { return result; }
        public ControlState control(ActionKind kind) {
            return !state.synchronizedReady() ? NOT_READY : pending.id().isPresent() ? pending.control()
                    : state.controls().getOrDefault(kind, NOT_READY);
        }
        public ControlState quickWithdrawControl(int index) {
            RowState row = row(index);
            return !state.synchronizedReady() || row == null ? NOT_READY
                    : pending.id().isPresent() ? pending.control() : row.quickWithdraw();
        }
        public String feedbackKey() {
            return pending.id().isPresent() ? pending.control().reasonKey()
                    : result == null ? "gt_shanhai.cloud.status.ready" : result.messageKey();
        }
        public void toggleDetails() { local = new LocalState(!local.detailsExpanded(), local.permissionsExpanded(), local.scrollY()); }
        public void togglePermissions() { local = new LocalState(local.detailsExpanded(), !local.permissionsExpanded(), local.scrollY()); }
        public void setScrollY(int value) { local = new LocalState(local.detailsExpanded(), local.permissionsExpanded(), Math.max(0, value)); }
        public void changeSearch(String value) { if (state.synchronizedReady()) actions.changeSearch(value); }
        public void selectType(String typeId) {
            if (state.synchronizedReady() && state.availableTypes().contains(typeId)) actions.selectType(typeId);
        }
        public boolean previousPage() {
            if (!state.synchronizedReady() || state.page() <= 0) return false;
            actions.previousPage(); return true;
        }
        public boolean nextPage() {
            if (!state.synchronizedReady() || state.page() + 1 >= state.pages()) return false;
            actions.nextPage(); return true;
        }
        public boolean select(int visibleIndex) {
            RowState row = row(visibleIndex);
            if (!state.synchronizedReady() || row == null) return false;
            actions.select(visibleIndex, row.id()); return true;
        }
        public void nextMember() { if (state.synchronizedReady() && state.owner()) actions.nextMember(); }
        private boolean enabled(ActionKind kind) {
            return control(kind).enabled();
        }
        private RowState row(int index) {
            return index < 0 || index >= state.visibleRows().size() ? null : state.visibleRows().get(index);
        }
        public boolean uploadOriginal() {
            if (!enabled(ActionKind.UPLOAD)) return false;
            actions.uploadOriginal(pending.begin(ActionKind.UPLOAD, null)); return true;
        }
        public boolean withdrawSelected() {
            SelectionState selection = state.selection();
            if (!enabled(ActionKind.WITHDRAW) || !selection.exists() || !selection.visibleOnPage() || selection.id() == null) return false;
            actions.withdrawSelected(pending.begin(ActionKind.WITHDRAW, selection.id()), selection.id()); return true;
        }
        public boolean quickUpload(int inventorySlot) {
            if (!enabled(ActionKind.QUICK_UPLOAD) || inventorySlot < 0 || inventorySlot >= 36) return false;
            actions.quickUpload(pending.begin(ActionKind.QUICK_UPLOAD, null), inventorySlot); return true;
        }
        public boolean quickWithdraw(int visibleIndex) {
            RowState row = row(visibleIndex);
            if (!state.synchronizedReady() || pending.id().isPresent() || row == null || !row.quickWithdraw().enabled()) return false;
            actions.quickWithdraw(pending.begin(ActionKind.QUICK_WITHDRAW, row.id()), visibleIndex, row.id()); return true;
        }
        public boolean changeAccess(boolean grant) {
            ActionKind kind = grant ? ActionKind.GRANT : ActionKind.REVOKE;
            if (!enabled(kind) || state.selectedMember() == null) return false;
            actions.changeAccess(pending.begin(kind, state.selectedMember()), state.selectedMember(), grant); return true;
        }
        public void tick() { pending.tick(actions::refresh); }
    }

    public static final class ConfiguratorController {
        private final ConfiguratorActions actions;
        private final Pending pending;
        private ConfiguratorView state = new ConfiguratorView(false, false, "gui.gt_shanhai.stellar_cloud.source_native", WAITING, 0, NOT_READY);
        private ActionResult result;
        public ConfiguratorController(ConfiguratorActions actions, LongSupplier clock) {
            this.actions = Objects.requireNonNull(actions); pending = new Pending(clock);
        }
        public void acceptSnapshot(ConfiguratorView snapshot) { state = Objects.requireNonNull(snapshot); }
        public ConfiguratorView state() { return state; }
        public OptionalInt pendingRequestId() { return pending.id(); }
        public ActionResult lastResult() { return result; }
        public ControlState toggleControl() {
            return !state.synchronizedReady() ? NOT_READY : pending.id().isPresent() ? pending.control() : state.toggle();
        }
        public String feedbackKey() {
            return pending.id().isPresent() ? pending.control().reasonKey()
                    : result == null ? "gui.gt_shanhai.stellar_cloud.settings_idle" : result.messageKey();
        }
        public boolean acceptResult(ActionResult result) {
            if (!pending.accept(result)) return false;
            this.result = result; return true;
        }
        public boolean setEnabled(boolean enabled) {
            if (!state.synchronizedReady() || !state.toggle().enabled() || pending.id().isPresent() || state.enabled() == enabled) return false;
            actions.setEnabled(pending.begin(ActionKind.SET_ENABLED, null), enabled); return true;
        }
        public void tick() { pending.tick(actions::refresh); }
    }

    public static final class CoreController {
        private final CoreActions actions;
        private final Pending pending;
        private CoreView state = new CoreView(false, null, false, false, NOT_READY, NOT_READY);
        private ActionResult result;
        public CoreController(CoreActions actions, LongSupplier clock) {
            this.actions = Objects.requireNonNull(actions); pending = new Pending(clock);
        }
        public void acceptSnapshot(CoreView snapshot) { state = Objects.requireNonNull(snapshot); }
        public CoreView state() { return state; }
        public OptionalInt pendingRequestId() { return pending.id(); }
        public ActionResult lastResult() { return result; }
        public ControlState bindControl() {
            return !state.synchronizedReady() ? NOT_READY : pending.id().isPresent() ? pending.control() : state.bind();
        }
        public ControlState unbindControl() {
            return !state.synchronizedReady() ? NOT_READY : pending.id().isPresent() ? pending.control() : state.unbind();
        }
        public String feedbackKey() {
            return pending.id().isPresent() ? pending.control().reasonKey()
                    : result == null ? "gt_shanhai.cloud.ae_core.status.idle" : result.messageKey();
        }
        public boolean acceptResult(ActionResult result) {
            if (!pending.accept(result)) return false;
            this.result = result; return true;
        }
        public boolean changeBinding(boolean bind) {
            if (!state.synchronizedReady() || pending.id().isPresent() || !(bind ? state.bind() : state.unbind()).enabled()) return false;
            actions.changeBinding(pending.begin(bind ? ActionKind.BIND : ActionKind.UNBIND, null), bind); return true;
        }
        public boolean bind() { return changeBinding(true); }
        public boolean unbind() { return changeBinding(false); }
        public void tick() { pending.tick(actions::refresh); }
    }

    private StellarCloudUiContract() {}
}
