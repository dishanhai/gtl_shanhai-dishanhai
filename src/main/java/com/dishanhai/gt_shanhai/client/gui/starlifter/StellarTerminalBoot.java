package com.dishanhai.gt_shanhai.client.gui.starlifter;

/** Client presentation clock. Only the current device and its verified owner are retained. */
final class StellarTerminalBoot {
    enum Policy { COLD, FAST, WARM, MODULE }

    enum Stage {
        DORMANT, TARGET_ACQUISITION, BRAND_REVEAL, TERMINAL_INIT, DEVICE_IDENTIFICATION, STRUCTURE_SCAN,
        TELEMETRY_CONNECT, TELEMETRY_SYNC, INTERFACE_MORPH, ONLINE, SUSPENDED, RESUMING, SHUTDOWN
    }

    record Device(long position, Object block, Object entity) {}

    static final int EXIT_MS = 135, MODULE_MS = 140, DETAILS_MS = 220, REDUCED_MS = 90;
    private static final long ACTIVE = Long.MAX_VALUE;

    private Device device;
    private long owner, incarnation, previousOwner, previousIncarnation;
    private long start, lostAt = ACTIVE, detailAt;
    private int duration, rail;
    private float startRail, inheritedLayout = 1;
    private boolean details, reduced, awaitingOwner;
    private Policy policy = Policy.COLD;

    Policy policyFor(Device next, long nextOwner, long nextIncarnation, long now, int cacheMs) {
        if (device == null || expired(now, cacheMs)) return Policy.COLD;
        if (device.equals(next) && (incarnation == 0 || nextIncarnation == 0
                || owner == nextOwner && incarnation == nextIncarnation)) {
            return lostAt == ACTIVE ? policy : Policy.WARM;
        }
        return sameOwner(owner, incarnation, nextOwner, nextIncarnation) ? Policy.MODULE : Policy.FAST;
    }

    void observe(Device next, long nextOwner, long nextIncarnation, long now, int cacheMs,
                 int coldMs, int fastMs, int warmMs, int coldRail, int fastRail, boolean reduced) {
        if (lostAt == ACTIVE && next.equals(device)
                && (nextIncarnation == 0 || incarnation == 0 || sameOwner(owner, incarnation, nextOwner, nextIncarnation))) {
            if (nextIncarnation != 0) {
                // An uncached module can be confirmed by a delayed reply after the transition starts.
                if (awaitingOwner && sameOwner(previousOwner, previousIncarnation, nextOwner, nextIncarnation)
                        && progress(now) < 1) {
                    startRail = space(now);
                    inheritedLayout = layout(now);
                    start = now;
                    duration = reduced ? REDUCED_MS : MODULE_MS;
                    rail = 0;
                    policy = Policy.MODULE;
                }
                awaitingOwner = false;
                owner = nextOwner;
                incarnation = nextIncarnation;
            }
            return;
        }

        inheritedLayout = layout(now);
        policy = policyFor(next, nextOwner, nextIncarnation, now, cacheMs);
        startRail = policy == Policy.COLD ? coldRail : space(now);
        previousOwner = owner;
        previousIncarnation = incarnation;
        awaitingOwner = policy == Policy.FAST && nextIncarnation == 0;
        boolean retainOwner = next.equals(device) && policy == Policy.WARM && nextIncarnation == 0;
        device = next;
        if (!retainOwner) {
            owner = nextOwner;
            incarnation = nextIncarnation;
        }
        start = now;
        lostAt = ACTIVE;
        this.reduced = reduced;
        rail = switch (policy) {
            case COLD -> coldRail;
            case FAST -> fastRail;
            default -> 0;
        };
        duration = reduced ? REDUCED_MS : switch (policy) {
            case COLD -> coldMs;
            case FAST -> fastMs;
            case WARM -> warmMs;
            case MODULE -> MODULE_MS;
        };
    }

    boolean current(Device next) { return next.equals(device) && lostAt == ACTIVE; }

    void lose(long now) {
        if (device != null && lostAt == ACTIVE) {
            lostAt = now;
            details = false;
        }
    }

    boolean expired(long now, int cacheMs) { return lostAt != ACTIVE && now - lostAt >= cacheMs; }

    void details(boolean open, long now) {
        if (open && !details) detailAt = now;
        details = open;
    }

    float detail(long now) { return details ? unit((now - detailAt) / (float) DETAILS_MS) : 1; }

    Stage stage(long now, boolean hasSample) {
        if (device == null) return Stage.DORMANT;
        if (lostAt != ACTIVE) return now - lostAt < EXIT_MS ? Stage.SHUTDOWN : Stage.SUSPENDED;
        float p = progress(now);
        if (p >= 1) return Stage.ONLINE;
        if (reduced || policy == Policy.WARM) return Stage.RESUMING;
        if (policy == Policy.MODULE) return p < .62f ? Stage.DEVICE_IDENTIFICATION : Stage.INTERFACE_MORPH;
        if (p < .12f) return Stage.TARGET_ACQUISITION;
        if (policy == Policy.COLD && p < .36f) return Stage.BRAND_REVEAL;
        if (policy == Policy.COLD && p < .57f) return Stage.TERMINAL_INIT;
        if (p < (policy == Policy.COLD ? .65f : .4f)) return Stage.DEVICE_IDENTIFICATION;
        if (p < (policy == Policy.COLD ? .73f : .58f)) return Stage.STRUCTURE_SCAN;
        if (p < .80f) return hasSample ? Stage.TELEMETRY_SYNC : Stage.TELEMETRY_CONNECT;
        return Stage.INTERFACE_MORPH;
    }

    Policy policy() { return policy; }

    float progress(long now) {
        if (device == null) return 1;
        return unit(((lostAt == ACTIVE ? now : lostAt) - start) / (float) duration);
    }

    float layout(long now) {
        float p = progress(now);
        return policy == Policy.COLD ? p : inheritedLayout + (1 - inheritedLayout) * p;
    }

    float space(long now) {
        float p = progress(now);
        float open = startRail + (rail - startRail) * window(p, 0, policy == Policy.FAST ? .11f : .24f);
        float close = lostAt == ACTIVE ? 1 : 1 - window((now - lostAt) / (float) EXIT_MS, 0, 1);
        return open * (1 - window(p, .78f, 1)) * close;
    }

    void reset() {
        device = null;
        lostAt = ACTIVE;
        details = false;
        startRail = rail = 0;
        inheritedLayout = 1;
    }

    static float window(float p, float from, float to) {
        float t = unit((p - from) / (to - from));
        return t * t * (3 - 2 * t);
    }

    private static float unit(float t) { return Math.clamp(t, 0, 1); }

    private static boolean sameOwner(long a, long aIncarnation, long b, long bIncarnation) {
        return aIncarnation != 0 && a == b && aIncarnation == bIncarnation;
    }
}
