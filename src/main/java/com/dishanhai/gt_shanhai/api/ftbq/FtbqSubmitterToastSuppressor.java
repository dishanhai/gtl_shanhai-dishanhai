package com.dishanhai.gt_shanhai.api.ftbq;

public final class FtbqSubmitterToastSuppressor {
    private static final ThreadLocal<Boolean> SUPPRESSING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private FtbqSubmitterToastSuppressor() {
    }

    public static boolean isSuppressing() {
        return SUPPRESSING.get().booleanValue();
    }

    public static void runSuppressed(Runnable action) {
        boolean previous = SUPPRESSING.get().booleanValue();
        SUPPRESSING.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            SUPPRESSING.set(Boolean.valueOf(previous));
        }
    }
}
