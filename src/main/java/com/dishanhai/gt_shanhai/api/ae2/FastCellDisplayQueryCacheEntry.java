package com.dishanhai.gt_shanhai.api.ae2;

import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * FastCell 精確庫存查詢的快取資料。
 *
 * <p>此類刻意位於非 Mixin package，避免 Mixin 合併到 gtlcore 類後留下對
 * Mixin package 巢狀類的直接引用，觸發 Mixin 的類別載入保護。</p>
 */
public final class FastCellDisplayQueryCacheEntry {

    public MEStorage storage;
    public long revision = Long.MIN_VALUE;
    public long topology = Long.MIN_VALUE;
    public List<AEKey> keys = List.of();
    public Map<AEKey, BigInteger> amounts = Map.of();
}
