package com.dishanhai.gt_shanhai.common.machine.part;

import org.jetbrains.annotations.Nullable;

/**
 * 星律样板下发期间的 AE 下单者上下文。
 * <p>
 * AE/量子 CPU 都在服务端线程同步调用 provider.pushPattern；星律只在该调用栈内读取一次，
 * 用完立即清理，避免污染同 tick 其他样板下发。
 */
public final class StellarPatternCraftingContext {

    private static final ThreadLocal<Integer> AE_PLAYER_ID = new ThreadLocal<>();

    private StellarPatternCraftingContext() {}

    public static void push(@Nullable Integer aePlayerId) {
        if (aePlayerId == null) {
            AE_PLAYER_ID.remove();
        } else {
            AE_PLAYER_ID.set(aePlayerId);
        }
    }

    public static void pop() {
        AE_PLAYER_ID.remove();
    }

    @Nullable
    public static Integer currentAePlayerId() {
        return AE_PLAYER_ID.get();
    }
}
