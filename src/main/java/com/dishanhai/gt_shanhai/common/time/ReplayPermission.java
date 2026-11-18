// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time;

/** Resolves command authority against the real player when one issued the command. */
public final class ReplayPermission {
    private ReplayPermission() {}
    public static boolean allowed(boolean playerSource, boolean playerAuthorized, boolean sourceAuthorized) {
        return playerSource ? playerAuthorized : sourceAuthorized;
    }
}
