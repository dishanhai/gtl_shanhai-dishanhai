package com.dishanhai.gt_shanhai.common.shop;

import java.math.BigInteger;

/**
 * 币种兑成星火的单价（山海署名）。
 *
 * <p>面值仍由 {@link CurrencyRateConfig} 的阶梯决定（铜币=1，往上每档 ×4）。
 * 白金及以下（面值 ≤ {@link #FULL_RATE_FACE}）1 面值 = 1 星火。
 * 再高一档，兑星火价改为面值的 90%，之后每档再乘 90%，最低保留面值的 {@link #MIN_PERCENT}%。
 * 币种互换用同一套兑星火价，避免先拆成铜币再转星火绕过折损。
 * 能不能换，另看 {@link CurrencyStageConfig} 的阶段提交，和商店买该阶段商品是同一把锁。</p>
 */
public final class SparkMint {

    /** 铂金币面值。此值及以下原价兑星火。 */
    public static final long FULL_RATE_FACE = 256L;
    /** 高阶币兑星火价相对面值的下限，百分比。 */
    public static final int MIN_PERCENT = 50;

    private SparkMint() {}

    /** 一枚该面值货币能换到的星火。面值 ≤0 返回 0。 */
    public static long perCoin(long face) {
        if (face <= 0L) return 0L;
        if (face <= FULL_RATE_FACE) return face;
        int steps = 0;
        long scaled = face / FULL_RATE_FACE;
        while (scaled >= 4L && steps < 64) {
            scaled /= 4L;
            steps++;
        }
        int percent = 100;
        for (int i = 0; i < steps; i++) {
            percent = percent * 9 / 10;
            if (percent <= MIN_PERCENT) {
                percent = MIN_PERCENT;
                break;
            }
        }
        return BigInteger.valueOf(face).multiply(BigInteger.valueOf(percent))
                .divide(BigInteger.valueOf(100))
                .longValueExact();
    }

    /** {@code coins} 枚该面值货币一共能换到的星火。 */
    public static BigInteger total(long face, BigInteger coins) {
        if (face <= 0L || coins == null || coins.signum() <= 0) return BigInteger.ZERO;
        return coins.multiply(BigInteger.valueOf(perCoin(face)));
    }
}
