package com.dishanhai.gt_shanhai.jei;

import java.math.BigInteger;

/**
 * JEI 收藏数量判定用的 long/BigInteger 边界解析器。
 *
 * <p>AE2 的库存接口只能返回 {@code long}，而无限元件或聚合存储可能以
 * {@code Long.MAX_VALUE}（或其以上被截断后的高位值）表示「实际上足够」。
 * 统一在这里钳制和比较，避免加法回绕把高库存误报为缺料。</p>
 */
public final class JeiBookmarkAmountParser {

    /** AE2 long 饱和数量达到此值时，收藏检查不再把它视为缺料。 */
    public static final long EFFECTIVELY_HIGH_STORAGE = Long.MAX_VALUE / 2L;

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private JeiBookmarkAmountParser() {
    }

    /** 把 long 数量归一化为非负、不会溢出的 AE 数量。 */
    public static long parse(long value) {
        return value <= 0L ? 0L : value;
    }

    /** 把可能超过 AE2 long 范围的精确数量钳制到 AE2 可表达范围。 */
    public static long parse(BigInteger value) {
        if (value == null || value.signum() <= 0) return 0L;
        return value.compareTo(LONG_MAX) >= 0 ? Long.MAX_VALUE : value.longValue();
    }

    /**
     * 解析附加诊断/配置值，支持数字、{@code Long.MAX_VALUE}、
     * {@code Long.MAX_VALUE / 2} 和 {@code 1/2Long.MAX_VALUE}。
     */
    public static long parse(Object value) {
        if (value instanceof BigInteger bigInteger) return parse(bigInteger);
        if (value instanceof Number number) return parse(number.longValue());
        if (value == null) return 0L;

        String text = value.toString().trim().replace("_", "").replace(",", "");
        if (text.isEmpty()) return 0L;
        String compact = text.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        if ("LONG.MAX.VALUE".equals(compact)
                || "LONG.MAX_VALUE".equals(compact)
                || "LONG.MAXVALUE".equals(compact)) {
            return Long.MAX_VALUE;
        }
        if ("LONG.MAX.VALUE/2".equals(compact)
                || "LONG.MAX_VALUE/2".equals(compact)
                || "LONG.MAXVALUE/2".equals(compact)
                || "1/2LONG.MAX.VALUE".equals(compact)
                || "1/2LONG.MAX_VALUE".equals(compact)
                || "1/2LONG.MAXVALUE".equals(compact)) {
            return EFFECTIVELY_HIGH_STORAGE;
        }
        try {
            return parse(new BigInteger(text));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * 高储量直接视为已足够；否则才进行普通的 {@code available >= required} 比较。
     */
    public static boolean isEffectivelyEnough(long available, long required) {
        long normalizedAvailable = parse(available);
        long normalizedRequired = parse(required);
        return normalizedAvailable >= EFFECTIVELY_HIGH_STORAGE
                || normalizedAvailable >= normalizedRequired;
    }
}
