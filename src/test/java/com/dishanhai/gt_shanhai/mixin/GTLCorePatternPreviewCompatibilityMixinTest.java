package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GTLCorePatternPreviewCompatibilityMixinTest {

    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GTLCorePatternPreviewCompatibilityMixin.java");

    @Test
    void skipsOnlyPreviewSearchesThatExceedSafeBounds() throws Exception {
        assertFalse(shouldSkip(fixedAisles(128)));
        assertTrue(shouldSkip(fixedAisles(129)));

        assertFalse(shouldSkip(repeatableAisles(6, 1, 4)));
        assertTrue(shouldSkip(repeatableAisles(7, 1, 4)));
        assertTrue(shouldSkip(new int[][]{{3, 2}}));
    }

    @Test
    void redirectsOnlyGtlCoreControllerPreviewFormationCheck() throws Exception {
        String source = Files.readString(SOURCE);
        String config = Files.readString(CONFIG);

        assertTrue(source.contains("org.gtlcore.gtlcore.api.gui.PatternPreviewWidget"));
        assertTrue(source.contains("@Redirect(method = \"loadControllerFormed\""));
        assertTrue(source.contains("BlockPattern;checkPatternAt(Lcom/gregtechceu/gtceu/api/pattern/MultiblockState;Z)Z"));
        assertTrue(source.contains("PatternPreviewSearchGuard.shouldSkip(pattern.aisleRepetitions)"));
        assertTrue(source.contains("return pattern.checkPatternAt(state, savePredicate);"));
        assertTrue(config.contains("GTLCorePatternPreviewCompatibilityMixin"));
    }

    private static boolean shouldSkip(int[][] aisleRepetitions) throws Exception {
        Class<?> guard = Class.forName("com.dishanhai.gt_shanhai.mixin.PatternPreviewSearchGuard");
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
