package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternWarningHistory;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 山海：從星律告警歷史刪掉一條，並把同一份報告刷回開啟中的詳情。 */
public record StellarWarningDismissPacket(BlockPos pos, String key) {
    private static final int MAX_KEY = 200;

    public StellarWarningDismissPacket(FriendlyByteBuf buffer) {
        this(buffer.readBlockPos(), buffer.readUtf(MAX_KEY));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeUtf(key == null ? "" : key, MAX_KEY);
    }

    public static void send(BlockPos pos, String key) {
        ShanhaiNetwork.CHANNEL.sendToServer(new StellarWarningDismissPacket(pos, key));
    }

    public static void handle(StellarWarningDismissPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> apply(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(StellarWarningDismissPacket packet, ServerPlayer player) {
        if (player == null || packet.pos == null || packet.key == null) return;
        int split = packet.key.indexOf('\n');
        if (split <= 0 || split >= packet.key.length() - 1) return;
        ResourceLocation dimension = ResourceLocation.tryParse(packet.key.substring(0, split));
        String incident = packet.key.substring(split + 1);
        if (dimension == null || incident.isBlank() || incident.indexOf('\n') >= 0 || !incident.contains(":slot:")) return;
        var level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null || !level.hasChunkAt(packet.pos)) return;
        if (!(MetaMachine.getMachine(level, packet.pos) instanceof RecipeTypePatternBufferPartMachine machine)) return;
        var history = StellarPatternWarningHistory.get(player.server);
        int slot = history.dismiss(dimension, packet.pos, incident);
        if (slot < 0) return;
        machine.gtShanhai$forgetDismissedStuckReport(slot);
        ShanhaiTerminalReportPacket.sendTo(player, history.build(dimension, packet.pos));
    }
}
