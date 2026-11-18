// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

/** Coordinates replay persistence barriers between the server and storage threads. */
public final class ReplaySaveGuard {
    private enum State { LIVE, REPLAY, FAILED }
    private static State state = State.LIVE;
    private static boolean conflict;
    private ReplaySaveGuard() {}
    public static synchronized boolean block() { return state == State.FAILED; }
    public static synchronized void beginReplay() {
        if (state != State.LIVE) throw new IllegalStateException("Replay save guard is already active");
        conflict = false;
        state = State.REPLAY;
    }
    public static synchronized boolean hasSaveConflict() { return conflict; }
    public static synchronized boolean rejectSave() {
        if (state == State.LIVE) return false;
        if (state == State.REPLAY) conflict = true;
        return true;
    }
    public static synchronized boolean rejectRegionWrite() { return rejectSave(); }
    public static synchronized boolean releaseAfterSuccess() {
        if (state != State.REPLAY || conflict) return false;
        state = State.LIVE;
        return true;
    }
    public static synchronized void fail() { state = State.FAILED; }
    public static synchronized void reset() { state = State.LIVE; conflict = false; }
}
