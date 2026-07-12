package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.WalletAccountSyncPacket;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 钱包账户服务端读写 API（山海署名）。全部按<b>玩家本体 UUID</b> 操作
 * {@link WalletAccountSavedData}，数值一律 BigInteger（真无限，不封顶，扣到 0 不为负）。
 *
 * <p>双账本：币种余额（商店结算）+ 数字余额（星火，兑换/多物品流体）。二者按
 * {@link CurrencyRateConfig#getValue 币值} 互转（{@link #convertCurrencyToDigital} /
 * {@link #convertDigitalToCurrency}）。</p>
 *
 * <p><b>仅服务端调用</b>：客户端界面读 {@code ClientWalletAccount} 快照缓存（阶段 C）。</p>
 */
public final class WalletAccountAPI {

    /**
     * 星火（数字余额）作为"伪货币"的保留 ID。商店条目把结算货币填成此值即表示
     * <b>用数字余额（星火）付款/收款</b>，而非某个实体币种。此 ID 不对应任何注册物品。
     */
    public static final ResourceLocation SPARK = new ResourceLocation("gt_shanhai", "spark");

    /** 判断某货币 ID 是否为星火（数字余额伪货币）。 */
    public static boolean isSpark(ResourceLocation id) {
        return SPARK.equals(id);
    }

    private WalletAccountAPI() {}

    private static WalletAccountSavedData data(MinecraftServer server) {
        return WalletAccountSavedData.get(server);
    }

    // ===================== 币种余额 =====================

    public static BigInteger getCurrency(MinecraftServer server, UUID uuid, ResourceLocation currency) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? BigInteger.ZERO : acc.getCurrency(currency);
    }

    /** 增减某币种余额（delta 可负，扣到 0 封底）。 */
    public static void addCurrency(MinecraftServer server, UUID uuid, ResourceLocation currency, BigInteger delta) {
        if (delta == null || delta.signum() == 0 || currency == null) return;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        BigInteger next = acc.getCurrency(currency).add(delta);
        if (next.signum() < 0) next = BigInteger.ZERO;
        acc.setCurrency(currency, next);
        d.setDirty();
    }

    /** 尝试扣某币种，余额不足则不扣返回 false。 */
    public static boolean tryDeductCurrency(MinecraftServer server, UUID uuid, ResourceLocation currency, BigInteger cost) {
        if (cost == null || cost.signum() <= 0) return true;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        BigInteger bal = acc.getCurrency(currency);
        if (bal.compareTo(cost) < 0) return false;
        acc.setCurrency(currency, bal.subtract(cost));
        d.setDirty();
        return true;
    }

    /** 读全部币种余额（副本，保序）。 */
    public static Map<ResourceLocation, BigInteger> getAllCurrencies(MinecraftServer server, UUID uuid) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? new LinkedHashMap<>() : acc.getCurrencyBalances();
    }

    // ===================== 数字余额（星火） =====================

    public static BigInteger getDigital(MinecraftServer server, UUID uuid) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? BigInteger.ZERO : acc.getDigital();
    }

    public static void addDigital(MinecraftServer server, UUID uuid, BigInteger delta) {
        if (delta == null || delta.signum() == 0) return;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        BigInteger next = acc.getDigital().add(delta);
        if (next.signum() < 0) next = BigInteger.ZERO;
        acc.setDigital(next);
        d.setDirty();
    }

    public static boolean tryDeductDigital(MinecraftServer server, UUID uuid, BigInteger cost) {
        if (cost == null || cost.signum() <= 0) return true;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        BigInteger bal = acc.getDigital();
        if (bal.compareTo(cost) < 0) return false;
        acc.setDigital(bal.subtract(cost));
        d.setDirty();
        return true;
    }

    // ===================== 币值互转（阶段 B） =====================

    /**
     * 币种 → 数字余额：扣 count 枚币种，加 {@code count × 币值} 数字余额。
     * 币值≤0（未配置）或余额不足则不动，返回加得的数字余额（0 表示失败）。
     */
    public static BigInteger convertCurrencyToDigital(MinecraftServer server, UUID uuid, ResourceLocation currency, BigInteger count) {
        if (count == null || count.signum() <= 0 || currency == null) return BigInteger.ZERO;
        long value = CurrencyRateConfig.getValue(currency);
        if (value <= 0L) return BigInteger.ZERO;
        if (!tryDeductCurrency(server, uuid, currency, count)) return BigInteger.ZERO;
        BigInteger gain = count.multiply(BigInteger.valueOf(value));
        addDigital(server, uuid, gain);
        return gain;
    }

    /**
     * 数字余额 → 币种：花 {@code coinsWanted × 币值} 星火，换 <b>恰好</b> {@code coinsWanted} 枚币种
     * （数量语义 = 想要的目标币数量，与其余 ATM 操作一致，非"花的星火数"）。
     * 币值≤0（未配置）或星火不足则整体不动，返回 0。
     */
    public static BigInteger convertDigitalToCurrency(MinecraftServer server, UUID uuid, ResourceLocation currency, BigInteger coinsWanted) {
        if (coinsWanted == null || coinsWanted.signum() <= 0 || currency == null) return BigInteger.ZERO;
        long value = CurrencyRateConfig.getValue(currency);
        if (value <= 0L) return BigInteger.ZERO;
        BigInteger spend = coinsWanted.multiply(BigInteger.valueOf(value));
        if (!tryDeductDigital(server, uuid, spend)) return BigInteger.ZERO;
        addCurrency(server, uuid, currency, coinsWanted);
        return coinsWanted;
    }

    // ===================== 客户端同步 =====================

    /** 向该玩家推送其账户全量快照（打开钱包 / 每次账户变动后调用）。 */
    public static void sync(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID uuid = player.getUUID();
        ShanhaiNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new WalletAccountSyncPacket(getAllCurrencies(server, uuid), getDigital(server, uuid)));
    }
}
