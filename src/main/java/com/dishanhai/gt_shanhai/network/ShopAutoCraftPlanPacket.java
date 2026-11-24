package com.dishanhai.gt_shanhai.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 自动合成预览和预检结果。预览保留客户端本地化所需的文本组件。
 */
public class ShopAutoCraftPlanPacket {

    private static final int MAX_LINES = 256;
    // 三栏的 UTF-8 总大小须低于 Forge 客户端自定义载荷的 1 MiB 上限。
    private static final int MAX_LINE_JSON = 384;

    private final UUID planId;
    private final List<Component> outputLines;
    private final List<Component> useLines;
    private final List<Component> noteLines;
    private final int requestId;
    private final int planned;
    private final int total;
    private final boolean openConfirmation;

    public ShopAutoCraftPlanPacket(UUID planId, List<Component> outputLines, List<Component> useLines,
                                   List<Component> noteLines, int requestId, int total) {
        this.planId = planId;
        this.outputLines = List.copyOf(outputLines);
        this.useLines = List.copyOf(useLines);
        this.noteLines = List.copyOf(noteLines);
        this.requestId = requestId;
        this.planned = outputLines.size();
        this.total = total;
        this.openConfirmation = true;
    }

    /** 山海：預檢失敗或已無缺口時只回饋按鍵，不開空白確認窗。 */
    public ShopAutoCraftPlanPacket(int requestId, boolean success) {
        this.planId = new UUID(0L, 0L);
        this.outputLines = List.of();
        this.useLines = List.of();
        this.noteLines = List.of();
        this.requestId = requestId;
        this.planned = success ? 1 : 0;
        this.total = 1;
        this.openConfirmation = false;
    }

    public ShopAutoCraftPlanPacket(FriendlyByteBuf buf) {
        this.planId = buf.readUUID();
        this.outputLines = readLines(buf);
        this.useLines = readLines(buf);
        this.noteLines = readLines(buf);
        this.requestId = buf.readVarInt();
        this.planned = buf.readVarInt();
        this.total = buf.readVarInt();
        this.openConfirmation = buf.readBoolean();
    }

    private static List<Component> readLines(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_LINES) throw new DecoderException("Invalid auto-craft plan line count: " + n);
        List<Component> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Component line = Component.Serializer.fromJson(buf.readUtf(MAX_LINE_JSON));
            if (line == null) throw new DecoderException("Missing auto-craft plan line");
            list.add(line);
        }
        return list;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(planId);
        writeLines(buf, outputLines);
        writeLines(buf, useLines);
        writeLines(buf, noteLines);
        buf.writeVarInt(requestId);
        buf.writeVarInt(planned);
        buf.writeVarInt(total);
        buf.writeBoolean(openConfirmation);
    }

    private static void writeLines(FriendlyByteBuf buf, List<Component> lines) {
        int n = Math.min(lines.size(), MAX_LINES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Component line = i == n - 1 && lines.size() > n
                    ? omission("more_lines", Integer.toString(lines.size() - i)) : lines.get(i);
            String json = Component.Serializer.toJson(line);
            if (json.length() > MAX_LINE_JSON) json = Component.Serializer.toJson(omission("long_line"));
            buf.writeUtf(json, MAX_LINE_JSON);
        }
    }

    private static Component omission(String key, Object... args) {
        return Component.translatable("gt_shanhai.shop.confirmation.auto_craft." + key, args)
                .withStyle(ChatFormatting.GRAY);
    }

    public static void handle(ShopAutoCraftPlanPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(ShopAutoCraftPlanPacket pkt) {
        var client = net.minecraft.client.Minecraft.getInstance();
        var shop = client.screen instanceof com.dishanhai.gt_shanhai.client.gui.shop.ShopScreen screen ? screen : null;
        boolean abandoned = pkt.openConfirmation && (shop == null || !shop.acceptsAutoCraftPlan(pkt.requestId));
        com.dishanhai.gt_shanhai.client.shop.ClientShopActionResults.offerAutoCraft(
                new com.dishanhai.gt_shanhai.client.shop.ClientShopActionResults.AutoCraftResult(
                        pkt.requestId, abandoned ? 0 : pkt.planned, pkt.total, System.currentTimeMillis()));
        if (abandoned && pkt.planned > 0 && client.getConnection() != null) {
            ShanhaiNetwork.CHANNEL.sendToServer(new ShopAutoCraftConfirmPacket(pkt.planId, false));
        }
        if (shop == null) return;
        if (!pkt.openConfirmation || abandoned) {
            shop.drainAutoCraftResults();
            return;
        }
        com.dishanhai.gt_shanhai.client.gui.shop.ShopAutoCraftConfirmScreen.open(
                shop, pkt.planId, pkt.planned, pkt.total, pkt.outputLines, pkt.useLines, pkt.noteLines);
    }
}
