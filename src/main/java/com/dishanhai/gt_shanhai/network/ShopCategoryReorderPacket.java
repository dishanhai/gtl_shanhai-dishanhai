package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 商店分类页签排序包（C→S，编辑权专属）：拖拽把 category 挪到 parentPath 下的 newIndex 位置
 * （见 {@link ShopConfig#moveCategoryTo}）。跟条目排序（{@link ShopReorderPacket}）是两套独立体系：
 * 页签排序只影响分类显示顺序，不改 shop.json 里商品条目本身的物理顺序。
 *
 * <p>不校验 catalogRevision——页签排序跟条目内容无关，旧版本号下发起也一样安全，服务端只看
 * parentPath 下当前是否真存在这个分类（见 {@link ShopConfig#moveCategoryTo} 内部校验）。</p>
 */
public class ShopCategoryReorderPacket {

    private final String parentPath;
    private final String category;
    private final int newIndex;

    public ShopCategoryReorderPacket(String parentPath, String category, int newIndex) {
        this.parentPath = parentPath == null ? "" : parentPath;
        this.category = category == null ? "" : category;
        this.newIndex = newIndex;
    }

    public ShopCategoryReorderPacket(FriendlyByteBuf buf) {
        this.parentPath = buf.readUtf();
        this.category = buf.readUtf();
        this.newIndex = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(parentPath);
        buf.writeUtf(category);
        buf.writeVarInt(newIndex);
    }

    public static void handle(ShopCategoryReorderPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopCategoryReorderPacket pkt, ServerPlayer player) {
        if (!ShopEditPermission.canEdit(player)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 无编辑权限"));
            return;
        }
        if (!ShopConfig.moveCategoryTo(pkt.parentPath, pkt.category, pkt.newIndex)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 分类页签排序失败（分类不存在或落点无变化）"));
        }
    }
}
