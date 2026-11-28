package com.dishanhai.gt_shanhai.common.machine.starlifter;

/** The server publishes operating mode and a bounded reserve transition with independent clocks. */
public record StarLifterVisualState(Mode mode, long since, boolean resonance, int reserveLevel,
                                    float previousReserve, long reserveSince) {
    public enum Mode { IDLE, RUNNING, HIGH_LOAD, FAULT }

    public static final int STARTUP_TICKS = 64;
    public static final int FULL_RESERVE = 1024;
    private static final int RESERVE_TRANSITION_TICKS = 30;
    public static final StarLifterVisualState IDLE = new StarLifterVisualState(Mode.IDLE, 0, false);
    private static final Mode[] MODES = Mode.values();

    public StarLifterVisualState(Mode mode, long since, boolean resonance) {
        this(mode, since, resonance, FULL_RESERVE, 1, 0);
    }

    public static StarLifterVisualState decode(int mode, long since, boolean resonance,
                                               int reserveLevel, float previousReserve, long reserveSince) {
        return new StarLifterVisualState(mode >= 0 && mode < MODES.length ? MODES[mode] : Mode.IDLE,
                since, resonance, Math.clamp(reserveLevel, 0, FULL_RESERVE),
                Float.isFinite(previousReserve) ? Math.clamp(previousReserve, 0f, 1f) : 1f, reserveSince);
    }

    public StarLifterVisualState update(Mode next, long tick, boolean linked, int nextReserve) {
        // Load can change during a batch without restarting the ignition sequence.
        long start = next != mode && (!extracting(mode) || !extracting(next)) ? tick : since;
        boolean reserveChanged = nextReserve != reserveLevel;
        if (next == mode && linked == resonance && !reserveChanged) return this;
        return new StarLifterVisualState(next, start, linked, nextReserve,
                reserveChanged ? reserveFraction(tick) : previousReserve, reserveChanged ? tick : reserveSince);
    }

    public static int reserveLevel(long remaining, long capacity) {
        return (int) Math.round(Math.clamp(remaining / (double) capacity, 0, 1) * FULL_RESERVE);
    }

    public float reserveFraction(double tick) {
        float t = (float) Math.clamp((tick - reserveSince) / RESERVE_TRANSITION_TICKS, 0, 1);
        float target = (float) reserveLevel / FULL_RESERVE;
        return previousReserve + (target - previousReserve) * t * t * (3 - 2 * t);
    }

    public boolean extracting() {
        return extracting(mode);
    }

    public float ignition(double tick) {
        return extracting() ? (float) Math.clamp((tick - since) / STARTUP_TICKS, 0, 1) : 0;
    }

    private static boolean extracting(Mode mode) {
        return mode == Mode.RUNNING || mode == Mode.HIGH_LOAD;
    }
}
