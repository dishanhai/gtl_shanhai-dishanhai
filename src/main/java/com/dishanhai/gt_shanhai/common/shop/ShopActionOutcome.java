package com.dishanhai.gt_shanhai.common.shop;

/** 一次买卖动作的成交结果分类，随 {@code ShopActionResultPacket} 推回客户端驱动按钮闪色。 */
public enum ShopActionOutcome {
    SUCCESS, PARTIAL, FAILED;

    /** {@code done<=0} 未成交；{@code done>=requested} 全部成交；其余为部分成交。 */
    public static ShopActionOutcome classify(long requested, long done) {
        if (done <= 0L) return FAILED;
        if (done >= requested) return SUCCESS;
        return PARTIAL;
    }
}
