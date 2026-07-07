package com.dishanhai.gt_shanhai.api.ae2;

import appeng.api.stacks.AEKey;

import java.util.Set;

public interface IStorageServiceRevisionAccess {

    long gtShanhai$getInventoryRevision();

    Set<AEKey> gtShanhai$getLastChangedKeys();
}
