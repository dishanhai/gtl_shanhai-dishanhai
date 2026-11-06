package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import java.util.List;
import java.util.UUID;

/** 山海：玩家清單改變時仍能安全地選擇下一個成員。 */
final class StellarCloudMemberSelection {
    static UUID next(List<UUID> candidates, UUID current) {
        if (candidates.isEmpty()) return null;
        int index = current == null ? -1 : candidates.indexOf(current);
        return candidates.get((index + 1) % candidates.size());
    }

    private StellarCloudMemberSelection() {}
}
