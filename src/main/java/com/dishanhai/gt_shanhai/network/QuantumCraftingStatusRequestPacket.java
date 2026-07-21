package com.dishanhai.gt_shanhai.network;

import appeng.api.stacks.AEKey;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingStatusLookupHelper;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端 -> 服务端：查询当前量子 CPU 中一个物品的真实调度状态。 */
public class QuantumCraftingStatusRequestPacket {

    private final AEKey key;

    public QuantumCraftingStatusRequestPacket(AEKey key) {
        this.key = key;
    }

    public static void encode(QuantumCraftingStatusRequestPacket msg, FriendlyByteBuf buf) {
        AEKey.writeKey(buf, msg.key);
    }

    public static QuantumCraftingStatusRequestPacket decode(FriendlyByteBuf buf) {
        return new QuantumCraftingStatusRequestPacket(AEKey.readKey(buf));
    }

    public static void handle(QuantumCraftingStatusRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player != null && msg.key != null) {
                QuantumCraftingStatusLookupHelper.resolveAndReply(player, msg.key);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
