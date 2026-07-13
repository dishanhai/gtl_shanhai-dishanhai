package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 钱包账户快照同步（S→C）。
 *
 * <p>余额已从 ItemStack NBT 迁到服务端 SavedData（按玩家UUID），客户端界面/tooltip 读不到，
 * 故服务端在<b>打开钱包</b>及<b>每次账户变动</b>后向该玩家推送账户全量（各币种余额 + 数字余额），
 * 客户端写入 {@code ClientWalletAccount} 缓存。BigInteger 走 {@code writeByteArray(toByteArray())}。</p>
 */
public class WalletAccountSyncPacket {

    private final Map<ResourceLocation, BigInteger> currencies;
    private final BigInteger digital;
    private final Map<String, Long> purchaseCounts;

    public WalletAccountSyncPacket(Map<ResourceLocation, BigInteger> currencies, BigInteger digital,
                                    Map<String, Long> purchaseCounts) {
        this.currencies = currencies != null ? currencies : new LinkedHashMap<>();
        this.digital = digital != null ? digital : BigInteger.ZERO;
        this.purchaseCounts = purchaseCounts != null ? purchaseCounts : new LinkedHashMap<>();
    }

    public WalletAccountSyncPacket(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<ResourceLocation, BigInteger> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            ResourceLocation id = buf.readResourceLocation();
            byte[] bytes = buf.readByteArray();
            map.put(id, bytes.length == 0 ? BigInteger.ZERO : new BigInteger(bytes));
        }
        this.currencies = map;
        byte[] db = buf.readByteArray();
        this.digital = db.length == 0 ? BigInteger.ZERO : new BigInteger(db);
        int pn = buf.readVarInt();
        Map<String, Long> pur = new LinkedHashMap<>();
        for (int i = 0; i < pn; i++) {
            String key = buf.readUtf();
            long v = buf.readVarLong();
            pur.put(key, v);
        }
        this.purchaseCounts = pur;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(currencies.size());
        for (Map.Entry<ResourceLocation, BigInteger> e : currencies.entrySet()) {
            buf.writeResourceLocation(e.getKey());
            buf.writeByteArray(e.getValue() == null ? new byte[0] : e.getValue().toByteArray());
        }
        buf.writeByteArray(digital.toByteArray());
        buf.writeVarInt(purchaseCounts.size());
        for (Map.Entry<String, Long> e : purchaseCounts.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarLong(e.getValue() == null ? 0L : e.getValue());
        }
    }

    public static void handle(WalletAccountSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(WalletAccountSyncPacket pkt) {
        com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount.apply(pkt.currencies, pkt.digital, pkt.purchaseCounts);
    }
}
