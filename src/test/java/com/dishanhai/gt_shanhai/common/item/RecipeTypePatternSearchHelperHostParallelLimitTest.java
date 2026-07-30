package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeTypePatternSearchHelperHostParallelLimitTest {

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
    void clampsNonPositiveValuesToOne() {
        assertEquals(1L, RecipeTypePatternSearchHelper.resolveHostParallelLimit(new MachineWithZeroes()));
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
