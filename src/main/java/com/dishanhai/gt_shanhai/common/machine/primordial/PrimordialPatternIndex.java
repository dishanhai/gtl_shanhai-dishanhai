package com.dishanhai.gt_shanhai.common.machine.primordial;

import java.util.Arrays;
import java.util.function.Predicate;

/** 山海：一次編譯有效格，保留原本 aisle → row → column 的判定順序。 */
final class PrimordialPatternIndex {
    private PrimordialPatternIndex() {}

    static <T> int[][] compile(T[][][] pattern, Predicate<T> isAny) {
        int[][] result = new int[pattern.length][];
        for (int aisle = 0; aisle < pattern.length; aisle++) {
            int width = pattern[aisle][0].length;
            int[] cells = new int[pattern[aisle].length * width];
            int count = 0;
            for (int row = 0; row < pattern[aisle].length; row++) {
                for (int column = 0; column < width; column++) {
                    if (!isAny.test(pattern[aisle][row][column])) {
                        cells[count++] = row * width + column;
                    }
                }
            }
            result[aisle] = Arrays.copyOf(cells, count);
        }
        return result;
    }
}
