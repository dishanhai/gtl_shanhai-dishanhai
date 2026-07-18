package com.dishanhai.gt_shanhai.client.shop;

import java.math.BigInteger;

/**
 * 客户端「山海银行」存款/欠款预览缓存（山海署名，仅客户端）：{@code ShopBankQueryPacket} 推来的
 * 惰性结息快照，供 {@code ShopMembershipScreen} 显示。未同步过返回 null（界面按"查询中"处理，不瞎猜 0）。
 */
public final class ClientShopBank {

    private static BigInteger deposit;
    private static BigInteger debt;

    private ClientShopBank() {}

    public static void apply(BigInteger newDeposit, BigInteger newDebt) {
        deposit = newDeposit == null ? BigInteger.ZERO : newDeposit;
        debt = newDebt == null ? BigInteger.ZERO : newDebt;
    }

    /** 定期存款本息（未同步过返回 null）。 */
    public static BigInteger getDeposit() {
        return deposit;
    }

    /** 贷款欠款本息（未同步过返回 null）。 */
    public static BigInteger getDebt() {
        return debt;
    }
}
