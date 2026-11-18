package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/** Persistent delivery receipts for overflow recovery cells awaiting an available chunk. */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StellarRecoverySavedData extends SavedData {
    private static final String NAME = "gt_shanhai_stellar_recovery";
    private final StellarRecoveryQueue<CompoundTag> pending = new StellarRecoveryQueue<>();

    public static StellarRecoverySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                StellarRecoverySavedData::load, StellarRecoverySavedData::new, NAME);
    }

    public static StellarRecoverySavedData load(CompoundTag tag) {
        var data = new StellarRecoverySavedData();
        ListTag entries = tag.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (entry.hasUUID("id")) data.pending.put(entry.getUUID("id"), entry.copy());
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        pending.snapshot().forEach((id, receipt) -> {
            CompoundTag entry = receipt.copy();
            entry.putUUID("id", id);
            entries.add(entry);
        });
        tag.put("pending", entries);
        return tag;
    }

    public void enqueue(ServerLevel level, BlockPos pos, ItemStack item) {
        UUID receiptId = UUID.randomUUID();
        CompoundTag receipt = new CompoundTag();
        receipt.putString("dimension", level.dimension().location().toString());
        receipt.putLong("position", pos.asLong());
        receipt.put("item", item.save(new CompoundTag()));
        pending.put(receiptId, receipt);
        setDirty();
    }

    private boolean deliver(MinecraftServer server, UUID id, CompoundTag receipt) {
        ResourceLocation dimension = ResourceLocation.tryParse(receipt.getString("dimension"));
        if (dimension == null) return false;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        BlockPos pos = BlockPos.of(receipt.getLong("position"));
        if (level == null || !level.hasChunkAt(pos)) return false;
        if (level.getEntity(id) != null) return true;
        ItemStack item = ItemStack.of(receipt.getCompound("item"));
        if (item.isEmpty()) return false;
        ItemEntity entity = new ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, item);
        entity.setUUID(id);
        entity.setUnlimitedLifetime();
        return level.addFreshEntity(entity);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 0) return;
        if (!StellarRemovalPolicy.canDeliver(
                com.dishanhai.gt_shanhai.common.time.ShanhaiWorldClock.SERVER.getMode()
                        == com.dishanhai.gt_shanhai.common.time.ShanhaiClockMode.LIVE,
                com.dishanhai.gt_shanhai.common.time.replay.ReplaySaveGuard.block())) return;
        StellarRecoverySavedData data = get(event.getServer());
        if (data.pending.retry(16, (id, receipt) -> data.deliver(event.getServer(), id, receipt)) > 0) data.setDirty();
    }
}
