package com.dishanhai.gt_shanhai.client.gui.scaled;

import java.util.HashMap;
import java.util.Map;

/**
 * 界面音效按频道节流：同一频道两次播放至少隔 {@code gapMs}，被拦下的那次不重置计时。
 * 连发、连点时只响一部分，避免叠成一片噪音。不依赖 Minecraft，方便单测。
 */
public final class UiSoundThrottle {

    private final Map<String, Long> lastPlayedMs = new HashMap<>();

    /** 放行就登记这次播放并返回 true；还在间隔内返回 false。 */
    public boolean allow(String channel, long gapMs, long nowMs) {
        Long prev = lastPlayedMs.get(channel);
        if (prev != null && nowMs - prev < gapMs) return false;
        lastPlayedMs.put(channel, nowMs);
        return true;
    }

    public void reset() {
        lastPlayedMs.clear();
    }
}
