package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import appeng.api.networking.IManagedGridNode;

/** 山海：由明確的玩家操作補綁舊星律，絕不借用主庫身分或覆寫已有節點身分。 */
public final class StellarCloudAEIdentity {
    public static boolean bindUnowned(IManagedGridNode managed, int playerId) {
        var node = managed.getNode();
        if (playerId < 0 || node == null || node.getOwningPlayerId() != -1) return false;
        managed.setOwningPlayerId(playerId);
        return true;
    }

    private StellarCloudAEIdentity() {}
}
