package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudUiContract.*;

/** 山海：LDLib 畫面內同步橋。服務端發快照與回執，客戶端只送意圖及來源快照。 */
public final class StellarCloudUiSync {
    private abstract static class Channel<S> extends Widget {
        private static final int SNAPSHOT = 70, RESULT = 71, REFRESH = 72;
        final StellarCloudUiRequests requests = new StellarCloudUiRequests();
        private final BiConsumer<FriendlyByteBuf, S> writer;
        private final Function<FriendlyByteBuf, S> reader;
        private S sent;
        private ActionResult receipt;

        Channel(BiConsumer<FriendlyByteBuf, S> writer, Function<FriendlyByteBuf, S> reader) {
            super(0, 0, 0, 0); this.writer = writer; this.reader = reader;
        }
        abstract S snapshot();
        abstract void accept(S state);
        abstract void accept(ActionResult result);
        abstract void tick();
        final Player player() { return getGui() == null ? null : getGui().entityPlayer; }
        @Override public void writeInitialData(FriendlyByteBuf buffer) {
            super.writeInitialData(buffer); sent = snapshot(); writer.accept(buffer, sent);
        }
        @Override public void readInitialData(FriendlyByteBuf buffer) {
            super.readInitialData(buffer); accept(reader.apply(buffer));
        }
        private void sendSnapshot() {
            sent = snapshot(); writeUpdateInfo(SNAPSHOT, buffer -> writer.accept(buffer, sent));
        }
        @Override public void detectAndSendChanges() {
            super.detectAndSendChanges();
            S current = snapshot();
            if (!Objects.equals(current, sent)) sendSnapshot();
        }
        @Override public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == SNAPSHOT) accept(reader.apply(buffer));
            else if (id == RESULT) accept(readResult(buffer));
            else super.readUpdateInfo(id, buffer);
        }
        @Override public void updateScreen() { super.updateScreen(); tick(); }
        public final void refresh() { writeClientAction(REFRESH, buffer -> {}); }
        @Override public void handleClientAction(int id, FriendlyByteBuf buffer) {
            if (id == REFRESH) {
                sendSnapshot();
                if (receipt != null) writeUpdateInfo(RESULT, out -> writeResult(out, receipt));
            } else super.handleClientAction(id, buffer);
        }
        final void execute(int id, ActionKind kind, UUID target, Supplier<String> operation) {
            receipt = requests.execute(id, kind, target, operation);
            sendSnapshot();
            writeUpdateInfo(RESULT, buffer -> writeResult(buffer, receipt));
        }
    }

    public static final class Library extends Channel<LibraryState> implements LibraryActions {
        private final StellarCloudLibraryMachine.Session session;
        private final Player actor;
        private final ItemStackTransfer inbox;
        public final LibraryController controller;
        Library(StellarCloudLibraryMachine.Session session, Player actor, ItemStackTransfer inbox) {
            super(StellarCloudUiSync::writeLibrary, StellarCloudUiSync::readLibrary);
            this.session = session; this.actor = actor; this.inbox = inbox;
            controller = new LibraryController(this, () -> System.nanoTime() / 1_000_000);
        }
        @Override LibraryState snapshot() { return session.uiSnapshot(); }
        @Override void accept(LibraryState state) { controller.acceptSnapshot(state); }
        @Override void accept(ActionResult result) { controller.acceptResult(result); }
        @Override void tick() { controller.tick(); }
        @Override public void changeSearch(String value) { writeClientAction(1, buffer -> buffer.writeUtf(value, 80)); }
        @Override public void selectType(String value) { writeClientAction(2, buffer -> buffer.writeUtf(value)); }
        @Override public void previousPage() { writeClientAction(3, buffer -> {}); }
        @Override public void nextPage() { writeClientAction(4, buffer -> {}); }
        @Override public void select(int index, UUID expected) {
            writeClientAction(5, buffer -> { buffer.writeVarInt(index); buffer.writeUUID(expected); });
        }
        @Override public void nextMember() { writeClientAction(6, buffer -> {}); }
        private void request(int id, ActionKind kind, UUID target, int slot, ItemStack source) {
            ItemStack expected = source.copy();
            writeClientAction(7, buffer -> {
                buffer.writeVarInt(id); buffer.writeEnum(kind); writeUuid(buffer, target); buffer.writeVarInt(slot);
                buffer.writeNbt(expected.save(new CompoundTag()));
            });
        }
        @Override public void uploadOriginal(int id) { request(id, ActionKind.UPLOAD, null, -1, inbox.getStackInSlot(0)); }
        @Override public void withdrawSelected(int id, UUID target) { request(id, ActionKind.WITHDRAW, target, -1, ItemStack.EMPTY); }
        @Override public void quickUpload(int id, int slot) {
            if (slot >= 0 && slot < 36) request(id, ActionKind.QUICK_UPLOAD, null, slot, actor.getInventory().getItem(slot));
        }
        @Override public void quickWithdraw(int id, int index, UUID target) { request(id, ActionKind.QUICK_WITHDRAW, target, index, ItemStack.EMPTY); }
        @Override public void changeAccess(int id, UUID target, boolean grant) {
            request(id, grant ? ActionKind.GRANT : ActionKind.REVOKE, target, -1, ItemStack.EMPTY);
        }
        @Override public void handleClientAction(int id, FriendlyByteBuf buffer) {
            switch (id) {
                case 1 -> session.changeSearch(buffer.readUtf(80));
                case 2 -> session.selectType(buffer.readUtf());
                case 3 -> session.previousPage();
                case 4 -> session.nextPage();
                case 5 -> session.select(buffer.readVarInt(), buffer.readUUID());
                case 6 -> session.nextMember();
                case 7 -> {
                    int request = buffer.readVarInt(); ActionKind kind = buffer.readEnum(ActionKind.class);
                    UUID target = readUuid(buffer); int slot = buffer.readVarInt();
                    CompoundTag tag = buffer.readNbt(); ItemStack expected = tag == null ? ItemStack.EMPTY : ItemStack.of(tag);
                    execute(request, kind, target, () -> session.uiAction(kind, target, slot, expected));
                }
                default -> super.handleClientAction(id, buffer);
            }
        }
    }

    public static final class Configurator extends Channel<ConfiguratorView> implements ConfiguratorActions {
        private final Function<Player, ConfiguratorView> view;
        private final BiFunction<Player, Boolean, String> action;
        public final ConfiguratorController controller;
        public Configurator(Function<Player, ConfiguratorView> view, BiFunction<Player, Boolean, String> action) {
            super(StellarCloudUiSync::writeConfigurator, StellarCloudUiSync::readConfigurator);
            this.view = view; this.action = action;
            controller = new ConfiguratorController(this, () -> System.nanoTime() / 1_000_000);
        }
        @Override ConfiguratorView snapshot() { return view.apply(player()); }
        @Override void accept(ConfiguratorView state) { controller.acceptSnapshot(state); }
        @Override void accept(ActionResult result) { controller.acceptResult(result); }
        @Override void tick() { controller.tick(); }
        @Override public void setEnabled(int id, boolean enabled) {
            writeClientAction(1, buffer -> { buffer.writeVarInt(id); buffer.writeBoolean(enabled); });
        }
        @Override public void handleClientAction(int id, FriendlyByteBuf buffer) {
            if (id == 1) {
                int request = buffer.readVarInt(); boolean enabled = buffer.readBoolean();
                execute(request, ActionKind.SET_ENABLED, null, () -> {
                    ControlState control = snapshot().toggle();
                    return control.enabled() ? action.apply(player(), enabled) : control.reasonKey();
                });
            } else super.handleClientAction(id, buffer);
        }
    }

    public static final class Core extends Channel<CoreView> implements CoreActions {
        private final Function<Player, CoreView> view;
        private final BiFunction<Player, Boolean, String> action;
        public final CoreController controller;
        public Core(Function<Player, CoreView> view, BiFunction<Player, Boolean, String> action) {
            super(StellarCloudUiSync::writeCore, StellarCloudUiSync::readCore);
            this.view = view; this.action = action;
            controller = new CoreController(this, () -> System.nanoTime() / 1_000_000);
        }
        @Override CoreView snapshot() { return view.apply(player()); }
        @Override void accept(CoreView state) { controller.acceptSnapshot(state); }
        @Override void accept(ActionResult result) { controller.acceptResult(result); }
        @Override void tick() { controller.tick(); }
        @Override public void changeBinding(int id, boolean bind) {
            writeClientAction(1, buffer -> { buffer.writeVarInt(id); buffer.writeBoolean(bind); });
        }
        @Override public void handleClientAction(int id, FriendlyByteBuf buffer) {
            if (id == 1) {
                int request = buffer.readVarInt(); boolean bind = buffer.readBoolean();
                execute(request, bind ? ActionKind.BIND : ActionKind.UNBIND, null, () -> {
                    CoreView state = snapshot(); ControlState control = bind ? state.bind() : state.unbind();
                    return control.enabled() ? action.apply(player(), bind) : control.reasonKey();
                });
            } else super.handleClientAction(id, buffer);
        }
    }

    static void writeUuid(FriendlyByteBuf buffer, UUID id) { buffer.writeBoolean(id != null); if (id != null) buffer.writeUUID(id); }
    static UUID readUuid(FriendlyByteBuf buffer) { return buffer.readBoolean() ? buffer.readUUID() : null; }
    private static void writeControl(FriendlyByteBuf buffer, ControlState state) { buffer.writeBoolean(state.enabled()); buffer.writeUtf(state.reasonKey()); }
    private static ControlState readControl(FriendlyByteBuf buffer) { return new ControlState(buffer.readBoolean(), buffer.readUtf()); }
    static void writeResult(FriendlyByteBuf buffer, ActionResult result) {
        buffer.writeVarInt(result.requestId()); buffer.writeEnum(result.action()); buffer.writeEnum(result.kind());
        writeUuid(buffer, result.target()); buffer.writeUtf(result.messageKey());
    }
    static ActionResult readResult(FriendlyByteBuf buffer) {
        return new ActionResult(buffer.readVarInt(), buffer.readEnum(ActionKind.class), buffer.readEnum(ResultKind.class), readUuid(buffer), buffer.readUtf());
    }
    static void writeLibrary(FriendlyByteBuf buffer, LibraryState state) {
        buffer.writeBoolean(state.synchronizedReady()); buffer.writeUtf(state.search()); buffer.writeUtf(state.type());
        buffer.writeCollection(state.availableTypes(), FriendlyByteBuf::writeUtf);
        buffer.writeVarInt(state.page()); buffer.writeVarInt(state.pages()); buffer.writeVarInt(state.total()); buffer.writeVarInt(state.filteredCount());
        buffer.writeVarInt(state.visibleRows().size());
        for (RowState row : state.visibleRows()) {
            buffer.writeUUID(row.id()); buffer.writeBoolean(row.leased()); buffer.writeBoolean(row.withdrawalRequested()); writeControl(buffer, row.quickWithdraw());
        }
        SelectionState selected = state.selection(); writeUuid(buffer, selected.id());
        buffer.writeBoolean(selected.exists()); buffer.writeBoolean(selected.visibleOnPage()); buffer.writeBoolean(selected.leased()); buffer.writeBoolean(selected.withdrawalRequested());
        writeUuid(buffer, state.selectedMember()); buffer.writeBoolean(state.owner()); buffer.writeBoolean(state.authorized()); buffer.writeBoolean(state.indexing());
        buffer.writeVarInt(state.controls().size());
        state.controls().forEach((kind, control) -> { buffer.writeEnum(kind); writeControl(buffer, control); });
    }
    static LibraryState readLibrary(FriendlyByteBuf buffer) {
        boolean ready = buffer.readBoolean(); String search = buffer.readUtf(), type = buffer.readUtf();
        List<String> types = buffer.readList(FriendlyByteBuf::readUtf);
        int page = buffer.readVarInt(), pages = buffer.readVarInt(), total = buffer.readVarInt(), filtered = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > 36) throw new IllegalArgumentException("Invalid cloud UI row count");
        List<RowState> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) rows.add(new RowState(buffer.readUUID(), buffer.readBoolean(), buffer.readBoolean(), readControl(buffer)));
        SelectionState selection = new SelectionState(readUuid(buffer), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
        UUID member = readUuid(buffer); boolean owner = buffer.readBoolean(), authorized = buffer.readBoolean(), indexing = buffer.readBoolean();
        int controlsCount = buffer.readVarInt();
        if (controlsCount < 0 || controlsCount > ActionKind.values().length) throw new IllegalArgumentException("Invalid cloud UI control count");
        var controls = new EnumMap<ActionKind, ControlState>(ActionKind.class);
        for (int i = 0; i < controlsCount; i++) controls.put(buffer.readEnum(ActionKind.class), readControl(buffer));
        return new LibraryState(ready, search, type, types, page, pages, total, filtered, rows, selection, member, owner, authorized, indexing, controls);
    }
    static void writeConfigurator(FriendlyByteBuf buffer, ConfiguratorView state) {
        buffer.writeBoolean(state.synchronizedReady()); buffer.writeBoolean(state.enabled()); buffer.writeUtf(state.sourceKey()); buffer.writeUtf(state.statusKey());
        buffer.writeVarInt(state.publishedCount()); writeControl(buffer, state.toggle());
    }
    static ConfiguratorView readConfigurator(FriendlyByteBuf buffer) {
        return new ConfiguratorView(buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(), buffer.readUtf(), buffer.readVarInt(), readControl(buffer));
    }
    static void writeCore(FriendlyByteBuf buffer, CoreView state) {
        buffer.writeBoolean(state.synchronizedReady()); writeUuid(buffer, state.boundPlayer()); buffer.writeBoolean(state.networkOnline()); buffer.writeBoolean(state.hostReady());
        writeControl(buffer, state.bind()); writeControl(buffer, state.unbind());
    }
    static CoreView readCore(FriendlyByteBuf buffer) {
        return new CoreView(buffer.readBoolean(), readUuid(buffer), buffer.readBoolean(), buffer.readBoolean(), readControl(buffer), readControl(buffer));
    }
    private StellarCloudUiSync() {}
}
