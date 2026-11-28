package com.dishanhai.gt_shanhai.common.machine.starlifter;

/** Boot cues shared by the terminal, observation pass and time-stretched sound track. */
public final class StarLifterBootTimeline {
    public static final int DURATION = 5600;
    private static final int CUE_DURATION = 4200;
    public static final int KERNEL = 300, CHECK = 590, ORBIT = 900, STELLAR = 1700;
    public static final int RESONANCE = 2500, IMAGING = 3100, HANDOFF = 3800;

    private StarLifterBootTimeline() {}

    public static float cueTime(float progress) { return progress * CUE_DURATION; }

    static int cueMs(int elapsedMs) { return elapsedMs * CUE_DURATION / DURATION; }

    static int playbackMs(int cueMs) { return Math.round(cueMs * (DURATION / (float) CUE_DURATION)); }

    enum Stage { WAKE, KERNEL, CHECK, ORBIT, STELLAR, RESONANCE, IMAGING, READY }

    static Stage stage(int ms) {
        if (ms >= HANDOFF) return Stage.READY;
        if (ms >= IMAGING) return Stage.IMAGING;
        if (ms >= RESONANCE) return Stage.RESONANCE;
        if (ms >= STELLAR) return Stage.STELLAR;
        if (ms >= ORBIT) return Stage.ORBIT;
        if (ms >= CHECK) return Stage.CHECK;
        return ms >= KERNEL ? Stage.KERNEL : Stage.WAKE;
    }

    static int ringStart(int ring) { return ORBIT + 125 * ring; }
    static int ringEnd(int ring) { return ringStart(ring) + 345; }

    public static float ringSweep(float ms, int ring) {
        return ramp(ms, ringStart(ring), ringEnd(ring));
    }

    public static float ringMaterial(float ms, int ring) {
        return ramp(ms, IMAGING + 85 * ring, IMAGING + 85 * ring + 410);
    }

    public static float starLight(float ms) { return ramp(ms, STELLAR, 2380); }

    private static float ramp(float ms, int start, int end) {
        return Math.clamp((ms - start) / (end - start), 0, 1);
    }

    enum Result {
        OK(0x79ceb0), READY(0x79ceb0), SYNCED(0x79ceb0), COMPUTING(0x8bbadf), PENDING(0x9399b9),
        STANDBY(0x9399b9), ACTIVE(0x79ceb0), UNBOUND(0xc4a5f2), OFFLINE(0xe8ae4d), DEFERRED(0x9399b9),
        WARNING(0xe8ae4d), FAILED(0xe87e87), SKIPPED(0x9399b9);

        final String label = "[" + name() + "]";
        final int ink;

        Result(int ink) { this.ink = ink; }
    }

    record MachineState(boolean ready, boolean formed, boolean unloaded, boolean work,
                        boolean active, boolean depleted, boolean suspended, String link) {
        Result structure() { return !ready ? Result.PENDING : formed ? Result.OK : unloaded ? Result.DEFERRED : Result.FAILED; }

        Result recipe() {
            if (!ready) return Result.PENDING;
            if (!formed || !work) return Result.SKIPPED;
            if (depleted || suspended) return Result.WARNING;
            return active ? Result.ACTIVE : Result.STANDBY;
        }

        Result resonance() {
            if (!ready) return Result.PENDING;
            return switch (link) {
                case "online", "working" -> Result.READY;
                case "unbound" -> Result.UNBOUND;
                case "unloaded" -> Result.DEFERRED;
                case "offline", "unavailable" -> Result.OFFLINE;
                default -> Result.PENDING;
            };
        }

        String online() {
            if (!ready) return "waiting";
            if (!formed) return unloaded ? "structure_wait" : "incomplete";
            if (!work) return "disabled";
            if (depleted) return "depleted";
            if (suspended) return "suspended";
            return active ? "active" : "awaiting";
        }
    }
}
