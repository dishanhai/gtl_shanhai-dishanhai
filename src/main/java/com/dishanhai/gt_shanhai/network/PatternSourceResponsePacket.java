package com.dishanhai.gt_shanhai.network;

import appeng.api.stacks.AEKey;

import com.dishanhai.gt_shanhai.client.PatternSourceClientCache;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 服务端 -> 客户端：回填"物品对应的样板配方类型 + 供应它的样板总成坐标"，写入客户端缓存。 */
public class PatternSourceResponsePacket {

    private final List<AEKey> keys;
    private final List<BlockPos> positions;
    private final List<String> recipeTypeIds;

    public PatternSourceResponsePacket(List<AEKey> keys, List<BlockPos> positions, List<String> recipeTypeIds) {
        this.keys = keys;
        this.positions = positions;
        this.recipeTypeIds = recipeTypeIds;
    }

    public static void encode(PatternSourceResponsePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.keys.size());
        for (int i = 0; i < msg.keys.size(); i++) {
            AEKey.writeKey(buf, msg.keys.get(i));
            buf.writeBlockPos(msg.positions.get(i));
            buf.writeUtf(msg.recipeTypeIds.get(i));
        }
    }

    public static PatternSourceResponsePacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<AEKey> keys = new ArrayList<>(count);
        List<BlockPos> positions = new ArrayList<>(count);
        List<String> recipeTypeIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AEKey key = AEKey.readKey(buf);
            BlockPos pos = buf.readBlockPos();
            String typeId = buf.readUtf();
            if (key != null) {
                keys.add(key);
                positions.add(pos);
                recipeTypeIds.add(typeId);
            }
        }
        return new PatternSourceResponsePacket(keys, positions, recipeTypeIds);
    }

    public static void handle(PatternSourceResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> PatternSourceClientCache.putAll(msg.keys, msg.positions, msg.recipeTypeIds));
        ctx.get().setPacketHandled(true);
    }
}
