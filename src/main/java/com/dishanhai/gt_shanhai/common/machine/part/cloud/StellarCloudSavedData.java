package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;

/** 山海：世界範圍的原件庫；損壞或未支援版本保持原始資料並拒絕寫入。 */
public final class StellarCloudSavedData extends SavedData {
    private static final String NAME = "gt_shanhai_stellar_cloud";
    private final Map<UUID, Library> libraries = new LinkedHashMap<>();
    private final Map<UUID, ExecutorReference> endpointTokens = new HashMap<>();
    private final ReferenceQueue<Object> staleEndpoints = new ReferenceQueue<>();
    private CompoundTag quarantined;

    public static final class Library {
        private final UUID id, owner;
        private final Set<UUID> members = new LinkedHashSet<>();
        private final StellarCloudCatalog catalog;
        long classificationTarget = Long.MIN_VALUE;
        long classificationTick = Long.MIN_VALUE;
        List<UUID> classificationIds = List.of();
        int classificationCursor;
        final Map<UUID, Set<String>> classificationUpdates = new LinkedHashMap<>();
        private Library(UUID id, UUID owner, StellarCloudCatalog catalog) {
            this.id = id; this.owner = owner; this.catalog = catalog;
        }
        public UUID id() { return id; }
        public UUID owner() { return owner; }
        public StellarCloudCatalog catalog() { return catalog; }
        public boolean authorized(UUID player) { return player != null && (owner.equals(player) || members.contains(player)); }
        public void grant(UUID player) { members.add(Objects.requireNonNull(player)); }
        public void revoke(UUID player) { members.remove(player); }
        public Set<UUID> members() { return Set.copyOf(members); }
    }

    public static StellarCloudSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(StellarCloudSavedData::load, StellarCloudSavedData::new, NAME);
    }
    public boolean healthy() { return quarantined == null; }

    /** 山海：持久 UUID 還需要當前載入實例的唯一憑證；不讓同 NBT 複本共享租約。 */
    public boolean bindExecutor(UUID executor, Object token) {
        clearStaleEndpoints();
        if (!healthy() || executor == null || !liveToken(token)) return false;
        ExecutorReference previous = endpointTokens.get(executor);
        Object active = previous == null ? null : previous.get();
        if (active != token && liveToken(active)) return false;
        if (active != token) endpointTokens.put(executor, new ExecutorReference(executor, token, staleEndpoints));
        return true;
    }

    public boolean executorActive(UUID executor, Object token) {
        clearStaleEndpoints();
        ExecutorReference reference = endpointTokens.get(executor);
        return healthy() && liveToken(token) && reference != null && reference.get() == token;
    }

    /** 卸載只撤銷該實例的執行權，不變更持久原件與租約。 */
    public void unloadExecutor(UUID executor, Object token) {
        clearStaleEndpoints();
        ExecutorReference reference = endpointTokens.get(executor);
        if (reference != null && reference.get() == token) endpointTokens.remove(executor);
    }

    private void clearStaleEndpoints() {
        ExecutorReference stale;
        while ((stale = (ExecutorReference) staleEndpoints.poll()) != null) endpointTokens.remove(stale.executor, stale);
    }

    private static boolean liveToken(Object token) {
        if (token == null) return false;
        if (token instanceof MetaMachine machine) {
            // 只查現有方塊實體的 removed 標記，絕不為驗權載入區塊。
            return machine.getHolder() != null && machine.getHolder().getSelf() != null && !machine.getHolder().getSelf().isRemoved();
        }
        return true;
    }

    private static final class ExecutorReference extends WeakReference<Object> {
        private final UUID executor;
        private ExecutorReference(UUID executor, Object token, ReferenceQueue<Object> queue) {
            super(token, queue); this.executor = executor;
        }
    }

    public Library library(UUID id) { return healthy() ? libraries.get(id) : null; }
    public Library defaultLibrary(UUID owner) {
        if (!healthy()) throw new IllegalStateException("Quarantined stellar cloud data");
        for (Library library : libraries.values()) if (library.owner.equals(owner)) return library;
        Library library = new Library(UUID.randomUUID(), Objects.requireNonNull(owner), new StellarCloudCatalog());
        libraries.put(library.id, library); setDirty(); return library;
    }

    public static StellarCloudSavedData load(CompoundTag tag) {
        StellarCloudSavedData data = new StellarCloudSavedData();
        try {
            if (tag.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported library schema");
            ListTag libraries = tag.getList("libraries", Tag.TAG_COMPOUND);
            for (int i = 0; i < libraries.size(); i++) {
                CompoundTag row = libraries.getCompound(i);
                if (!row.hasUUID("id") || !row.hasUUID("owner")) throw new IllegalArgumentException("Missing library identity");
                Library library = new Library(row.getUUID("id"), row.getUUID("owner"), StellarCloudCatalog.load(row.getCompound("catalog")));
                ListTag members = row.getList("members", Tag.TAG_COMPOUND);
                for (int m = 0; m < members.size(); m++) {
                    if (!members.getCompound(m).hasUUID("id")) throw new IllegalArgumentException("Invalid library member");
                    library.members.add(members.getCompound(m).getUUID("id"));
                }
                if (data.libraries.putIfAbsent(library.id, library) != null) throw new IllegalArgumentException("Duplicate library");
            }
        } catch (RuntimeException invalid) {
            data.libraries.clear(); data.quarantined = tag.copy();
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        if (quarantined != null) return quarantined.copy();
        tag.putInt("schema", 1); ListTag list = new ListTag();
        for (Library library : libraries.values()) {
            CompoundTag row = new CompoundTag(); row.putUUID("id", library.id); row.putUUID("owner", library.owner);
            row.put("catalog", library.catalog.save()); ListTag members = new ListTag();
            for (UUID member : library.members) { CompoundTag entry = new CompoundTag(); entry.putUUID("id", member); members.add(entry); }
            row.put("members", members); list.add(row);
        }
        tag.put("libraries", list); return tag;
    }
}
