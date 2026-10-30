package com.dishanhai.gt_shanhai.network;

import java.util.function.Supplier;

import com.dishanhai.gt_shanhai.client.ShanhaiTerminalReportClient;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/** 山海終端完整報告（伺服器至客戶端）。聊天欄的連結點擊後在客戶端開啟報告介面。 */
public final class ShanhaiTerminalReportPacket {

    private final ShanhaiTerminalReport report;
    private final boolean open;

    public ShanhaiTerminalReportPacket(ShanhaiTerminalReport report) {
        this(report, false);
    }

    private ShanhaiTerminalReportPacket(ShanhaiTerminalReport report, boolean open) {
        this.report = report;
        this.open = open;
    }

    public ShanhaiTerminalReportPacket(FriendlyByteBuf buf) {
        this.open = buf.readBoolean();
        this.report = ShanhaiTerminalReport.decode(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        report.encode(buf);
    }

    public static void handle(ShanhaiTerminalReportPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(packet.report, packet.open));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShanhaiTerminalReport report, boolean open) {
        ShanhaiTerminalReportClient.store(report);
        if (open) ShanhaiTerminalReportClient.run(ShanhaiTerminalReport.CHAT_COMMAND + report.id());
    }

    public static void sendTo(ServerPlayer player, ShanhaiTerminalReport report) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ShanhaiTerminalReportPacket(report));
    }

    public static void sendAndOpenTo(ServerPlayer player, ShanhaiTerminalReport report) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShanhaiTerminalReportPacket(report, true));
    }
}
