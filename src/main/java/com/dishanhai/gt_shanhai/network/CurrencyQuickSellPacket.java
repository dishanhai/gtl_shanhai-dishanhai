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
 * 網路按可出售量補充各幣種，再按幣值轉成星火。</p>
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
        if (!com.dishanhai.gt_shanhai.common.shop.ShopAvailability.requireEnabled(player)) return;
        ShopPurchase.QuickSellResult result = ShopPurchase.quickSellAllCurrencies(player, pkt.includeAe);
        if (result.gained().signum() > 0) {
            player.sendSystemMessage(Component.translatable("gt_shanhai.shop.currency_sale.quick_success",
                    ShopPurchase.formatCount(result.gained())));
            if (result.extracted().signum() > 0) {
                player.sendSystemMessage(Component.translatable("gt_shanhai.shop.currency_sale.quick_ae",
                        ShopPurchase.formatCount(result.extracted())));
            }
        }
        if (result.limitedCurrencies() > 0) {
            player.sendSystemMessage(Component.translatable("gt_shanhai.shop.currency_sale.quick_limited",
                    result.limitedCurrencies()));
        }
        if (result.skippedLocked() > 0) {
            player.sendSystemMessage(Component.translatable("gt_shanhai.shop.currency_sale.quick_locked",
                    result.skippedLocked()));
        } else if (result.gained().signum() == 0 && result.limitedCurrencies() == 0) {
            player.sendSystemMessage(Component.translatable("gt_shanhai.shop.currency_sale.quick_empty"));
        }
        WalletAccountAPI.sync(player);
    }
}
