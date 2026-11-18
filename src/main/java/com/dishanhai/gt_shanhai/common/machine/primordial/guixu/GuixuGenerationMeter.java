package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import java.math.BigInteger;

/** Records only committed output transactions, without changing their execution. */
public final class GuixuGenerationMeter {
    private long tick = Long.MIN_VALUE;
    private BigInteger amount = BigInteger.ZERO;
    private long previousTick = Long.MIN_VALUE;
    private BigInteger previousAmount = BigInteger.ZERO;

    public void record(long gameTick, BigInteger committed) {
        if (committed == null || committed.signum() <= 0) return;
        if (gameTick != tick) {
            previousTick = tick;
            previousAmount = amount;
            tick = gameTick;
            amount = BigInteger.ZERO;
        }
        amount = amount.add(committed);
    }

    public BigInteger completedTick(long gameTick) {
        if (tick == gameTick - 1) return amount;
        if (previousTick == gameTick - 1) return previousAmount;
        return BigInteger.ZERO;
    }
}
