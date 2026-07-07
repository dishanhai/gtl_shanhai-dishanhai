package com.dishanhai.gt_shanhai.api.ftbq;

import dev.ftb.mods.ftbquests.quest.TeamData;

import java.util.Map;
import java.util.WeakHashMap;

public final class FtbqCacheRevision {

    private static final Map<TeamData, Long> TEAM_REVISIONS = new WeakHashMap<>();

    private FtbqCacheRevision() {}

    public static synchronized long get(TeamData teamData) {
        Long revision = TEAM_REVISIONS.get(teamData);
        return revision == null ? 0L : revision.longValue();
    }

    public static synchronized void bump(TeamData teamData) {
        if (teamData == null) {
            return;
        }
        TEAM_REVISIONS.put(teamData, Long.valueOf(get(teamData) + 1L));
    }
}
