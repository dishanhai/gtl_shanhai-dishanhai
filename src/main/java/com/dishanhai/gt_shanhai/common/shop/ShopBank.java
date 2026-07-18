package com.dishanhai.gt_shanhai.common.shop;

import java.math.BigInteger;

/**
 * 星火存款 / 贷款利息计算（山海署名，纯函数）。线性单利：本金 × 费率(基点/小时) × 经过小时数 / 10000。
 *
 * <p>没有独立的 tick 调度器——任何一次存/取/借/还/查询操作（见 {@link WalletAccountAPI} 的
 * {@code bank*} 系列方法）都会先把上次结算到现在这段时间的利息折进本金、刷新计息起点，再执行本次操作，
 * 跟 {@link ShopEntry} 限时折扣按 {@code System.currentTimeMillis()} 惰性判定的风格一致。</p>
 */
public final class ShopBank {
    private ShopBank() {}

    private static final long MS_PER_HOUR = 3_600_000L;

    /**
     * 按经过的毫秒数 + 每小时计息基点，算出这段时间应计的利息。
     * 不足 1 小时的零头留到下次一起结算（避免频繁操作把利息拆得过碎、也避免除法精度损耗）。
     * @return 本金为 0/费率非正/不足 1 小时 → 0
     */
    public static BigInteger accrue(BigInteger principal, long rateBpPerHour, long elapsedMs) {
        if (principal == null || principal.signum() <= 0 || rateBpPerHour <= 0L || elapsedMs <= 0L) {
            return BigInteger.ZERO;
        }
        long elapsedHours = elapsedMs / MS_PER_HOUR;
        if (elapsedHours <= 0L) return BigInteger.ZERO;
        return principal.multiply(BigInteger.valueOf(rateBpPerHour))
                .multiply(BigInteger.valueOf(elapsedHours))
                .divide(BigInteger.valueOf(10_000L));
    }
}
