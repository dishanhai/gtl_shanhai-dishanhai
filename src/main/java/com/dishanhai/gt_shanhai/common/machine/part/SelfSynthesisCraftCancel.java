package com.dishanhai.gt_shanhai.common.machine.part;

/**
 * 自合成仓收下发配后，标记本次合成调用结束时取消任务。
 * 不注入样板产物。
 */
public final class SelfSynthesisCraftCancel {

    private static final ThreadLocal<Boolean> MARKED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private SelfSynthesisCraftCancel() {}

    public static void begin() {
        MARKED.set(Boolean.FALSE);
    }

    public static void mark() {
        MARKED.set(Boolean.TRUE);
    }

    public static boolean finish() {
        boolean marked = Boolean.TRUE.equals(MARKED.get());
        MARKED.set(Boolean.FALSE);
        return marked;
    }
}
