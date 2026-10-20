package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GTLCorePatternPreviewCompatibilityMixinTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GTLCorePatternPreviewCompatibilityMixin.java");

    @Test
    void skipsOnlyPreviewSearchesThatExceedSafeBounds() throws Exception {
        assertFalse(shouldSkip(fixedAisles(128)));
        // .mb 结构動輒 180~250 層，全部是固定層（線性匹配），不能因為層數多就被跳過
        assertFalse(shouldSkip(fixedAisles(186)));
        assertFalse(shouldSkip(fixedAisles(247)));

        assertFalse(shouldSkip(repeatableAisles(6, 1, 4)));
        assertTrue(shouldSkip(repeatableAisles(7, 1, 4)));
        assertTrue(shouldSkip(new int[][] { { 3, 2 } }));
        // 只有「可重複層」才需要受層數上限約束
        assertTrue(shouldSkip(repeatableAisles(129, 1, 2)));
        assertTrue(shouldSkip(repeatableAisles(200, 1, 2)));
    }

    @Test
    void fixedAislePreviewSearchIsNeverSkippedForMbStructures() throws Exception {
        for (int layers : new int[] { 9, 13, 15, 29, 31, 50, 73, 82, 186, 247 }) {
            assertFalse(shouldSkip(fixedAisles(layers)),
                    layers + " 層固定 .mb 結構的預覽不能被跳過，否則控制器不會成型且 JEI 清理時會 NPE");
        }
    }

    @Test
    void redirectsOnlyGtlCoreControllerPreviewFormationCheck() throws Exception {
        String source = Files.readString(SOURCE);
        String config = MixinConfigs.readAll();

        assertTrue(source.contains("org.gtlcore.gtlcore.api.gui.PatternPreviewWidget"));
        assertTrue(source.contains("@Redirect(method = \"loadControllerFormed\""));
        assertTrue(source.contains("BlockPattern;checkPatternAt(Lcom/gregtechceu/gtceu/api/pattern/MultiblockState;Z)Z"));
        assertTrue(source.contains("PatternPreviewSearchGuard.shouldSkip(pattern.aisleRepetitions)"));
        assertTrue(source.contains("return pattern.checkPatternAt(state, savePredicate);"));
        assertTrue(config.contains("GTLCorePatternPreviewCompatibilityMixin"));
    }

    private static boolean shouldSkip(int[][] aisleRepetitions) throws Exception {
        Class<?> guard = Class.forName("com.dishanhai.gt_shanhai.integration.gtlcore.PatternPreviewSearchGuard");
        Method method = guard.getDeclaredMethod("shouldSkip", int[][].class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, (Object) aisleRepetitions);
    }

    private static int[][] fixedAisles(int count) {
        return repeatableAisles(count, 1, 1);
    }

    private static int[][] repeatableAisles(int count, int min, int max) {
        int[][] repetitions = new int[count][2];
        for (int i = 0; i < count; i++) {
            repetitions[i][0] = min;
            repetitions[i][1] = max;
        }
        return repetitions;
    }
}
