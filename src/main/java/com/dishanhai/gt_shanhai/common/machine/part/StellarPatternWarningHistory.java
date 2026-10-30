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
    private static final int MAX_TERMINAL_HISTORY = 512;
    public record MachineKey(ResourceLocation dimension, BlockPos pos) {
        public MachineKey { pos = pos.immutable(); }
    }
    private static final class Log {
        private long reportId = ShanhaiTerminalReport.nextId();
        private final LinkedHashMap<String, StellarPatternStuckReport.Entry> entries = new LinkedHashMap<>();
        private final Set<Integer> requesters = new HashSet<>();
        private int archived;
    }
    private final Map<MachineKey, Log> machines = new LinkedHashMap<>();
    private final Map<String, Set<MachineKey>> byOrder = new HashMap<>();

    public static StellarPatternWarningHistory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(StellarPatternWarningHistory::load,
                StellarPatternWarningHistory::new, "gt_shanhai_stellar_warning_history");
    }

    public void record(ResourceLocation dimension, BlockPos pos, StellarPatternStuckReport.Entry entry, Integer requester) {
        MachineKey key = new MachineKey(dimension, pos);
        Log log = machines.computeIfAbsent(key, ignored -> new Log());
        String incident = entry.order().id() + ":slot:" + entry.slot();
        var previous = log.entries.get(incident);
        if (previous != null && previous.order().state().terminal()) entry = entry.withOrder(previous.order());
        log.entries.put(incident, entry);
        if (requester != null) log.requesters.add(requester);
        index(key, entry);
        trim(key, log);
        setDirty();
    }

    private void index(MachineKey key, StellarPatternStuckReport.Entry entry) {
        if (!entry.order().id().isBlank()) byOrder.computeIfAbsent(entry.order().id(), ignored -> new HashSet<>()).add(key);
    }

    public Set<MachineKey> updateOrder(String id, StellarPatternOrderInfo.State state, long remaining) {
        if (!state.terminal()) return Set.of();
        Set<MachineKey> changed = new HashSet<>();
        for (MachineKey key : Set.copyOf(byOrder.getOrDefault(id, Set.of()))) {
            Log log = machines.get(key);
            if (log == null) continue;
            log.entries.replaceAll((incident, entry) -> {
                if (!entry.order().id().equals(id) || entry.order().state().terminal()) return entry;
                var order = entry.order().withOutcome(state, remaining);
                if (order.equals(entry.order())) return entry;
                changed.add(key);
                return entry.withOrder(order);
            });
            trim(key, log);
        }
        if (!changed.isEmpty()) setDirty();
        return changed;
    }

    public static void observeTerminal(MinecraftServer server, appeng.api.networking.crafting.ICraftingLink link, long remaining) {
        if (server == null || link == null) return;
        try {
            var state = StellarPatternOrderInfo.stateOf(link);
            if (!state.terminal()) return;
            var history = get(server);
            for (MachineKey key : history.updateOrder(link.getCraftingID().toString(), state, remaining)) {
                StellarPatternStuckNotifier.refreshHistory(server, key);
            }
        } catch (RuntimeException error) {
            // 告警紀錄失敗不能中斷 AE 原本的完成／取消與物品退回流程。
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.error("[StellarHistory] Unable to record AE order outcome", error);
        }
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
        var report = StellarPatternStuckReport.build(log.reportId, dimension, pos, log.entries.values());
        if (log.archived == 0) return report;
        var summary = net.minecraft.network.chat.Component.empty().append(report.summary()).append("\n")
                .append(net.minecraft.network.chat.Component.translatable("gui.gt_shanhai.ultimate_terminal.report.history_archived", log.archived));
        return new ShanhaiTerminalReport(report.id(), report.type(), report.target(), report.counts(), summary,
                report.categories(), report.names(), report.icons(), report.details(), report.lines(), report.omitted());
    }

    private void trim(MachineKey key, Log log) {
        long terminal = log.entries.values().stream().filter(entry -> entry.order().state().terminal()).count();
        if (terminal <= MAX_TERMINAL_HISTORY) return;
        var iterator = log.entries.values().iterator();
        while (iterator.hasNext() && terminal > MAX_TERMINAL_HISTORY) {
            var entry = iterator.next();
            if (!entry.order().state().terminal()) continue;
            iterator.remove(); terminal--; log.archived++;
            String id = entry.order().id();
            if (log.entries.values().stream().noneMatch(other -> other.order().id().equals(id))) {
                Set<MachineKey> locations = byOrder.get(id);
                if (locations != null) { locations.remove(key); if (locations.isEmpty()) byOrder.remove(id); }
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        var list = new ListTag();
        for (var item : machines.entrySet()) {
            var saved = new CompoundTag();
            saved.putString("dimension", item.getKey().dimension().toString()); saved.putLong("pos", item.getKey().pos().asLong());
            Log log = item.getValue(); saved.putLong("reportId", log.reportId); saved.putInt("archived", log.archived);
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
            Log log = new Log(); log.reportId = saved.getLong("reportId"); log.archived = saved.getInt("archived");
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
