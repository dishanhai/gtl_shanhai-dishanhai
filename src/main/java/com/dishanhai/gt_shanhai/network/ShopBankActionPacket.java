package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.math.BigInteger;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 会员中心「银行」操作请求（C→S）：定期存款存入/取出、贷款借款/还款（见 {@link WalletAccountAPI} 的
 * {@code bank*} 系列结算方法）。结算后回推钱包快照（星火余额变动）+ 银行查询回包（存款/欠款变动）。
 */
public class ShopBankActionPacket {

    public enum Op { DEPOSIT, WITHDRAW, BORROW, REPAY }

    private final Op op;
    private final long amount;

    public ShopBankActionPacket(Op op, long amount) {
        this.op = op;
        this.amount = Math.max(0L, amount);
    }

    public ShopBankActionPacket(FriendlyByteBuf buf) {
        this.op = buf.readEnum(Op.class);
        this.amount = buf.readVarLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(op);
        buf.writeVarLong(amount);
    }

    public static void handle(ShopBankActionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopBankActionPacket pkt, ServerPlayer player) {
        MinecraftServer server = player.getServer();
        UUID uuid = player.getUUID();
        BigInteger amt = BigInteger.valueOf(pkt.amount);
        BigInteger result;
        String label;
        switch (pkt.op) {
            case DEPOSIT -> { result = WalletAccountAPI.bankDeposit(server, uuid, amt); label = "存入"; }
            case WITHDRAW -> { result = WalletAccountAPI.bankWithdraw(server, uuid, amt); label = "取出"; }
            case BORROW -> { result = WalletAccountAPI.bankBorrow(server, uuid, amt); label = "借出"; }
            default -> { result = WalletAccountAPI.bankRepay(server, uuid, amt); label = "还款"; }
        }
        player.sendSystemMessage(result.signum() > 0
                ? Component.literal("§b[山海银行] §a已" + label + " §f" + ShopPurchase.formatCount(
                        result.bitLength() < 63 ? result.longValue() : Long.MAX_VALUE) + " §a星火")
                : Component.literal("§c[山海银行] " + label + "失败（余额/欠款不足，或已达欠款上限）"));
        WalletAccountAPI.sync(player); // 星火数字余额变动
        ShopBankQueryPacket.sendTo(player); // 定期存款/欠款变动
    }
}
