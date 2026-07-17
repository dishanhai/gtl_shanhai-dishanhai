package com.dishanhai.gt_shanhai.network;

import appeng.api.stacks.AEKey;

import com.dishanhai.gt_shanhai.common.ae2.quantum.PatternSourceLookupHelper;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：合成状态详情页请求"这些物品分别是哪个样板总成在供"。
 * 只发未缓存过的 AEKey，服务端在玩家当前打开的合成状态菜单绑定的量子CPU所在网络内查找。
 */
public class PatternSourceRequestPacket {

    private final List<AEKey> keys;

    public PatternSourceRequestPacket(List<AEKey> keys) {
        this.keys = keys;
    }

    public static void encode(PatternSourceRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.keys.size());
        for (AEKey key : msg.keys) {
            AEKey.writeKey(buf, key);
        }
    }

    public static PatternSourceRequestPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<AEKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AEKey key = AEKey.readKey(buf);
            if (key != null) keys.add(key);
        }
        return new PatternSourceRequestPacket(keys);
    }

    public static void handle(PatternSourceRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null || msg.keys.isEmpty()) return;
            PatternSourceLookupHelper.resolveAndReply(player, msg.keys);
        });
        ctx.get().setPacketHandled(true);
    }
}
