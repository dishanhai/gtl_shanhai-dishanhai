package com.dishanhai.gt_shanhai.common.debug;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldLoadMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger("gt_shanhai:world_load_monitor");
    private static final Map<String, Long> TIMINGS = new ConcurrentHashMap<>();
    private static volatile boolean enabled;

    private WorldLoadMonitor() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        WorldLoadMonitor.enabled = enabled;
        if (!enabled) {
            reset();
        }
    }

    public static void startTiming(String key) {
        if (!enabled || key == null) return;
        TIMINGS.put(key, System.nanoTime());
    }

    public static void endTiming(String key) {
        if (!enabled || key == null) return;
        Long start = TIMINGS.remove(key);
        if (start == null) return;
        long elapsedMs = (System.nanoTime() - start) / 1000000L;
        if (elapsedMs >= 50L) {
            LOGGER.info("[slow] key={} elapsedMs={}", key, elapsedMs);
        }
    }

    public static void reset() {
        TIMINGS.clear();
    }

    public static void printReport(ServerPlayer player) {
        String message = "WorldLoadMonitor enabled=" + enabled + ", activeTimings=" + TIMINGS.size();
        LOGGER.info("[report] {}", message);
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
