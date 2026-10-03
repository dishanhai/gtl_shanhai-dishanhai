package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialRecipeSwitchAndOverdriverSourceTest {

    private static final Path SELECTABLE_LOGIC_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "machine", "SelectableRecipeTypeSetRecipeLogic.java");
    private static final Path PRIMORDIAL_LOGIC_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialModuleRecipeLogic.java");

    @Test
    void capabilityChangesInvalidateNonEmptyRecipeCandidates() throws Exception {
        String source = Files.readString(SELECTABLE_LOGIC_SOURCE);
        String method = extractBlock(source, "public void updateTickSubscription() {");

        assertTrue(method.contains("invalidateLookupSetCache();"),
                "輸入/輸出能力變更必須清除非空候選快取，才能立即切換電路配方");
    }

    @Test
    void primordialParallelDataUsesTheRealInputHandlingPath() throws Exception {
        String source = Files.readString(PRIMORDIAL_LOGIC_SOURCE);
        String method = extractBlock(source, "protected ParallelData calculateParallels() {");

        assertTrue(method.contains("new ParallelData((List<GTRecipe>) recipeList, parallels, true, null)"),
                "原初配方必須進入 buildFinalWirelessRecipe 的真實扣料分支");
        assertTrue(source.contains("mod.hasParallelOverdriver()"),
                "超限器必須由原初配方邏輯直接識別");
        assertTrue(source.contains("return Long.MAX_VALUE;"),
                "超限器必須為每個配方提供 Long.MAX_VALUE 級上限");
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return source.substring(openBrace, i + 1);
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }
}
