package com.dishanhai.gt_shanhai.integration.gtlcore;

/** Bounds GTLCore preview searches before recursive pattern matching starts. */
public final class PatternPreviewSearchGuard {

    private static final int MAX_AISLE_DEPTH = 128;
    private static final long MAX_SEARCH_COMBINATIONS = 4096L;

    private PatternPreviewSearchGuard() {
    }

    public static boolean shouldSkip(int[][] aisleRepetitions) {
        if (aisleRepetitions == null) {
            return false;
        }
        if (aisleRepetitions.length > MAX_AISLE_DEPTH) {
            return true;
        }

        long combinations = 1L;
        for (int[] range : aisleRepetitions) {
            if (range == null || range.length < 2 || range[0] < 0 || range[1] < range[0]) {
                return true;
            }
            long choices = (long) range[1] - range[0] + 1L;
            if (choices > MAX_SEARCH_COMBINATIONS / combinations) {
                return true;
            }
            combinations *= choices;
        }
        return false;
    }
}
