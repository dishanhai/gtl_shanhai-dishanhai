package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 打开商店界面包（S→C）：服务端命令 {@code /商店} 触发，让客户端打开 {@code ShopScreen}。
 * 携带 {@code canEdit}：服务端算好的当前玩家编辑权（OP 或白名单），客户端据此显隐「编辑条目/商店设置」
 * 等按钮；{@code catalogEditUnlocked}：在 canEdit 基础上是否还开了 {@code /山海 商店 编辑} 编辑模式，
 * 客户端据此单独显隐「新增商品/删除商品」这类目录增删按钮（见 {@link com.dishanhai.gt_shanhai.common.shop.ShopEditPermission#canEditCatalog}）。
 */
public class ShopOpenPacket {

    /** 所有开店入口统一先推服务端禁用状态与 OP 权限。 */
    public static void sendTo(net.minecraft.server.level.ServerPlayer player) {
        ShopAvailabilityPacket.sendTo(player);
        ShanhaiNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new ShopOpenPacket(com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEdit(player),
                        com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEditCatalog(player),
                        com.dishanhai.gt_shanhai.common.shop.ShopConfig.manifest()));
    }

    private final boolean canEdit;
    private final boolean catalogEditUnlocked;
    private final com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest manifest;

    public ShopOpenPacket() {
        this(false, false, com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest.empty());
    }

    public ShopOpenPacket(boolean canEdit, boolean catalogEditUnlocked) {
        this(canEdit, catalogEditUnlocked, com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest.empty());
    }

    public ShopOpenPacket(boolean canEdit, boolean catalogEditUnlocked,
                           com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest manifest) {
        this.canEdit = canEdit;
        this.catalogEditUnlocked = catalogEditUnlocked;
        this.manifest = manifest == null
                ? com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest.empty() : manifest;
    }

    public ShopOpenPacket(FriendlyByteBuf buf) {
        this.canEdit = buf.readBoolean();
        this.catalogEditUnlocked = buf.readBoolean();
        this.manifest = ShopCatalogCodecs.readManifest(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(canEdit);
        buf.writeBoolean(catalogEditUnlocked);
        ShopCatalogCodecs.writeManifest(buf, manifest);
    }

    public static void handle(ShopOpenPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> openClient(pkt.canEdit, pkt.catalogEditUnlocked, pkt.manifest));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void openClient(boolean canEdit, boolean catalogEditUnlocked,
                                   com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest manifest) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog.applyManifest(manifest);
        com.dishanhai.gt_shanhai.client.gui.shop.ShopScreenOpener.open(canEdit, catalogEditUnlocked);
    }
}
