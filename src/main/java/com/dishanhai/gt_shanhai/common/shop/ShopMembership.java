package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 付费会员档位（山海署名）：玩家在「会员中心」（见 {@code ShopMembershipScreen}）花星火直接购买/升级，
 * 永久买断制——买了就一直生效，不会自动降级/过期，也不从历史消费推算（原「累计消费自动解锁」方案
 * 已按反馈改为纯购买制，见 {@link WalletAccountAPI#buyMemberTier}）。
 *
 * <p>纯静态表 + 查表函数：跟档位相关的价格/折扣/名称只在这一处维护，服务端结算
 * （{@link ShopPurchase#affordAndDeduct}）和客户端预览/购买界面共用同一份数据，不会读岔。</p>
 */
public final class ShopMembership {
    private ShopMembership() {}

    /** 各档位售价（星火），下标即档位序号（0=青铜/1=白银/2=黄金）。永久买断，直接付目标档位全价。 */
    private static final long[] PRICES = {1_000_000L, 10_000_000L, 100_000_000L};
    /** 各档位折扣百分比，下标对齐 {@link #PRICES}。 */
    private static final int[] DISCOUNTS = {2, 5, 10};
    /** 各档位名称，下标对齐 {@link #PRICES}。 */
    public static final String[] TIER_NAMES = {"青铜", "白银", "黄金"};

    /** 档位总数。 */
    public static int tierCount() {
        return PRICES.length;
    }

    /** 档位售价（星火）；越界返回 0。 */
    public static long priceOf(int tier) {
        return tier >= 0 && tier < PRICES.length ? PRICES[tier] : 0L;
    }

    /** 档位折扣百分比（tier<0 视为未购买任何档位，返回 0）。 */
    public static int discountPercentForTier(int tier) {
        return tier >= 0 && tier < DISCOUNTS.length ? DISCOUNTS[tier] : 0;
    }

    /** 档位名称（tier<0 返回空串）。 */
    public static String tierNameForTier(int tier) {
        return tier >= 0 && tier < TIER_NAMES.length ? TIER_NAMES[tier] : "";
    }

    /** 服务端：该玩家当前会员档位对应的折扣百分比（未购买任何档位为 0）。 */
    public static int discountPercent(MinecraftServer server, UUID uuid) {
        return discountPercentForTier(WalletAccountAPI.getMemberTier(server, uuid));
    }
}
