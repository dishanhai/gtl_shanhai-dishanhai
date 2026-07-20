package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class UniformOutputMultiplierTest {

    @Test
    void detectsTenTimesOutputsWithoutDependingOnOrder() {
        Map<String, Long> base = amounts("钐粉", 1024L, "钕粉", 1024L, "铈粉", 1024L);
        Map<String, Long> multiplied = amounts("铈粉", 10240L, "钐粉", 10240L, "钕粉", 10240L);

        assertEquals(10L, UniformOutputMultiplier.detect(base, multiplied, Set.of()));
    }

    @Test
    void cycleContainerOutputsRemainUnscaled() {
        Map<String, Long> base = amounts("产物", 4L, "循环容器", 1L);
        Map<String, Long> multiplied = amounts("循环容器", 1L, "产物", 400L);

        assertEquals(100L, UniformOutputMultiplier.detect(base, multiplied, Set.of("循环容器")));
    }

    @Test
    void rejectsMixedMultipliersAndDifferentOutputKeys() {
        Map<String, Long> base = amounts("甲", 8L, "乙", 16L);

        assertEquals(0L, UniformOutputMultiplier.detect(
                base, amounts("甲", 80L, "乙", 320L), Set.of()));
        assertEquals(0L, UniformOutputMultiplier.detect(
                base, amounts("甲", 80L, "丙", 160L), Set.of()));
    }

    @Test
    void exactOutputsReportOneAndInvalidAmountsAreRejected() {
        assertEquals(1L, UniformOutputMultiplier.detect(
                amounts("甲", 8L), amounts("甲", 8L), Set.of()));
        assertEquals(0L, UniformOutputMultiplier.detect(
                amounts("甲", 0L), amounts("甲", 8L), Set.of()));
    }

    private static Map<String, Long> amounts(Object... values) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], (Long) values[i + 1]);
        }
        return result;
    }
}
