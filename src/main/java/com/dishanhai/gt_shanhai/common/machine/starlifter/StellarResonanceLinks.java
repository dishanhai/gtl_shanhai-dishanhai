package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuStarLiftingModule;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.google.common.collect.HashBiMap;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/** 一份存档索引同时保留两端关系，解绑时无需远端区块在线。 */
public final class StellarResonanceLinks extends SavedData {
    private static final String DATA_ID = "gt_shanhai_stellar_resonance_links";
    private static final String STAR = "StarLifter";
    private static final String MODULE = "GuixuModule";
    private final HashBiMap<GlobalPos, GlobalPos> pairs = HashBiMap.create();

    public enum Result { LINKED, ALREADY_LINKED, STAR_OCCUPIED, MODULE_OCCUPIED, UNAVAILABLE }

    public static StellarResonanceLinks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(StellarResonanceLinks::load,
                StellarResonanceLinks::new, DATA_ID);
    }

    public Result connect(ServerLevel level, GlobalPos star, GuixuStarLiftingModule module) {
        if (!module.isFormed() || module.isInValid() || !starReady(level.getServer(), star)) return Result.UNAVAILABLE;
        return connect(star, GlobalPos.of(level.dimension(), module.getPos()));
    }

    Result connect(GlobalPos star, GlobalPos module) {
        var existing = pairs.get(star);
        if (module.equals(existing)) return Result.ALREADY_LINKED;
        if (existing != null) return Result.STAR_OCCUPIED;
        if (pairs.containsValue(module)) return Result.MODULE_OCCUPIED;
        pairs.put(star, module);
        setDirty();
        return Result.LINKED;
    }

    public boolean unlink(GlobalPos address) {
        var removed = pairs.remove(address);
        if (removed == null) removed = pairs.inverse().remove(address);
        if (removed == null) return false;
        setDirty();
        return true;
    }

    @Nullable
    public GlobalPos starFor(GlobalPos module) {
        return pairs.inverse().get(module);
    }

    @Nullable
    GlobalPos moduleFor(GlobalPos star) {
        return pairs.get(star);
    }

    record Endpoint(GlobalPos address, GuixuVisionCoreMachine host, String state) {
        boolean resonant() { return "online".equals(state) || "working".equals(state); }
    }

    Endpoint endpoint(ServerLevel source, BlockPos pos, boolean formed) {
        var address = moduleFor(GlobalPos.of(source.dimension(), pos));
        if (address == null) return new Endpoint(null, null, "unbound");
        var remote = source.getServer().getLevel(address.dimension());
        if (remote == null) return new Endpoint(address, null, "unavailable");
        var target = address.pos();
        var chunk = remote.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4);
        if (chunk == null) return new Endpoint(address, null, "unloaded");
        if (!(MetaMachine.getMachine(chunk, target) instanceof GuixuStarLiftingModule module) || module.isInValid()) {
            return new Endpoint(address, null, "unavailable");
        }
        var host = module.getHost() instanceof GuixuVisionCoreMachine core ? core : null;
        String state = !formed || !module.isFormed() || host == null || !host.isFormed() ? "offline"
                : module.getRecipeLogic().isWorking() ? "working" : "online";
        return new Endpoint(address, host, state);
    }

    public static boolean starReady(MinecraftServer server, GlobalPos address) {
        var level = server.getLevel(address.dimension());
        if (level == null) return false;
        var pos = address.pos();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && MetaMachine.getMachine(chunk, pos) instanceof StarLifterControllerMachine star
                && star.isStructureAvailable();
    }

    static StellarResonanceLinks load(CompoundTag data) {
        var links = new StellarResonanceLinks();
        for (Tag entry : data.getList("Links", Tag.TAG_COMPOUND)) {
            var pair = (CompoundTag) entry;
            if (!pair.contains(STAR, Tag.TAG_COMPOUND) || !pair.contains(MODULE, Tag.TAG_COMPOUND)) continue;
            var star = GlobalPos.CODEC.parse(NbtOps.INSTANCE, pair.get(STAR)).result();
            var module = GlobalPos.CODEC.parse(NbtOps.INSTANCE, pair.get(MODULE)).result();
            if (star.isPresent() && module.isPresent() && !links.pairs.containsValue(module.get())) {
                links.pairs.putIfAbsent(star.get(), module.get());
            }
        }
        return links;
    }

    @Override
    public CompoundTag save(CompoundTag data) {
        var entries = new ListTag();
        pairs.forEach((star, module) -> {
            var pair = new CompoundTag();
            pair.put(STAR, GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, star).result().orElseThrow());
            pair.put(MODULE, GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, module).result().orElseThrow());
            entries.add(pair);
        });
        data.put("Links", entries);
        return data;
    }
}
