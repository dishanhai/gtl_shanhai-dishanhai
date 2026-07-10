package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 货币 ATM 操作包（C→S）：单币提交 / 币种兑换 / AE 抽取。
 *
 * <p>服务端从发包玩家主/副手定位钱包 {@link WalletItem}，按 {@link Op} 派发到
 * {@link ShopPurchase} 的对应结算方法，全程原地改钱包 NBT（不换对象）。</p>
 */
public class CurrencyActionPacket {

    public enum Op { DEPOSIT, EXCHANGE, AE_EXTRACT }

    private final Op op;
    private final ResourceLocation currency;   // 源/操作币种
    private final ResourceLocation target;     // EXCHANGE 目标币种（其余忽略）
    private final long amount;                 // EXCHANGE/AE_EXTRACT 数量（DEPOSIT 忽略）

    public CurrencyActionPacket(Op op, ResourceLocation currency, ResourceLocation target, long amount) {
        this.op = op;
        this.currency = currency == null ? new ResourceLocation("minecraft:air") : currency;
        this.target = target == null ? new ResourceLocation("minecraft:air") : target;
        this.amount = Math.max(0L, amount);
    }

    public CurrencyActionPacket(FriendlyByteBuf buf) {
        this.op = buf.readEnum(Op.class);
        this.currency = buf.readResourceLocation();
        this.target = buf.readResourceLocation();
        this.amount = buf.readVarLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(op);
        buf.writeResourceLocation(currency);
        buf.writeResourceLocation(target);
        buf.writeVarLong(amount);
    }

    public static void handle(CurrencyActionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(CurrencyActionPacket pkt, ServerPlayer player) {
        ItemStack wallet = findWallet(player);
        if (wallet.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[货币中心] 需手持山海钱包"));
            return;
        }
        switch (pkt.op) {
            case DEPOSIT -> {
                long got = ShopPurchase.depositOne(player, pkt.currency);
                player.sendSystemMessage(got > 0L
                        ? Component.literal("§b[货币中心] §a已提交 §f" + ShopPurchase.formatCount(got) + " §a枚 "
                            + ShopPurchase.coinName(pkt.currency))
                        : Component.literal("§c[货币中心] 背包里没有 " + ShopPurchase.coinName(pkt.currency)));
            }
            case EXCHANGE -> {
                long gain = ShopPurchase.exchange(player, pkt.currency, pkt.target, pkt.amount);
                player.sendSystemMessage(gain > 0L
                        ? Component.literal("§b[货币中心] §a已兑换 §f" + ShopPurchase.formatCount(pkt.amount) + " "
                            + ShopPurchase.coinName(pkt.currency) + " §7→ §f" + ShopPurchase.formatCount(gain) + " "
                            + ShopPurchase.coinName(pkt.target))
                        : Component.literal("§c[货币中心] 兑换失败（余额不足 / 数量太小 / 币值未配置）"));
            }
            case AE_EXTRACT -> {
                long got = ShopPurchase.aeExtractCoin(player, pkt.currency, pkt.amount);
                player.sendSystemMessage(got > 0L
                        ? Component.literal("§b[货币中心] §a已从 AE 抽取 §f" + ShopPurchase.formatCount(got) + " §a枚 "
                            + ShopPurchase.coinName(pkt.currency) + " §7入钱包")
                        : Component.literal("§c[货币中心] AE 抽取失败（无绑定在线提交器 / 网络无此币）"));
            }
        }
        WalletAccountAPI.sync(player); // 回推账户快照，客户端界面即时刷新
    }

    /** 从主/副手找钱包 ItemStack。 */
    private static ItemStack findWallet(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack s = player.getItemInHand(hand);
            if (!s.isEmpty() && s.getItem() instanceof WalletItem) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }
}
