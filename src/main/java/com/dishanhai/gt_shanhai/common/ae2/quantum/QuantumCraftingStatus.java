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
        long pendingOutput) {

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
