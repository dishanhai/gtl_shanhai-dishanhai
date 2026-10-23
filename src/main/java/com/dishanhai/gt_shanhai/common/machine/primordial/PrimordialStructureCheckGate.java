package com.dishanhai.gt_shanhai.common.machine.primordial;

import java.util.concurrent.atomic.AtomicReference;

/** 山海：跨異步探測與主執行緒成型的單一待辦；舊任務不得清除新任務。 */
final class PrimordialStructureCheckGate {
    private final AtomicReference<Object> pending = new AtomicReference<>();

    Object tryStart() {
        Object ticket = new Object();
        return pending.compareAndSet(null, ticket) ? ticket : null;
    }

    boolean isCurrent(Object ticket) {
        return ticket != null && pending.get() == ticket;
    }

    void finish(Object ticket) {
        pending.compareAndSet(ticket, null);
    }

    void cancel() {
        pending.set(null);
    }
}
