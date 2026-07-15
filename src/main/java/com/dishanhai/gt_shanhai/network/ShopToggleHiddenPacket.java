package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 商店条目「设为隐藏/取消隐藏」专用包（C→S，编辑权专属）。
 *
 * <p>单独拆出来（而不是走 {@link ShopEditPacket} 的 Action.EDIT），是因为「编辑条目」
 * 折进了编辑模式（{@link com.dishanhai.gt_shanhai.common.shop.ShopEditMode}，见反馈）后，
 * 服务端要能严格拦「编辑条目」而不连带误伤「设为隐藏」——两者若共用同一个网络包，
 * 服务端没法只区分出是从哪个入口发起的。这里只校验 {@link ShopEditPermission#canEdit}，
 * 不要求编辑模式，跟排序（{@link ShopReorderPacket}）同一档权限。</p>
 */
public class ShopToggleHiddenPacket {

    private final long catalogRevision;
    private final long entryKey;

    public ShopToggleHiddenPacket(long catalogRevision, long entryKey) {
        this.catalogRevision = catalogRevision;
        this.entryKey = entryKey;
    }

    public ShopToggleHiddenPacket(FriendlyByteBuf buf) {
        this.catalogRevision = buf.readLong();
        this.entryKey = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(catalogRevision);
        buf.writeLong(entryKey);
    }

    public static void handle(ShopToggleHiddenPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopToggleHiddenPacket pkt, ServerPlayer player) {
        if (!ShopEditPermission.canEdit(player)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 无编辑权限"));
            return;
        }
        ShopEntry old = ShopConfig.resolve(pkt.catalogRevision, pkt.entryKey);
        if (old == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品目录已更新或商品不存在，请重新打开"));
            return;
        }
        ShopEntry updated = new ShopEntry(old.getGoodsList(), old.getCategory(), old.getCost(), old.getDescription(),
                old.getRemainingUses(), old.getDisplayIcons(), old.getRewardMode(), old.getRewardPool(),
                !old.isHidden(), old.getLinkKey(), old.getLinkTo(), old.getDisplayName(), old.getFtbqTableId(),
                old.getFtbqSubMode(), old.getTradeMode(), old.getPeriodTicks(), old.getPeriodLimit(),
                old.getPrerequisiteQuestId(), old.getStableId());
        boolean ok = ShopConfig.replaceEntry(old, updated);
        player.sendSystemMessage(ok
                ? Component.literal(updated.isHidden() ? "§b[山海商店] §a已设为隐藏" : "§b[山海商店] §a已取消隐藏")
                : Component.literal("§c[山海商店] 操作失败"));
    }
}
