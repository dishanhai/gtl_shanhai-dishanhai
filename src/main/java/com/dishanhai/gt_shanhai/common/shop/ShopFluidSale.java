package com.dishanhai.gt_shanhai.common.shop;

import java.util.function.LongUnaryOperator;

/** Converts an AE extraction into whole sale units and returns any incomplete unit. */
final class ShopFluidSale {
    record Result(long sold, long unreturned) {}

    static Result extract(long units, int perUnit, LongUnaryOperator extract, LongUnaryOperator restore) {
        if (units <= 0 || perUnit <= 0) return new Result(0, 0);
        long requested = Math.min(units, Long.MAX_VALUE / perUnit) * perUnit;
        long extracted = Math.max(0, Math.min(requested, extract.applyAsLong(requested)));
        long remainder = extracted % perUnit;
        long returned = remainder == 0 ? 0 : Math.max(0, Math.min(remainder, restore.applyAsLong(remainder)));
        return new Result(extracted / perUnit, remainder - returned);
    }
}
