package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import java.util.Arrays;

/** 山海：AR 按槽位 ID 升序緊接排列；保留原始資料的槽號對應。 */
public final class HostFxGlassArSort {
    private HostFxGlassArSort() {}

    /** 只排序副本，避免改動與模組名稱平行對應的原始陣列。 */
    public static int[] order(int[] slots) {
        if (slots == null) return new int[0];
        int[] result = slots.clone();
        Arrays.sort(result);
        return result;
    }
}
