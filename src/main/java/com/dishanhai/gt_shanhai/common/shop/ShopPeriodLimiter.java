package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 商品周期限购（山海署名）：在 {@link ShopEntry#getRemainingUses} 永久总量之外的第二套独立限购，
 * 例如「每 1000 秒限购 100 万次」，到点自动刷新，每个玩家各自独立计数。
 *
 * <p>窗口按<b>玩家首次消费该商品的那一刻</b>开窗（存该 gameTime 为「锚点」），到锚点+周期长度才关窗刷新——
 * 而不是按服务器主世界 gameTime 整除对齐的全局固定窗口。玩家一直没买过/一直是满额度，压根不会开窗，
 * 自然也没有"倒计时"这回事（见反馈：满额度不该白白计量刷新）。玩家账户（{@link WalletAccountAPI}）
 * 只存「当前窗口锚点 gameTime + 该窗口内已用次数」两个值，锚点为空或已过期（gameTime 超出锚点+周期）
 * 就视为已用次数清零、窗口未开——不需要任何定时任务主动重置。</p>
 */
public final class ShopPeriodLimiter {

    private ShopPeriodLimiter() {}

    private static long currentGameTime(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null ? server.overworld().getGameTime() : 0L;
    }

    /** 该玩家当前窗口内已用次数；窗口未开或已过期返回 0（不会误读上一窗口的残留值）。 */
    private static long usedInActiveWindow(ServerPlayer player, MinecraftServer server, ShopEntry entry, String key) {
        long anchor = WalletAccountAPI.getPeriodWindow(server, player.getUUID(), key);
        if (anchor < 0L) return 0L;
        long gameTime = currentGameTime(player);
        boolean windowActive = gameTime - anchor < entry.getPeriodTicks();
        return windowActive ? WalletAccountAPI.getPeriodUsed(server, player.getUUID(), key) : 0L;
    }

    /** 该玩家在当前周期窗口内还能用的额度；非周期限购商品返回 {@link Long#MAX_VALUE}（不夹）。 */
    public static long remaining(ServerPlayer player, ShopEntry entry, String key) {
        if (!entry.isPeriodLimited() || player == null) return Long.MAX_VALUE;
        MinecraftServer server = player.getServer();
        if (server == null) return Long.MAX_VALUE;
        return Math.max(0L, entry.getPeriodLimit() - usedInActiveWindow(player, server, entry, key));
    }

    /** 把请求次数夹到不超过当前周期窗口剩余额度（非周期限购商品原样返回）。 */
    public static long clamp(ServerPlayer player, ShopEntry entry, String key, long times) {
        if (!entry.isPeriodLimited() || times <= 0L) return times;
        return Math.min(times, remaining(player, entry, key));
    }

    /**
     * 成交后记账：窗口未开/已过期就在这一刻开新窗口（锚点=当前 gameTime），否则原地累加。
     * 调用方需确保 amount ≤ {@link #clamp} 夹过的额度。
     */
    public static void consume(ServerPlayer player, ShopEntry entry, String key, long amount) {
        if (!entry.isPeriodLimited() || amount <= 0L || player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        long anchor = WalletAccountAPI.getPeriodWindow(server, player.getUUID(), key);
        long gameTime = currentGameTime(player);
        boolean windowActive = anchor >= 0L && gameTime - anchor < entry.getPeriodTicks();
        long usedInWindow = windowActive ? WalletAccountAPI.getPeriodUsed(server, player.getUUID(), key) : 0L;
        long newAnchor = windowActive ? anchor : gameTime;
        WalletAccountAPI.setPeriodState(server, player.getUUID(), key, newAnchor, usedInWindow + amount);
    }
}
