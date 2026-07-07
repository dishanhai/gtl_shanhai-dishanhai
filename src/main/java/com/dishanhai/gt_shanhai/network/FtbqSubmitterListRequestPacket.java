package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class FtbqSubmitterListRequestPacket {
    private final long taskId;

    public FtbqSubmitterListRequestPacket(long taskId) {
        this.taskId = taskId;
    }

    public FtbqSubmitterListRequestPacket(FriendlyByteBuf buf) {
        this.taskId = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(taskId);
    }

    public static void handle(FtbqSubmitterListRequestPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void applyOnServer(FtbqSubmitterListRequestPacket packet, ServerPlayer player) {
        if (player == null || ServerQuestFile.INSTANCE == null) return;
        Task task = ServerQuestFile.INSTANCE.getTask(packet.taskId);
        if (!(task instanceof ItemTask itemTask)) return;
        if (!itemTask.consumesResources()) return;
        ShanhaiNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new FtbqSubmitterListResponsePacket(packet.taskId, FtbqAeSubmitterMachine.listSubmitters(player))
        );
    }
}
