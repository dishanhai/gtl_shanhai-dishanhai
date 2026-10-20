package com.dishanhai.gt_shanhai.integration.gtlcore;

/**
 * Bounds GTLCore preview searches before recursive pattern matching starts.
 *
 * <p>只有「可重複層」({@code range[0] != range[1]}) 才可能讓 GTLCore 的層數回放搜索
 * 出現組合爆炸。{@code FactoryBlockPattern.aisle(...)} 建出的層是固定 1..1，
 * GTLCore 走的是 {@code FixedPatternLayout} 線性匹配，成本與層數成正比、與層數的冪無關。
 *
 * <p>因此固定層再多也不能跳過：一旦跳過，預覽世界的控制器就不會成型，
 * 上方 {@code PatternPreviewWidget} 會保留一個 cache 為 null 的 {@code MultiblockState}，
 * 後續 {@code clearSharedPreviewState} 清理時拋 NPE，直接打斷 JEI 配方布局更新，
 * 表現就是 JEI 結構預覽打不開。本模組的 .mb 結構（天界領航塔 247 層、
 * 黑洞收容 186 層）正是踩在這個門檻上。
 */
public final class PatternPreviewSearchGuard {

    private static final int MAX_AISLE_DEPTH = 128;
    private static final long MAX_SEARCH_COMBINATIONS = 4096L;

    private PatternPreviewSearchGuard() {
    }

    public static boolean shouldSkip(int[][] aisleRepetitions) {
        if (aisleRepetitions == null) {
            return false;
        }

        long combinations = 1L;
        boolean hasRepeatableAisle = false;
        for (int[] range : aisleRepetitions) {
            if (range == null || range.length < 2 || range[0] < 0 || range[1] < range[0]) {
                return true;
            }
            long choices = (long) range[1] - range[0] + 1L;
            if (choices <= 1L) {
                // 固定層：線性匹配，不參與組合數計算，也不受層數上限約束
                continue;
            }
            hasRepeatableAisle = true;
            if (choices > MAX_SEARCH_COMBINATIONS / combinations) {
                return true;
            }
            combinations *= choices;
        }

        return hasRepeatableAisle && aisleRepetitions.length > MAX_AISLE_DEPTH;
    }
}
