package com.dishanhai.gt_shanhai.common.item.terminal;

import net.minecraft.network.chat.Component;

public record StellarTerminalDetail(
        long serverId,
        Component name,
        Component host,
        int x,
        int y,
        int z,
        int filledPatterns,
        int patternCapacity,
        int wildcardMothers,
        int wildcardExpanded,
        boolean multiplierEnabled,
        long multiplier,
        int circuit,
        int warnings,
        boolean online,
        int hostCount,
        boolean hostFormed,
        int hostX,
        int hostY,
        int hostZ,
        String hostStatus,
        String hostRecipeTypes,
        String stuckBroadcast,
        int proxyCount,
        String proxyLines) {
}
