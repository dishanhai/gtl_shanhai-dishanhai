package com.dishanhai.gt_shanhai.api.ae2;

import appeng.api.stacks.AEKey;

/**
 * StorageService 增量更新 Accessor
 * 放在 api 包而非 mixin 包，避免 Mixin 包隔离限制
 */
public interface IStorageServiceDeltaRecorder {
    void gtShanhai$recordDelta(AEKey key, long delta);
}
