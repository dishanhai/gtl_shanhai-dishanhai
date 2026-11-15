package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 货币中心周期限售（山海署名）。每位玩家、每种低阶货币独立记账。 */
public final class CurrencySaleLimit {

    public static final long DEFAULT_LIMIT = 4096L;
    public static final long DEFAULT_SECONDS = 180L;
    public static final long MAX_SECONDS = Long.MAX_VALUE / 1000L;

    private CurrencySaleLimit() {}

    public static boolean isLimited(long faceValue) {
        return faceValue > 0L && faceValue <= SparkMint.FULL_RATE_FACE;
    }

    public static long tierLimit(long faceValue, long baseLimit) {
        if (!isLimited(faceValue)) return Long.MAX_VALUE;
        // 低阶币面值每档乘 4，限售枚数每档乘 2。
        int tier = (Long.SIZE - 1 - Long.numberOfLeadingZeros(faceValue)) / 2;
        long multiplier = 1L << tier;
        return baseLimit <= Long.MAX_VALUE / multiplier
                ? baseLimit * multiplier : Long.MAX_VALUE;
    }

    public static String key(ResourceLocation currency) {
        // 使用独立键，避免把旧存档里的游戏 tick 锚点当成现实时间戳。
        return "currency-sale-ms:" + currency;
    }

    public static long millisUntilRefresh(long nowMs, long startMs, long periodSeconds) {
        if (startMs < 0L) return 0L;
        long elapsedMs = nowMs > startMs ? nowMs - startMs : 0L;
        return Math.max(0L, periodSeconds * 1000L - elapsedMs);
    }

    public static long remaining(ServerPlayer player, ResourceLocation currency) {
        return remaining(player, currency, System.currentTimeMillis());
    }

    static long remaining(ServerPlayer player, ResourceLocation currency, long nowMs) {
        long faceValue = CurrencyRateConfig.getValue(currency);
        if (!isLimited(faceValue)) return Long.MAX_VALUE;
        long limit = tierLimit(faceValue, DShanhaiConfig.COMMON.shopCurrencySaleLimit.get());
        MinecraftServer server = player.getServer();
        String key = key(currency);
        long startMs = WalletAccountAPI.getPeriodWindow(server, player.getUUID(), key);
        long periodSeconds = DShanhaiConfig.COMMON.shopCurrencySalePeriodSeconds.get();
        if (millisUntilRefresh(nowMs, startMs, periodSeconds) == 0L) return limit;
        return Math.max(0L, limit - WalletAccountAPI.getPeriodUsed(server, player.getUUID(), key));
    }

    static void consume(ServerPlayer player, ResourceLocation currency, long amount, long nowMs) {
        MinecraftServer server = player.getServer();
        String key = key(currency);
        long startMs = WalletAccountAPI.getPeriodWindow(server, player.getUUID(), key);
        long periodSeconds = DShanhaiConfig.COMMON.shopCurrencySalePeriodSeconds.get();
        boolean active = millisUntilRefresh(nowMs, startMs, periodSeconds) > 0L;
        long used = active ? WalletAccountAPI.getPeriodUsed(server, player.getUUID(), key) : 0L;
        WalletAccountAPI.setPeriodState(server, player.getUUID(), key, active ? startMs : nowMs, used + amount);
    }
}
