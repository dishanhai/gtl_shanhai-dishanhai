package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BooleanSupplier;

/** 山海：原件權威目錄。所有可變 NBT 都在邊界複製；租約不因卸載逾時而釋放。 */
public final class StellarCloudCatalog {
    private static final int SCHEMA = 1;
    private final Map<UUID, Row> entries = new LinkedHashMap<>();
    private final Map<UUID, UUID> receipts = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<UUID>> typeIndex = new HashMap<>();
    private final Set<UUID> unclassified = new LinkedHashSet<>();
    private long revision;
    private long leaseRevision;

    public record Upload(UUID entry, boolean inserted) {}

    public static final class Row {
        private final UUID id;
        private final CompoundTag original, configuration;
        private final Set<String> types;
        private UUID executor;
        private boolean withdrawRequested;

        private Row(UUID id, CompoundTag original, CompoundTag configuration, Set<String> types) {
            this.id = id;
            this.original = original.copy();
            this.configuration = configuration.copy();
            this.types = Collections.unmodifiableSet(new LinkedHashSet<>(types));
        }
        public UUID id() { return id; }
        public CompoundTag original() { return original.copy(); }
        public CompoundTag configuration() { return configuration.copy(); }
        public Set<String> types() { return types; }
        public boolean leased() { return executor != null; }
        public boolean withdrawRequested() { return withdrawRequested; }
    }

    public Upload upload(UUID operation, CompoundTag original, CompoundTag configuration, Set<String> types) {
        return upload(operation, original, configuration, types, () -> true);
    }

    /** Checks duplicates before debiting a live source. A failed debit leaves the catalog unchanged. */
    @Nullable Upload upload(UUID operation, CompoundTag original, CompoundTag configuration, Set<String> types,
            BooleanSupplier debitSource) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(original);
        if (original.isEmpty()) throw new IllegalArgumentException("Empty cloud original");
        UUID receipt = receipts.get(operation);
        if (receipt != null) return new Upload(receipt, false);
        CompoundTag single = original.copy();
        single.putByte("Count", (byte) 1);
        for (Row existing : entries.values()) {
            if (existing.original.equals(single) && existing.configuration.equals(configuration)) {
                return new Upload(existing.id, false);
            }
        }
        UUID id = UUID.randomUUID();
        Row row = new Row(id, single, configuration, types);
        if (!debitSource.getAsBoolean()) return null;
        entries.put(id, row); receipts.put(operation, id); index(row); revision++;
        return new Upload(id, true);
    }

    public Row entry(UUID id) { return entries.get(id); }
    public UUID receipt(UUID operation) { return receipts.get(operation); }
    public int size() { return entries.size(); }
    public long revision() { return revision; }
    public long leaseRevision() { return leaseRevision; }
    public List<UUID> ids() { return List.copyOf(entries.keySet()); }
    public Set<String> types() { return Set.copyOf(typeIndex.keySet()); }

    public List<UUID> candidates(Set<String> types) {
        if (types.isEmpty()) return List.of();
        Set<UUID> result = new LinkedHashSet<>(unclassified);
        for (String type : types) result.addAll(typeIndex.getOrDefault(type, new LinkedHashSet<>()));
        result.removeIf(id -> entries.get(id).withdrawRequested);
        return List.copyOf(result);
    }

    /** 配方重載只更新分類；原件、來源操作與在途租約不改變。 */
    public void reclassify(UUID entry, Set<String> types) {
        Row previous = entries.get(entry);
        if (previous == null || previous.types.equals(types)) return;
        unclassified.remove(entry);
        for (String type : previous.types) {
            Set<UUID> indexed = typeIndex.get(type);
            if (indexed != null) { indexed.remove(entry); if (indexed.isEmpty()) typeIndex.remove(type); }
        }
        Row replacement = new Row(entry, previous.original, previous.configuration, types);
        replacement.executor = previous.executor;
        replacement.withdrawRequested = previous.withdrawRequested;
        entries.put(entry, replacement); index(replacement); revision++;
    }

    public boolean claim(UUID executor, UUID entry) {
        Objects.requireNonNull(executor);
        Row row = entries.get(entry);
        if (row == null || row.withdrawRequested || row.executor != null && !executor.equals(row.executor)) return false;
        if (row.executor == null) { row.executor = executor; leaseRevision++; }
        return true;
    }

    public boolean owns(UUID executor, UUID entry) {
        Row row = entries.get(entry);
        return row != null && executor != null && executor.equals(row.executor);
    }

    public void release(UUID executor, UUID entry) {
        Row row = entries.get(entry);
        if (row != null && executor != null && executor.equals(row.executor)) {
            row.executor = null; leaseRevision++;
        }
    }

    /** 先停止發布與接新單；原件與舊租約留到執行端安全排空。 */
    public boolean requestWithdrawal(UUID entry) {
        Row row = entries.get(entry);
        if (row == null) return false;
        if (!row.withdrawRequested) { row.withdrawRequested = true; revision++; }
        return true;
    }

    public CompoundTag withdraw(UUID entry) {
        Row row = entries.get(entry);
        if (row == null || row.executor != null) return null;
        entries.remove(entry);
        unclassified.remove(entry);
        for (String type : row.types) {
            Set<UUID> indexed = typeIndex.get(type);
            if (indexed != null) { indexed.remove(entry); if (indexed.isEmpty()) typeIndex.remove(type); }
        }
        revision++;
        return row.original();
    }

    private void index(Row row) {
        if (row.types.isEmpty()) unclassified.add(row.id);
        for (String type : row.types) typeIndex.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(row.id);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putInt("schema", SCHEMA); tag.putLong("revision", revision); tag.putLong("leaseRevision", leaseRevision);
        ListTag rows = new ListTag();
        for (Row row : entries.values()) {
            CompoundTag data = new CompoundTag(); data.putUUID("id", row.id);
            data.put("original", row.original()); data.put("configuration", row.configuration());
            data.putBoolean("withdrawRequested", row.withdrawRequested);
            ListTag types = new ListTag(); row.types.forEach(type -> types.add(StringTag.valueOf(type))); data.put("types", types);
            if (row.executor != null) data.putUUID("executor", row.executor);
            rows.add(data);
        }
        ListTag operations = new ListTag();
        receipts.forEach((op, id) -> { CompoundTag data = new CompoundTag(); data.putUUID("operation", op); data.putUUID("entry", id); operations.add(data); });
        tag.put("entries", rows); tag.put("receipts", operations);
        return tag;
    }

    public static StellarCloudCatalog load(CompoundTag tag) {
        if (tag.getInt("schema") != SCHEMA) throw new IllegalArgumentException("Unsupported stellar cloud schema");
        StellarCloudCatalog catalog = new StellarCloudCatalog();
        ListTag rows = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag data = rows.getCompound(i);
            if (!data.hasUUID("id") || data.getCompound("original").isEmpty()
                    || data.contains("executor") && !data.hasUUID("executor")) throw new IllegalArgumentException("Invalid cloud row");
            UUID id = data.getUUID("id");
            Set<String> types = new LinkedHashSet<>();
            ListTag savedTypes = data.getList("types", Tag.TAG_STRING);
            for (int type = 0; type < savedTypes.size(); type++) {
                String name = savedTypes.getString(type); if (!name.isBlank()) types.add(name);
            }
            Row row = new Row(id, data.getCompound("original"), data.getCompound("configuration"), types);
            row.withdrawRequested = data.getBoolean("withdrawRequested");
            if (data.hasUUID("executor")) row.executor = data.getUUID("executor");
            if (catalog.entries.putIfAbsent(id, row) != null) throw new IllegalArgumentException("Duplicate cloud identity");
            catalog.index(row);
        }
        ListTag receipts = tag.getList("receipts", Tag.TAG_COMPOUND);
        for (int i = 0; i < receipts.size(); i++) {
            CompoundTag data = receipts.getCompound(i);
            if (!data.hasUUID("operation") || !data.hasUUID("entry")) throw new IllegalArgumentException("Invalid cloud operation receipt");
            if (catalog.receipts.putIfAbsent(data.getUUID("operation"), data.getUUID("entry")) != null)
                throw new IllegalArgumentException("Duplicate cloud operation");
        }
        catalog.revision = tag.getLong("revision");
        catalog.leaseRevision = tag.getLong("leaseRevision");
        return catalog;
    }
}
