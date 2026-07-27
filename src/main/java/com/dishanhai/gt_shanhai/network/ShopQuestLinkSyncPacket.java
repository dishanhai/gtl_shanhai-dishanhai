package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopQuestLinkConfig;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 「任务 → 商店商品」手动绑定表全量同步（S→C）。登录时推一次，编辑后广播。
 * 表本身很小（只有被显式绑定过的任务才占一行），不做增量。
 */
public final class ShopQuestLinkSyncPacket {

    private static final int MAX_QUESTS = 8192;
    private static final int MAX_LINKS_PER_QUEST = 64;
    private static final int MAX_QUEST_ID_CHARS = 32;
    private static final int MAX_STABLE_ID_CHARS = 64;

    private final Map<String, List<String>> links;

    public ShopQuestLinkSyncPacket(Map<String, List<String>> links) {
        this.links = links == null ? Map.of() : links;
    }

    public ShopQuestLinkSyncPacket(FriendlyByteBuf buf) {
        int questCount = buf.readVarInt();
        if (questCount < 0 || questCount > MAX_QUESTS) {
            throw new DecoderException("invalid quest link count: " + questCount);
        }
        Map<String, List<String>> decoded = new LinkedHashMap<>();
        for (int i = 0; i < questCount; i++) {
            String questId = buf.readUtf(MAX_QUEST_ID_CHARS);
            int linkCount = buf.readVarInt();
            if (linkCount < 0 || linkCount > MAX_LINKS_PER_QUEST) {
                throw new DecoderException("invalid link count: " + linkCount);
            }
            List<String> stableIds = new ArrayList<>(linkCount);
            for (int j = 0; j < linkCount; j++) stableIds.add(buf.readUtf(MAX_STABLE_ID_CHARS));
            decoded.put(questId, List.copyOf(stableIds));
        }
        this.links = decoded;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(links.size());
        for (Map.Entry<String, List<String>> entry : links.entrySet()) {
            buf.writeUtf(entry.getKey(), MAX_QUEST_ID_CHARS);
            List<String> stableIds = entry.getValue();
            buf.writeVarInt(stableIds.size());
            for (String stableId : stableIds) buf.writeUtf(stableId, MAX_STABLE_ID_CHARS);
        }
    }

    public Map<String, List<String>> links() {
        return links;
    }

    public static void handle(ShopQuestLinkSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopQuestLinkSyncPacket pkt) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopQuestLinks.apply(pkt.links);
    }

    public static void sendTo(ServerPlayer player) {
        if (player == null) return;
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopQuestLinkSyncPacket(ShopQuestLinkConfig.all()));
    }

    /** 启动期服务器尚未存在时安全跳过。 */
    public static void broadcast() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ShopQuestLinkSyncPacket packet = new ShopQuestLinkSyncPacket(ShopQuestLinkConfig.all());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }
}
