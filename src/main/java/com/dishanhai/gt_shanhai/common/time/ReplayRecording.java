// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time;

/** Per-server-session recording switch. Call reset when the server stops. */
public final class ReplayRecording {
    private static volatile boolean enabled;
    private ReplayRecording() {}
    public static boolean isEnabled() { return enabled; }
    public static void start() { enabled = true; }
    public static void stop() { enabled = false; }
    public static void reset() { enabled = false; }
}
