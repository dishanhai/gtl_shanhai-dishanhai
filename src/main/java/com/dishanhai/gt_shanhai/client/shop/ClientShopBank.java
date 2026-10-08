package com.dishanhai.gt_shanhai.client.shop;

import java.math.BigInteger;

/**
 * 客户端「山海银行」快照（山海署名，仅客户端）。未同步过 {@link #get()} 返回 null，
 * 界面显示「查询中」，不把缺省值当成真实余额。
 */
public final class ClientShopBank {

    /** 服务器结息后的银行快照。利率来自服务器，不读客户端自己的配置。 */
    public static final class Snapshot {
        public final BigInteger depositPrincipal;
        public final BigInteger depositInterest;
        public final BigInteger debtPrincipal;
        public final BigInteger debtInterest;
        public final BigInteger loanRoom;
        public final int depositRateBp;
        public final int loanRateBp;
        public final long maxLoan;

        public Snapshot(BigInteger depositPrincipal, BigInteger depositInterest,
                        BigInteger debtPrincipal, BigInteger debtInterest, BigInteger loanRoom,
                        int depositRateBp, int loanRateBp, long maxLoan) {
            this.depositPrincipal = nz(depositPrincipal);
            this.depositInterest = nz(depositInterest);
            this.debtPrincipal = nz(debtPrincipal);
            this.debtInterest = nz(debtInterest);
            this.loanRoom = nz(loanRoom);
            this.depositRateBp = depositRateBp;
            this.loanRateBp = loanRateBp;
            this.maxLoan = maxLoan;
        }

        public BigInteger depositTotal() {
            return depositPrincipal.add(depositInterest);
        }

        public BigInteger debtTotal() {
            return debtPrincipal.add(debtInterest);
        }
    }

    private static Snapshot snapshot;

    private ClientShopBank() {}

    /** 退出世界/换服时清空，避免跨存档展示旧账目。 */
    public static void clear() {
        snapshot = null;
    }

    public static void apply(Snapshot next) {
        snapshot = next;
    }

    /** 未同步过返回 null。 */
    public static Snapshot get() {
        return snapshot;
    }

    private static BigInteger nz(BigInteger value) {
        return value == null || value.signum() < 0 ? BigInteger.ZERO : value;
    }
}
