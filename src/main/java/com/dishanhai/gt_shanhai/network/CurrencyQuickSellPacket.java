package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 貨幣中心「快速售出全部貨幣」請求（C→S）。
 *
 * <p>服務端以玩家 UUID 帳戶為權威；{@code includeAe} 為真時，會先自綁定在線 AE
 * 網路抽取各幣種，再按幣值全部轉成星火。</p>
 */
public class CurrencyQuickSellPacket {

    private final boolean includeAe;

    public CurrencyQuickSellPacket(boolean includeAe) {
        this.includeAe = includeAe;
    }

    public CurrencyQuickSellPacket(FriendlyByteBuf buf) {
        this.includeAe = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(includeAe);
    }

    public static void handle(CurrencyQuickSellPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> apply(pkt, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(CurrencyQuickSellPacket pkt, ServerPlayer player) {
        if (player == null || !WalletItem.isCarrying(player)) return;
        ShopPurchase.QuickSellResult result = ShopPurchase.quickSellAllCurrencies(player, pkt.includeAe);
        if (result.gained().signum() > 0) {
            String aeHint = pkt.includeAe && result.extracted().signum() > 0
                    ? "（已自動抽取 AE " + ShopPurchase.formatCount(result.extracted()) + " 枚）"
                    : "";
            player.sendSystemMessage(Component.literal("§b[货币中心] §a已快速售出全部货币，获得 §e"
                    + ShopPurchase.formatCount(result.gained()) + " 星火 §7" + aeHint));
        } else {
            player.sendSystemMessage(Component.literal(
                    "§c[货币中心] 没有可按币值售出的货币（特殊货币请前往兑换中心）"));
        }
        WalletAccountAPI.sync(player);
    }
}
