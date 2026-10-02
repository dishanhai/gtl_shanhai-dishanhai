package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopStageConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopSubmissionSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 玩家提交商品固定物品或分类阶段物品。 */
public final class ShopSubmitPacket {
    private final long revision;
    private final long entryKey;
    private final boolean stage;
    private final String stagePath;
    private final boolean aeMode;
    private final boolean backpackMode;

    public ShopSubmitPacket(long revision, long entryKey) {
        this(revision, entryKey, false, "", false, false);
    }

    public ShopSubmitPacket(long revision, long entryKey, boolean aeMode, boolean backpackMode) {
        this(revision, entryKey, false, "", aeMode, backpackMode);
    }

    public ShopSubmitPacket(String stagePath) {
        this(stagePath, false, false);
    }

    public ShopSubmitPacket(String stagePath, boolean aeMode, boolean backpackMode) {
        this(0L, -1L, true, stagePath, aeMode, backpackMode);
    }

    private ShopSubmitPacket(long revision, long entryKey, boolean stage, String stagePath,
                             boolean aeMode, boolean backpackMode) {
        this.revision = revision;
        this.entryKey = entryKey;
        this.stage = stage;
        this.stagePath = stagePath == null ? "" : stagePath;
        this.aeMode = aeMode;
        this.backpackMode = backpackMode;
    }

    public ShopSubmitPacket(FriendlyByteBuf buf) {
        this.revision = buf.readLong();
        this.entryKey = buf.readLong();
        this.stage = buf.readBoolean();
        this.stagePath = buf.readUtf();
        this.aeMode = buf.readBoolean();
        this.backpackMode = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(revision);
        buf.writeLong(entryKey);
        buf.writeBoolean(stage);
        buf.writeUtf(stagePath);
        buf.writeBoolean(aeMode);
        buf.writeBoolean(backpackMode);
    }

    public static void handle(ShopSubmitPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) apply(packet, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopSubmitPacket packet, ServerPlayer player) {
        ShopSubmissionSavedData data = ShopSubmissionSavedData.get(player.getServer());
        if (packet.stage) {
            if (packet.stagePath.isBlank()) return;
            java.util.List<ShopStageConfig.StageRequirement> requirements = ShopStageConfig.requirementsForCategory(packet.stagePath);
            ShopStageConfig.StageRequirement target = null;
            for (ShopStageConfig.StageRequirement requirement : requirements) {
                if (requirement.path().equals(packet.stagePath)) target = requirement;
            }
            if (target == null) {
                player.sendSystemMessage(Component.literal("§c[山海商店] 该阶段没有配置提交物品"));
                return;
            }
            if (!data.submit(player, "stage:" + packet.stagePath, target.items(), packet.aeMode, packet.backpackMode)) {
                player.sendSystemMessage(Component.literal("§c[山海商店] 提交物品不足，无法解锁阶段「" + packet.stagePath + "」"));
                return;
            }
            ShopSubmissionSyncPacket.sync(player);
            player.sendSystemMessage(Component.literal("§b[山海商店] §a阶段已解锁: §f" + packet.stagePath));
            return;
        }
        ShopEntry entry = ShopConfig.resolve(packet.revision, packet.entryKey);
        if (entry == null || !entry.hasSubmissionRequirement()) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品提交要求不存在或目录已更新"));
            return;
        }
        if (!data.submit(player, "entry:" + entry.getStableId(), entry.getSubmissionItems(),
                packet.aeMode, packet.backpackMode)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 提交物品不足，无法解锁该商品"));
            return;
        }
        ShopSubmissionSyncPacket.sync(player);
        player.sendSystemMessage(Component.literal("§b[山海商店] §a商品已解锁: §f" + entry.goodsDisplayName()));
    }
}
