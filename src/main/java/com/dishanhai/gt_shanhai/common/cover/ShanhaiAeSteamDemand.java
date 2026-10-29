package com.dishanhai.gt_shanhai.common.cover;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;

/** 山海：原生 EU→蒸汽倍率的精確計量；不持有 AE 或 Minecraft 狀態。 */
public final class ShanhaiAeSteamDemand {
    private ShanhaiAeSteamDemand() {}

    @FunctionalInterface
    public interface SteamStorage {
        long extract(long amount, boolean simulate);
    }

    /** 僅保留實扣不足一整筆時已付款的部分，防止重試重扣或吞汽。 */
    public static final class Account {
        private long credit;
        public Account(long credit) { this.credit = Math.max(0, credit); }
        public long credit() { return credit; }

        public long extract(SteamStorage storage, long requested, boolean simulate) {
            long prepaid = Math.min(credit, requested);
            long missing = requested - prepaid;
            long extracted = missing == 0 ? 0 : storage.extract(missing, simulate);
            if (simulate) return prepaid + extracted;
            if (extracted < missing) {
                credit += extracted;
                return 0;
            }
            credit -= prepaid;
            return requested;
        }
    }

    /** -1 表示非法倍率、負數或超過 long 可表示的單次需求。 */
    public static long requiredSteam(List<Long> energy, double rate) {
        if (!Double.isFinite(rate) || rate <= 0) return -1;
        BigInteger sum = BigInteger.ZERO;
        for (Long value : energy) {
            if (value == null || value < 0) return -1;
            sum = sum.add(BigInteger.valueOf(value));
        }
        BigDecimal demand = new BigDecimal(sum).multiply(BigDecimal.valueOf(rate))
                .setScale(0, RoundingMode.CEILING);
        return demand.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? -1 : demand.longValueExact();
    }

    public static long availableEnergy(long steam, double rate) {
        if (steam <= 0 || !Double.isFinite(rate) || rate <= 0) return 0;
        BigDecimal energy = BigDecimal.valueOf(steam).divide(BigDecimal.valueOf(rate), 0, RoundingMode.FLOOR);
        return energy.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    public static boolean consume(SteamStorage storage, long amount, boolean simulate) {
        if (amount < 0) return false;
        if (amount == 0) return true;
        if (storage.extract(amount, true) < amount) return false;
        return simulate || storage.extract(amount, false) == amount;
    }
}
