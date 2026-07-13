package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 打开商店界面包（S→C）：服务端命令 {@code /商店} 触发，让客户端打开 {@code ShopScreen}。
 * 携带 {@code canEdit}：服务端算好的当前玩家编辑权（OP 或白名单），客户端据此显隐编辑按钮。
 */
public class ShopOpenPacket {

    private final boolean canEdit;
    private final com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest manifest;

    public ShopOpenPacket() {
        this(false, com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest.empty());
    }

    public ShopOpenPacket(boolean canEdit) {
        this(canEdit, com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest.empty());
    }

    public ShopOpenPacket(boolean canEdit, com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest manifest) {
        this.canEdit = canEdit;
        this.manifest = manifest == null
                ? com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest.empty() : manifest;
    }

    public ShopOpenPacket(FriendlyByteBuf buf) {
        this.canEdit = buf.readBoolean();
        this.manifest = ShopCatalogCodecs.readManifest(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(canEdit);
        ShopCatalogCodecs.writeManifest(buf, manifest);
    }

    public static void handle(ShopOpenPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> openClient(pkt.canEdit, pkt.manifest));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void openClient(boolean canEdit,
                                   com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest manifest) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog.applyManifest(manifest);
        com.dishanhai.gt_shanhai.client.gui.shop.ShopScreenOpener.open(canEdit);
    }
}
