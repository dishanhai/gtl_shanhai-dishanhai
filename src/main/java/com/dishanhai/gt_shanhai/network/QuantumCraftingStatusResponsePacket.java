package com.dishanhai.gt_shanhai.network;

import appeng.api.stacks.AEKey;

import com.dishanhai.gt_shanhai.client.QuantumCraftingStatusClientCache;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingStatus;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端 -> 客户端：回填量子 CPU 中一个物品的真实调度状态。 */
public class QuantumCraftingStatusResponsePacket {

    private final AEKey key;
    private final QuantumCraftingStatus status;

    public QuantumCraftingStatusResponsePacket(AEKey key, QuantumCraftingStatus status) {
        this.key = key;
        this.status = status;
    }

    public static void encode(QuantumCraftingStatusResponsePacket msg, FriendlyByteBuf buf) {
        AEKey.writeKey(buf, msg.key);
        QuantumCraftingStatus status = msg.status;
        buf.writeVarInt(status.state().ordinal());
        buf.writeBoolean(status.blockingInput() != null);
        if (status.blockingInput() != null) {
            AEKey.writeKey(buf, status.blockingInput());
        }
        buf.writeVarLong(status.availableInput());
        buf.writeVarLong(status.requiredInputPerPattern());
        buf.writeVarLong(status.runnablePatterns());
        buf.writeVarLong(status.remainingPatterns());
        buf.writeVarLong(status.waitingForInput());
        buf.writeVarLong(status.pendingInput());
        buf.writeVarLong(status.waitingForOutput());
        buf.writeVarLong(status.pendingOutput());
    }

    public static QuantumCraftingStatusResponsePacket decode(FriendlyByteBuf buf) {
        AEKey key = AEKey.readKey(buf);
        QuantumCraftingStatus.State state = QuantumCraftingStatus.State.fromNetworkId(buf.readVarInt());
        AEKey blockingInput = buf.readBoolean() ? AEKey.readKey(buf) : null;
        QuantumCraftingStatus status = new QuantumCraftingStatus(
                state,
                blockingInput,
                buf.readVarLong(),
                buf.readVarLong(),
                buf.readVarLong(),
                buf.readVarLong(),
                buf.readVarLong(),
                buf.readVarLong(),
                buf.readVarLong(),
                buf.readVarLong());
        return new QuantumCraftingStatusResponsePacket(key, status);
    }

    public static void handle(QuantumCraftingStatusResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (msg.key != null) {
                QuantumCraftingStatusClientCache.put(msg.key, msg.status);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
