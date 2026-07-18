package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.math.BigInteger;
import java.util.function.Supplier;

/**
 * 会员中心「银行」查询响应（S→C）：定期存款/欠款本息快照（已惰性结算最新利息，见
 * {@link WalletAccountAPI#getBankDeposit}/{@link WalletAccountAPI#getBankDebt}），
 * 写入 {@code ClientShopBank} 缓存，供 {@code ShopMembershipScreen} 显示。
 */
public class ShopBankQueryPacket {

    private final BigInteger deposit;
    private final BigInteger debt;

    public ShopBankQueryPacket(BigInteger deposit, BigInteger debt) {
        this.deposit = deposit == null ? BigInteger.ZERO : deposit;
        this.debt = debt == null ? BigInteger.ZERO : debt;
    }

    public ShopBankQueryPacket(FriendlyByteBuf buf) {
        byte[] db = buf.readByteArray();
        this.deposit = db.length == 0 ? BigInteger.ZERO : new BigInteger(db);
        byte[] dt = buf.readByteArray();
        this.debt = dt.length == 0 ? BigInteger.ZERO : new BigInteger(dt);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByteArray(deposit.toByteArray());
        buf.writeByteArray(debt.toByteArray());
    }

    /** 服务端：把该玩家当前的存款/欠款快照（含惰性结息副作用）推给客户端。 */
    public static void sendTo(ServerPlayer player) {
        if (player == null) return;
        BigInteger deposit = WalletAccountAPI.getBankDeposit(player.getServer(), player.getUUID());
        BigInteger debt = WalletAccountAPI.getBankDebt(player.getServer(), player.getUUID());
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ShopBankQueryPacket(deposit, debt));
    }

    public static void handle(ShopBankQueryPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopBankQueryPacket pkt) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopBank.apply(pkt.deposit, pkt.debt);
    }
}
