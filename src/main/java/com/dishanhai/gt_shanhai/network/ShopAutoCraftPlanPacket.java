package com.dishanhai.gt_shanhai.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 自动合成方案就绪（S→C）：{@link ShopAutoCraftRequestPacket} 的回包，把
 * {@code ShopAutoCraft} 算好的用料预览（会消耗什么 + 哪些项跳过/材料不足）推给客户端，
 * 弹出确认框——{@code anySubmittable=false} 时确认框只能取消（没有任何一项真能提交）。
 */
public class ShopAutoCraftPlanPacket {

    private static final int MAX_LINES = 512;

    private final UUID planId;
    private final boolean anySubmittable;
    private final List<String> useLines;    // 会消耗的合并用料（"物品 ×数量"，已带颜色码）
    private final List<String> noteLines;   // 无样板/材料不足/计算失败的提示（已带颜色码）
    private final int requestId;
    private final int planned;
    private final int total;
    private final boolean openConfirmation;

    public ShopAutoCraftPlanPacket(UUID planId, boolean anySubmittable, List<String> useLines, List<String> noteLines) {
        this(planId, anySubmittable, useLines, noteLines, 0, anySubmittable ? 1 : 0, 1);
    }

    public ShopAutoCraftPlanPacket(UUID planId, boolean anySubmittable, List<String> useLines, List<String> noteLines,
                                   int requestId, int planned, int total) {
        this.planId = planId;
        this.anySubmittable = anySubmittable;
        this.useLines = useLines;
        this.noteLines = noteLines;
        this.requestId = requestId;
        this.planned = planned;
        this.total = total;
        this.openConfirmation = true;
    }

    /** 山海：預檢失敗或已無缺口時只回饋按鍵，不開空白確認窗。 */
    public ShopAutoCraftPlanPacket(int requestId, boolean success) {
        this.planId = new UUID(0L, 0L);
        this.anySubmittable = success;
        this.useLines = List.of();
        this.noteLines = List.of();
        this.requestId = requestId;
        this.planned = success ? 1 : 0;
        this.total = 1;
        this.openConfirmation = false;
    }

    public ShopAutoCraftPlanPacket(FriendlyByteBuf buf) {
        this.planId = buf.readUUID();
        this.anySubmittable = buf.readBoolean();
        this.useLines = readLines(buf);
        this.noteLines = readLines(buf);
        this.requestId = buf.readVarInt();
        this.planned = buf.readVarInt();
        this.total = buf.readVarInt();
        this.openConfirmation = buf.readBoolean();
    }

    private static List<String> readLines(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_LINES) throw new DecoderException("Invalid auto-craft plan line count: " + n);
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUtf(256));
        return list;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(planId);
        buf.writeBoolean(anySubmittable);
        writeLines(buf, useLines);
        writeLines(buf, noteLines);
        buf.writeVarInt(requestId);
        buf.writeVarInt(planned);
        buf.writeVarInt(total);
        buf.writeBoolean(openConfirmation);
    }

    private static void writeLines(FriendlyByteBuf buf, List<String> lines) {
        int n = Math.min(lines.size(), MAX_LINES);
        buf.writeVarInt(n);
        // writeUtf(String, 256) 对超过 256 字符的输入直接抛 EncoderException；调用方拼接的行
        // （如缺料明细）一旦失控变长且未捕获，会把整个 ServerTick 事件链路带崩、直接停服。
        // 这里兜底按字符截断到 256 以内，宁可显示不全也不能崩服务器。
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            if (line.length() > 256) line = line.substring(0, 253) + "...";
            buf.writeUtf(line, 256);
        }
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
        com.dishanhai.gt_shanhai.client.shop.ClientShopActionResults.offerAutoCraft(
                new com.dishanhai.gt_shanhai.client.shop.ClientShopActionResults.AutoCraftResult(
                        pkt.requestId, pkt.planned, pkt.total, System.currentTimeMillis()));
        // 有預覽時保留回執到返回商店再閃色，避免結果在確認窗背後播完；即時錯誤立即顯示。
        if (!pkt.openConfirmation && net.minecraft.client.Minecraft.getInstance().screen
                instanceof com.dishanhai.gt_shanhai.client.gui.shop.ShopScreen shop) {
            shop.drainAutoCraftResults();
        }
        if (!pkt.openConfirmation) return;
        com.dishanhai.gt_shanhai.client.gui.shop.ShopAutoCraftConfirmScreen.openOrUpdate(
                pkt.planId, pkt.anySubmittable, pkt.useLines, pkt.noteLines);
    }
}
