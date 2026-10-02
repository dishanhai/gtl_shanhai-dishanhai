package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;
import com.dishanhai.gt_shanhai.common.shop.ShopStageConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 管理员保存分类阶段的提交物品配置。 */
public final class ShopStageEditPacket {
    private final String path;
    private final List<ExchangeEntry.Ingredient> items;

    public ShopStageEditPacket(String path, List<ExchangeEntry.Ingredient> items) {
        this.path = path == null ? "" : path;
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    public ShopStageEditPacket(FriendlyByteBuf buf) {
        path = buf.readUtf();
        int count = Math.min(64, buf.readVarInt());
        List<ExchangeEntry.Ingredient> parsed = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = buf.readResourceLocation();
            long amount = buf.readVarLong();
            net.minecraft.nbt.CompoundTag nbt = buf.readNbt();
            if (ForgeRegistries.ITEMS.containsKey(id)) parsed.add(new ExchangeEntry.Ingredient(id, false, amount, nbt));
        }
        items = parsed;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(path);
        buf.writeVarInt(items.size());
        for (ExchangeEntry.Ingredient item : items) {
            buf.writeResourceLocation(item.id);
            buf.writeVarLong(item.count);
            buf.writeNbt(item.nbt());
        }
    }

    public static void handle(ShopStageEditPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (!ShopEditPermission.canEdit(player)) {
                player.sendSystemMessage(Component.literal("§c[山海商店] 无编辑权限"));
                return;
            }
            if (packet.path.isBlank()) return;
            ShopStageConfig.set(packet.path, packet.items);
            com.dishanhai.gt_shanhai.common.shop.ShopSubmissionSavedData.get(player.getServer())
                    .clearKey("stage:" + packet.path);
            ShopSubmissionSyncPacket.sync(player);
            player.sendSystemMessage(Component.literal(packet.items.isEmpty()
                    ? "§b[山海商店] 已清除阶段提交限制: " + packet.path
                    : "§b[山海商店] 已保存阶段提交限制: " + packet.path));
        });
        context.setPacketHandled(true);
    }
}
