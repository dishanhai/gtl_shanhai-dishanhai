package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;
import com.dishanhai.gt_shanhai.common.shop.ShopQuestLinkConfig;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 「任务 → 商店商品」手动绑定的增删（C→S）。由任务详情页「编辑 ▼」菜单里的绑定项发起，
 * 服务端校验编辑权（{@link ShopEditPermission#canEdit}，跟商店设置同一档，不要求开编辑模式——
 * 这只是导航关系，不增删商品本身），落盘后广播新表给所有在线玩家。
 */
public class ShopQuestLinkEditPacket {

    private static final int MAX_QUEST_ID_CHARS = 32;
    private static final int MAX_STABLE_ID_CHARS = 64;

    private final String questHexId;
    private final String stableId;
    /** true=绑定，false=解绑。 */
    private final boolean add;

    public ShopQuestLinkEditPacket(String questHexId, String stableId, boolean add) {
        this.questHexId = questHexId == null ? "" : questHexId.trim();
        this.stableId = stableId == null ? "" : stableId.trim();
        this.add = add;
    }

    public ShopQuestLinkEditPacket(FriendlyByteBuf buf) {
        this.questHexId = buf.readUtf(MAX_QUEST_ID_CHARS);
        this.stableId = buf.readUtf(MAX_STABLE_ID_CHARS);
        this.add = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(questHexId, MAX_QUEST_ID_CHARS);
        buf.writeUtf(stableId, MAX_STABLE_ID_CHARS);
        buf.writeBoolean(add);
    }

    public static void handle(ShopQuestLinkEditPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (!ShopEditPermission.canEdit(player)) {
                player.sendSystemMessage(Component.literal("§c[山海商店] 没有编辑权限"));
                return;
            }
            if (pkt.questHexId.isEmpty() || pkt.stableId.isEmpty()) return;
            boolean changed = pkt.add
                    ? ShopQuestLinkConfig.add(pkt.questHexId, pkt.stableId)
                    : ShopQuestLinkConfig.remove(pkt.questHexId, pkt.stableId);
            if (!changed) return;
            player.sendSystemMessage(Component.literal(
                    pkt.add ? "§a[山海商店] 已绑定商品到该任务" : "§e[山海商店] 已解除该任务的商品绑定"));
            ShopQuestLinkSyncPacket.broadcast();
        });
        context.setPacketHandled(true);
    }
}
