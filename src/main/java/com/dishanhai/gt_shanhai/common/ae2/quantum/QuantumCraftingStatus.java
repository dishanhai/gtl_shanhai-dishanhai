package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.stacks.AEKey;

import org.jetbrains.annotations.Nullable;

/** 量子 CPU 中某个合成产物当前的实际调度状态。 */
public record QuantumCraftingStatus(
        State state,
        @Nullable AEKey blockingInput,
        long availableInput,
        long requiredInputPerPattern,
        long runnablePatterns,
        long remainingPatterns,
        long waitingForInput,
        long pendingInput,
        long waitingForOutput,
        long pendingOutput,
        /** 阻塞原料在 ME 网络存储里现存的数量（不含本 CPU 内部库存）。 */
        long networkAvailableInput,
        /** 本项配方的样板提供器总数。 */
        int providerCount,
        /** 本项配方中当前空闲的样板提供器数量。 */
        int freeProviderCount,
        /** 阻塞原料是否还有任何样板能把它做出来。 */
        boolean blockingInputCraftable) {

    /** 还差多少阻塞原料才能把剩余份数全部做完；无阻塞原料时为 0。 */
    public long shortfallInput() {
        if (blockingInput == null || requiredInputPerPattern <= 0L || remainingPatterns <= 0L) return 0L;
        long needed = saturatedMultiply(requiredInputPerPattern, remainingPatterns);
        return Math.max(0L, needed - Math.min(needed, availableInput));
    }

    /**
     * 是否已经死锁：卡在某个原料上，但上游既没有在返还、也没有未发计划，
     * 网络里没有现货，而且这个原料根本没有任何样板能做出来。
     */
    public boolean deadlocked() {
        return blockingInput != null
                && runnablePatterns <= 0L
                && waitingForInput <= 0L
                && pendingInput <= 0L
                && networkAvailableInput <= 0L
                && !blockingInputCraftable;
    }

    /** 网络里有现货但 CPU 没抽进来，玩家可以手动「重发配」补救。 */
    public boolean recoverableByRedispatch() {
        return blockingInput != null && runnablePatterns <= 0L && networkAvailableInput > 0L;
    }

    private static long saturatedMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) return 0L;
        long result = a * b;
        return result / a != b ? Long.MAX_VALUE : result;
    }

    public enum State {
        WAITING_UPSTREAM,
        MISSING_INPUT,
        NO_PROVIDER,
        PROVIDER_BUSY,
        READY_TO_DISPATCH,
        WAITING_MACHINE,
        PLANNED,
        INVALID_PATTERN;

        public static State fromNetworkId(int id) {
            State[] values = values();
            return id >= 0 && id < values.length ? values[id] : PLANNED;
        }
    }
}
