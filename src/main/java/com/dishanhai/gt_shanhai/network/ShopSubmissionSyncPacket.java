package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopSubmissionSavedData;
import com.dishanhai.gt_shanhai.common.shop.ShopStageConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 玩家提交解锁状态同步。 */
public final class ShopSubmissionSyncPacket {
    private final List<String> keys;
    private final Map<String, List<com.dishanhai.gt_shanhai.common.shop.ExchangeEntry.Ingredient>> stageRequirements;

    public ShopSubmissionSyncPacket(Iterable<String> keys) {
        this(keys, Map.of());
    }

    public ShopSubmissionSyncPacket(Iterable<String> keys,
                                    Map<String, List<com.dishanhai.gt_shanhai.common.shop.ExchangeEntry.Ingredient>> stageRequirements) {
        this.keys = new ArrayList<>();
        if (keys != null) for (String key : keys) if (key != null && !key.isBlank()) this.keys.add(key);
        this.stageRequirements = new LinkedHashMap<>();
        if (stageRequirements != null) {
            for (Map.Entry<String, List<com.dishanhai.gt_shanhai.common.shop.ExchangeEntry.Ingredient>> entry : stageRequirements.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                this.stageRequirements.put(entry.getKey(), List.copyOf(entry.getValue() == null ? List.of() : entry.getValue()));
            }
        }
    }

    public ShopSubmissionSyncPacket(FriendlyByteBuf buf) {
        int count = Math.min(4096, buf.readVarInt());
        this.keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) this.keys.add(buf.readUtf(256));
        int stageCount = Math.min(4096, buf.readVarInt());
        this.stageRequirements = new LinkedHashMap<>();
        for (int i = 0; i < stageCount; i++) {
            String path = buf.readUtf(256);
            int itemCount = Math.min(64, buf.readVarInt());
            List<com.dishanhai.gt_shanhai.common.shop.ExchangeEntry.Ingredient> items = new ArrayList<>(itemCount);
            for (int j = 0; j < itemCount; j++) {
                net.minecraft.resources.ResourceLocation id = buf.readResourceLocation();
                boolean fluid = buf.readBoolean();
                long amount = buf.readVarLong();
                net.minecraft.nbt.CompoundTag nbt = buf.readNbt();
                items.add(new com.dishanhai.gt_shanhai.common.shop.ExchangeEntry.Ingredient(id, fluid, amount, nbt));
            }
            if (!path.isBlank()) this.stageRequirements.put(path, List.copyOf(items));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(keys.size());
        for (String key : keys) buf.writeUtf(key, 256);
        buf.writeVarInt(stageRequirements.size());
        for (Map.Entry<String, List<com.dishanhai.gt_shanhai.common.shop.ExchangeEntry.Ingredient>> entry : stageRequirements.entrySet()) {
            buf.writeUtf(entry.getKey(), 256);
            List<com.dishanhai.gt_shanhai.common.shop.ExchangeEntry.Ingredient> items = entry.getValue();
            buf.writeVarInt(items.size());
            for (com.dishanhai.gt_shanhai.common.shop.ExchangeEntry.Ingredient item : items) {
                buf.writeResourceLocation(item.id);
                buf.writeBoolean(item.isFluid);
                buf.writeVarLong(item.count);
                buf.writeNbt(item.nbt());
            }
        }
    }

    public static void handle(ShopSubmissionSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(packet));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopSubmissionSyncPacket packet) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState.replace(packet.keys, packet.stageRequirements);
    }

    public static void sync(ServerPlayer player) {
        if (player == null || player.getServer() == null) return;
        ShopSubmissionSavedData data = ShopSubmissionSavedData.get(player.getServer());
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(data.keys(player));
        java.util.Map<String, java.util.List<com.dishanhai.gt_shanhai.common.shop.ExchangeEntry.Ingredient>> requirements = new java.util.LinkedHashMap<>();
        for (String path : ShopStageConfig.paths()) {
            keys.add("stage_req:" + path);
            requirements.put(path, ShopStageConfig.get(path));
        }
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopSubmissionSyncPacket(keys, requirements));
    }
}
