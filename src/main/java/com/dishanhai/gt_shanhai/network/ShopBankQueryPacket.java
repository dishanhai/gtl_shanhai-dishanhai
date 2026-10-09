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
 * 会员中心「银行」查询响应（S→C）。前两个字段仍是存款合计与欠款合计，后面带本金、利息、
 * 可借额度和服务器利率，避免客户端拿自己的配置文件显示利差。
 */
public class ShopBankQueryPacket {

    private final BigInteger deposit;
    private final BigInteger debt;
    private final BigInteger depositPrincipal;
    private final BigInteger depositInterest;
    private final BigInteger debtPrincipal;
    private final BigInteger debtInterest;
    private final BigInteger loanRoom;
    private final int depositRateBp;
    private final int loanRateBp;
    private final long maxLoan;
    private final boolean overdue;

    public ShopBankQueryPacket(WalletAccountAPI.BankView view) {
        WalletAccountAPI.BankView src = view;
        this.deposit = src.depositTotal();
        this.debt = src.debtTotal();
        this.depositPrincipal = src.depositPrincipal;
        this.depositInterest = src.depositInterest;
        this.debtPrincipal = src.debtPrincipal;
        this.debtInterest = src.debtInterest;
        this.loanRoom = src.loanRoom;
        this.depositRateBp = src.depositRateBp;
        this.loanRateBp = src.loanRateBp;
        this.maxLoan = src.maxLoan;
        this.overdue = src.overdue;
    }

    public ShopBankQueryPacket(FriendlyByteBuf buf) {
        this.deposit = readBi(buf);
        this.debt = readBi(buf);
        this.depositPrincipal = readBi(buf);
        this.depositInterest = readBi(buf);
        this.debtPrincipal = readBi(buf);
        this.debtInterest = readBi(buf);
        this.loanRoom = readBi(buf);
        this.depositRateBp = buf.readVarInt();
        this.loanRateBp = buf.readVarInt();
        this.maxLoan = buf.readVarLong();
        this.overdue = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        writeBi(buf, deposit);
        writeBi(buf, debt);
        writeBi(buf, depositPrincipal);
        writeBi(buf, depositInterest);
        writeBi(buf, debtPrincipal);
        writeBi(buf, debtInterest);
        writeBi(buf, loanRoom);
        buf.writeVarInt(depositRateBp);
        buf.writeVarInt(loanRateBp);
        buf.writeVarLong(maxLoan);
        buf.writeBoolean(overdue);
    }

    /** 服务端：把该玩家当前的存款/欠款快照（含惰性结息副作用）推给客户端。 */
    public static void sendTo(ServerPlayer player) {
        if (player == null) return;
        WalletAccountAPI.BankView view = WalletAccountAPI.bankView(player.getServer(), player.getUUID());
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ShopBankQueryPacket(view));
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
        com.dishanhai.gt_shanhai.client.shop.ClientShopBank.apply(new com.dishanhai.gt_shanhai.client.shop.ClientShopBank.Snapshot(
                pkt.depositPrincipal, pkt.depositInterest, pkt.debtPrincipal, pkt.debtInterest,
                pkt.loanRoom, pkt.depositRateBp, pkt.loanRateBp, pkt.maxLoan, pkt.overdue));
    }

    private static void writeBi(FriendlyByteBuf buf, BigInteger value) {
        BigInteger safe = value == null ? BigInteger.ZERO : value;
        buf.writeByteArray(safe.toByteArray());
    }

    private static BigInteger readBi(FriendlyByteBuf buf) {
        byte[] bytes = buf.readByteArray();
        return bytes.length == 0 ? BigInteger.ZERO : new BigInteger(bytes);
    }
}
