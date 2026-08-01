package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTypePatternSearchHelperHostParallelLimitTest {

    private static final Path SEARCH_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");

    @Test
    void prefersMachineRecipeLogicMaxParallelOverLogicTotal() {
        assertEquals(77L, RecipeTypePatternSearchHelper.resolveHostParallelLimit(new MachineWithTotalLimit()));
    }

    @Test
    void fallsBackToMachineMaxParallel() {
        assertEquals(9L, RecipeTypePatternSearchHelper.resolveHostParallelLimit(new MachineWithMaxParallel()));
    }

    @Test
    void ignoresLogicOnlyTotalParallelByDefault() {
        assertEquals(1L, RecipeTypePatternSearchHelper.resolveHostParallelLimit(new MachineWithOnlyLogicTotalLimit()));
    }

    @Test
    void treatsGtlcoreUnboundedDefaultAsOneParallel() {
        assertEquals(1L, RecipeTypePatternSearchHelper.resolveHostParallelLimit(new MachineWithUnboundedMaxParallel()));
    }

    @Test
    void treatsUnboundedRecipeLogicMaxAsOneParallel() {
        assertEquals(1L, RecipeTypePatternSearchHelper.resolveHostParallelLimit(new MachineWithUnboundedRecipeLogicMax()));
    }

    @Test
    void treatsHugeRecipeLogicMaxAsOneParallel() {
        assertEquals(1L,
                RecipeTypePatternSearchHelper.resolveHostParallelLimit(new MachineWithHugeRecipeLogicMax()));
    }

    @Test
    void fallsBackToMachineMaxParallelWhenRecipeLogicMaxIsUnbounded() {
        assertEquals(64L,
                RecipeTypePatternSearchHelper.resolveHostParallelLimit(new MachineWithUnboundedRecipeLogicMaxAndBoundedMaxParallel()));
    }

    @Test
    void clampsNonPositiveValuesToOne() {
        assertEquals(1L, RecipeTypePatternSearchHelper.resolveHostParallelLimit(new MachineWithZeroes()));
    }

    @Test
    void activeAeOrderInventoryIsNeverClampedToHostParallel() throws Exception {
        String source = Files.readString(SEARCH_HELPER);
        int methodStart = source.indexOf("private static void topUpVirtualSupply");
        int methodEnd = source.indexOf("\n    static long resolveHostParallelLimit", methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertFalse(method.contains("clampInventoryAmount("),
                "正常 AE pushPattern 已填入的整單原料必須保留；宿主並行只能限制主動補料量");
        assertTrue(method.indexOf("if (consumableStockPresent)") < method.indexOf("long hostParallelLimit"),
                "正常下單槽必須先返回，宿主並行上限只能進入主動虛擬抽料分支");
        assertTrue(method.contains("Math.min(remainingBudget, hostParallelLimit)"),
                "主動虛擬補料仍必須受宿主並行上限約束");
    }

    private static final class MachineWithTotalLimit {
        public Object getRecipeLogic() {
            return new LogicWithTotalLimit();
        }

        public long getRecipeLogicMaxParallel() {
            return 77L;
        }

        public int getMaxParallel() {
            return 9;
        }
    }

    private static final class LogicWithTotalLimit {
        protected long getTotalParallelLimit() {
            return 51L;
        }
    }

    private static final class MachineWithMaxParallel {
        public Object getRecipeLogic() {
            return new LogicWithTotalLimit();
        }

        public int getMaxParallel() {
            return 9;
        }
    }

    private static final class MachineWithOnlyLogicTotalLimit {
        public Object getRecipeLogic() {
            return new LogicWithTotalLimit();
        }
    }

    private static final class MachineWithUnboundedMaxParallel {
        public int getMaxParallel() {
            return Integer.MAX_VALUE;
        }
    }

    private static final class MachineWithUnboundedRecipeLogicMax {
        public long getRecipeLogicMaxParallel() {
            return Long.MAX_VALUE;
        }
    }

    private static final class MachineWithHugeRecipeLogicMax {
        public long getRecipeLogicMaxParallel() {
            return (long) Integer.MAX_VALUE + 1L;
        }
    }

    private static final class MachineWithUnboundedRecipeLogicMaxAndBoundedMaxParallel {
        public long getRecipeLogicMaxParallel() {
            return Long.MAX_VALUE;
        }

        public int getMaxParallel() {
            return 64;
        }
    }

    private static final class MachineWithZeroes {
        public Object getRecipeLogic() {
            return new LogicWithZeroTotalLimit();
        }

        public long getRecipeLogicMaxParallel() {
            return 0L;
        }

        public int getMaxParallel() {
            return 0;
        }
    }

    private static final class LogicWithZeroTotalLimit {
        protected long getTotalParallelLimit() {
            return 0L;
        }
    }
}
