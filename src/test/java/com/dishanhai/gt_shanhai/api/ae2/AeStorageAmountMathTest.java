package com.dishanhai.gt_shanhai.api.ae2;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AeStorageAmountMathTest {

    /**
     * 直达通道解析失败时 addSaturated 会静默回退双查找慢路径，行为测试照样全绿——
     * 只有这里能暴露 AE2 升级把 KeyCounter.lists / VariantCounter.records 改名的退化。
     */
    @Test
    void directRecordsChannelResolvesAgainstCurrentAe2() throws Exception {
        for (String fieldName : new String[] {
                "KEY_COUNTER_LISTS_GETTER", "UNORDERED_RECORDS_GETTER", "FUZZY_RECORDS_GETTER" }) {
            Field field = AeStorageAmountMath.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            assertNotNull(field.get(null), fieldName + " 未解析：单查找直达通道已退化为每 key 双哈希查找");
        }
    }

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
