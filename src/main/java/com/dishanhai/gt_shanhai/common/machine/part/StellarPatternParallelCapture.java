package com.dishanhai.gt_shanhai.common.machine.part;

/** 將配方輸入助手中的成功扣料結果限定在目前主機的配方查找呼叫內。 */
public final class StellarPatternParallelCapture {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private StellarPatternParallelCapture() {}

    public static Scope begin(Object host, StellarPatternParallelState state) {
        Scope scope = new Scope(host, state, CURRENT.get());
        state.gtShanhai$recordAllocatedParallel(0L);
        CURRENT.set(scope);
        return scope;
    }

    public static boolean isObserving(Object host) {
        Scope scope = CURRENT.get();
        return scope != null && scope.host == host;
    }

    public static void record(Object host, long parallel, boolean accepted) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.host != host || !accepted || parallel <= 0L) return;
        long current = Math.max(0L, scope.state.gtShanhai$getObservedAllocatedParallel());
        scope.state.gtShanhai$recordAllocatedParallel(parallel > Long.MAX_VALUE - current
                ? Long.MAX_VALUE : current + parallel);
    }

    public static final class Scope implements AutoCloseable {
        private final Object host;
        private final StellarPatternParallelState state;
        private final Scope previous;
        private boolean closed;

        private Scope(Object host, StellarPatternParallelState state, Scope previous) {
            this.host = host;
            this.state = state;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (CURRENT.get() == this) {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            }
        }
    }
}
