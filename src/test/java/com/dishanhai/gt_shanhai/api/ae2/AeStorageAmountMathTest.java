package com.dishanhai.gt_shanhai.api.ae2;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AeStorageAmountMathTest {

    @Test
    void saturatesWhenTwoInfiniteSourcesAreMerged() {
        assertEquals(Long.MAX_VALUE,
                AeStorageAmountMath.saturatedAdd(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void saturatesWhenFiniteAmountIsAddedToAnInfiniteSource() {
        assertEquals(Long.MAX_VALUE,
                AeStorageAmountMath.saturatedAdd(Long.MAX_VALUE, 2_400_000_000_000L));
    }

    @Test
    void invalidNegativeContributionCannotEraseExistingInventory() {
        assertEquals(17L, AeStorageAmountMath.saturatedAdd(17L, -2L));
    }

    @Test
    void updatesBigIntegerTotalFromSuccessfulInsertWithoutScanningAllEntries() throws Exception {
        BigInteger total = new BigInteger("1000000000000000000000000000000");
        assertEquals(total.add(BigInteger.valueOf(27L)),
                invokeBigIntegerMath("afterBigIntegerInsert", total, 27L));
    }

    @Test
    void updatesBigIntegerTotalFromPartialAndFullExtraction() throws Exception {
        BigInteger total = BigInteger.valueOf(100L);
        BigInteger stored = BigInteger.valueOf(30L);

        assertEquals(BigInteger.valueOf(90L),
                invokeBigIntegerMath("afterBigIntegerExtract", total, stored, 10L));
        assertEquals(BigInteger.valueOf(70L),
                invokeBigIntegerMath("afterBigIntegerExtract", total, stored, 50L));
    }

    private static BigInteger invokeBigIntegerMath(String methodName, Object... arguments) throws Exception {
        Class<?>[] parameterTypes = new Class<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            parameterTypes[i] = arguments[i] instanceof Long ? long.class : BigInteger.class;
        }
        Method method = AeStorageAmountMath.class.getMethod(methodName, parameterTypes);
        return (BigInteger) method.invoke(null, arguments);
    }
}
