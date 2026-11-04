package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** 山海：跨槽位紅框生命週期保存的告警歷史，以 AE 訂單 ID 接收完成／取消結果。 */
public final class StellarPatternWarningHistory extends SavedData {
    public record MachineKey(ResourceLocation dimension, BlockPos pos) {
        public MachineKey { pos = pos.immutable(); }
    }
    private static final class Log {
        private long reportId = ShanhaiTerminalReport.nextId();
        private final LinkedHashMap<String, StellarPatternStuckReport.Entry> entries = new LinkedHashMap<>();
        private final Set<Integer> requesters = new HashSet<>();
    }
    private final Map<MachineKey, Log> machines = new LinkedHashMap<>();
    private final Map<String, Set<MachineKey>> byOrder = new HashMap<>();
    /** 告警還沒寫入時先到的完成／取消。寫入告警時套上，避免只靠當時的連結旗標。 */
    private final Map<String, Outcome> early = new HashMap<>();

    private record Outcome(StellarPatternOrderInfo.State state, long remaining) {}

    public static StellarPatternWarningHistory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(StellarPatternWarningHistory::load,
                StellarPatternWarningHistory::new, "gt_shanhai_stellar_warning_history");
    }

    public void record(ResourceLocation dimension, BlockPos pos, StellarPatternStuckReport.Entry entry, Integer requester) {
        MachineKey key = new MachineKey(dimension, pos);
        Log log = machines.computeIfAbsent(key, ignored -> new Log());
        String incident = incidentOf(entry);
        var previous = log.entries.get(incident);
        Outcome pending = entry.order().id().isBlank() ? null : early.remove(entry.order().id());
        if (pending != null && !entry.order().state().terminal()) {
            long remain = pending.remaining < 0L ? entry.order().remaining() : pending.remaining;
            entry = entry.withOrder(entry.order().withOutcome(pending.state, remain));
        }
        if (previous != null && previous.order().state().terminal()) entry = entry.withOrder(previous.order());
        log.entries.put(incident, entry);
        if (requester != null) log.requesters.add(requester);
        index(key, entry);
        setDirty();
    }

    private void index(MachineKey key, StellarPatternStuckReport.Entry entry) {
        if (!entry.order().id().isBlank() && !entry.order().state().terminal())
            byOrder.computeIfAbsent(entry.order().id(), ignored -> new HashSet<>()).add(key);
    }

    public Set<MachineKey> updateOrder(String id, StellarPatternOrderInfo.State state, long remaining) {
        if (id == null || id.isBlank() || state == null || !state.terminal()) return Set.of();
        Set<MachineKey> indexed = byOrder.get(id);
        if (indexed == null || indexed.isEmpty()) {
            early.put(id, new Outcome(state, remaining));
            return Set.of();
        }
        Set<MachineKey> changed = new HashSet<>();
        for (MachineKey key : Set.copyOf(indexed)) {
            Log log = machines.get(key);
            if (log == null) continue;
            log.entries.replaceAll((incident, entry) -> {
                if (!entry.order().id().equals(id) || entry.order().state().terminal()) return entry;
                var order = entry.order().withOutcome(state, remaining < 0L ? entry.order().remaining() : remaining);
                if (order.equals(entry.order())) return entry;
                changed.add(key);
                return entry.withOrder(order);
            });
        }
        byOrder.remove(id);
        early.remove(id);
        if (!changed.isEmpty()) setDirty();
        return changed;
    }

    public static void observeTerminal(MinecraftServer server, appeng.api.networking.crafting.ICraftingLink link, long remaining) {
        observeTerminal(server, link, null, remaining);
    }

    /** {@code signal} 補上連結旗標還沒寫上的完成或取消。已寫上的取消優先於完成。 */
    public static void observeTerminal(MinecraftServer server, appeng.api.networking.crafting.ICraftingLink link,
            StellarPatternOrderInfo.State signal, long remaining) {
        if (server == null || link == null) return;
        if (!server.isSameThread()) {
            long amount = remaining;
            server.execute(() -> observeTerminal(server, link, signal, amount));
            return;
        }
        try {
            var observed = StellarPatternOrderInfo.stateOf(link);
            var state = observed == StellarPatternOrderInfo.State.CANCELLED ? observed
                    : observed.terminal() ? observed : signal;
            if (state == null || !state.terminal()) return;
            var history = get(server);
            String id = link.getCraftingID() == null ? "" : link.getCraftingID().toString();
            for (MachineKey key : history.updateOrder(id, state, remaining)) {
                StellarPatternStuckNotifier.refreshHistory(server, key);
            }
        } catch (RuntimeException error) {
            // 告警紀錄失敗不能中斷 AE 原本的完成／取消與物品退回流程。
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.error("[StellarHistory] Unable to record AE order outcome", error);
        }
    }

    public static String incidentOf(StellarPatternStuckReport.Entry entry) {
        return entry.order().id() + ":slot:" + entry.slot();
    }

    /** 從歷史詳情移除一條告警。已移除或不存在時回傳 -1。 */
    public int dismiss(ResourceLocation dimension, BlockPos pos, String incident) {
        if (dimension == null || pos == null || incident == null || incident.isBlank() || incident.length() > 160) return -1;
        MachineKey key = new MachineKey(dimension, pos);
        Log log = machines.get(key);
        if (log == null) return -1;
        StellarPatternStuckReport.Entry removed = log.entries.remove(incident);
        if (removed == null) return -1;
        byOrder.entrySet().removeIf(indexed -> {
            indexed.getValue().remove(key);
            return indexed.getValue().isEmpty();
        });
        for (var entry : log.entries.values()) index(key, entry);
        setDirty();
        return removed.slot();
    }

    public List<StellarPatternStuckReport.Entry> entries(ResourceLocation dimension, BlockPos pos) {
        Log log = machines.get(new MachineKey(dimension, pos));
        return log == null ? List.of() : List.copyOf(log.entries.values());
    }

    public Set<Integer> requesters(ResourceLocation dimension, BlockPos pos) {
        Log log = machines.get(new MachineKey(dimension, pos));
        return log == null ? Set.of() : Set.copyOf(log.requesters);
    }

    public ShanhaiTerminalReport build(ResourceLocation dimension, BlockPos pos) {
        Log log = machines.computeIfAbsent(new MachineKey(dimension, pos), ignored -> { setDirty(); return new Log(); });
        return StellarPatternStuckReport.build(log.reportId, dimension, pos, log.entries.values());
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        var list = new ListTag();
        for (var item : machines.entrySet()) {
            var saved = new CompoundTag();
            saved.putString("dimension", item.getKey().dimension().toString()); saved.putLong("pos", item.getKey().pos().asLong());
            Log log = item.getValue(); saved.putLong("reportId", log.reportId);
            saved.putIntArray("requesters", log.requesters.stream().mapToInt(Integer::intValue).toArray());
            var entries = new ListTag();
            for (var entry : log.entries.values()) entries.add(entry.save());
            saved.put("entries", entries); list.add(saved);
        }
        tag.put("machines", list); return tag;
    }

    public static StellarPatternWarningHistory load(CompoundTag tag) {
        var history = new StellarPatternWarningHistory();
        var machines = tag.getList("machines", Tag.TAG_COMPOUND);
        for (int i = 0; i < machines.size(); i++) {
            var saved = machines.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(saved.getString("dimension"));
            if (dimension == null) continue;
            MachineKey key = new MachineKey(dimension, BlockPos.of(saved.getLong("pos")));
            Log log = new Log(); log.reportId = saved.getLong("reportId");
            for (int requester : saved.getIntArray("requesters")) log.requesters.add(requester);
            var entries = saved.getList("entries", Tag.TAG_COMPOUND);
            for (int j = 0; j < entries.size(); j++) {
                var entry = StellarPatternStuckReport.Entry.load(entries.getCompound(j));
                log.entries.put(entry.order().id() + ":slot:" + entry.slot(), entry); history.index(key, entry);
            }
            history.machines.put(key, log);
        }
        return history;
    }
}
