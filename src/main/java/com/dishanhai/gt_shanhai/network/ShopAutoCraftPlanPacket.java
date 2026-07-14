package com.dishanhai.gt_shanhai.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 自动合成方案就绪（S→C）：{@link ShopAutoCraftRequestPacket} 的回包，把
 * {@code ShopAutoCraft} 算好的用料预览（会消耗什么 + 哪些项跳过/材料不足）推给客户端，
 * 弹出确认框——{@code anySubmittable=false} 时确认框只能取消（没有任何一项真能提交）。
 */
public class ShopAutoCraftPlanPacket {

    private static final int MAX_LINES = 512;

    private final boolean anySubmittable;
    private final List<String> useLines;    // 会消耗的合并用料（"物品 ×数量"，已带颜色码）
    private final List<String> noteLines;   // 无样板/材料不足/计算失败的提示（已带颜色码）

    public ShopAutoCraftPlanPacket(boolean anySubmittable, List<String> useLines, List<String> noteLines) {
        this.anySubmittable = anySubmittable;
        this.useLines = useLines;
        this.noteLines = noteLines;
    }

    public ShopAutoCraftPlanPacket(FriendlyByteBuf buf) {
        this.anySubmittable = buf.readBoolean();
        this.useLines = readLines(buf);
        this.noteLines = readLines(buf);
    }

    private static List<String> readLines(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_LINES) throw new DecoderException("Invalid auto-craft plan line count: " + n);
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readUtf(256));
        return list;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(anySubmittable);
        writeLines(buf, useLines);
        writeLines(buf, noteLines);
    }

    private static void writeLines(FriendlyByteBuf buf, List<String> lines) {
        int n = Math.min(lines.size(), MAX_LINES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeUtf(lines.get(i), 256);
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
        com.dishanhai.gt_shanhai.client.gui.shop.ShopAutoCraftConfirmScreen.openOrUpdate(
                pkt.anySubmittable, pkt.useLines, pkt.noteLines);
    }
}
