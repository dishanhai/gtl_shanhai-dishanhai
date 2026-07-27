package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 星律共享搜索集必须接入所有配方类型隔离判定点：宿主类型校验（虚拟直跑前置 + 收集后过滤）、
 * 通用样板的选择集选中校验、选择集机器候选校验、原初模块候选校验、样板槽扣料匹配。
 * 缺任何一处，同组类型（如化反↔大型化反）仍会在该链路被严格隔离拦下。
 */
class RecipeTypeSharedSearchSetGuardSourceTest {

    private static final Path SEARCH_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path SELECTABLE_LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "machine", "SelectableRecipeTypeSetRecipeLogic.java");
    private static final Path MODULE_LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialModuleRecipeLogic.java");
    private static final Path PATTERN_BUFFER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");
    private static final Path CONFIG_SCREEN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "config", "DShanhaiConfigScreen.java");

    @Test
    void hostTypeGuardsHonorSharedSearchSets() throws IOException {
        String source = Files.readString(SEARCH_HELPER);

        assertMethodContains(source, "private static boolean hostAllowsVirtualRecipeType",
                "RecipeTypeSharedSearchSets.isSharedWithAny(recipeType, hostTypes)",
                "虚拟直跑前置校验必须放行与宿主同组的配方类型");
        assertMethodContains(source, "private static Set<GTRecipe> retainHostSupportedRecipes",
                "RecipeTypeSharedSearchSets.isSharedWithAny(recipe.recipeType, hostTypes)",
                "收集后过滤必须放行与宿主同组的配方类型");
        assertMethodContains(source, "private static boolean isSelectedOnMachine",
                "RecipeTypeSharedSearchSets.isSharedWithAny(type, selectable.getSelectedRecipeTypes())",
                "通用样板的选择集选中校验必须放行同组类型");
    }

    @Test
    void selectableRecipeTypeSetLogicHonorsSharedSearchSets() throws IOException {
        String source = Files.readString(SELECTABLE_LOGIC);

        assertMethodContains(source, "private boolean isRecipeTypeSelected",
                "RecipeTypeSharedSearchSets.isSharedWithAny",
                "选择集机器的候选校验必须放行与任一选中类型同组的配方类型");
    }

    @Test
    void primordialModuleLogicHonorsSharedSearchSets() throws IOException {
        String source = Files.readString(MODULE_LOGIC);

        assertMethodContains(source, "private boolean isSelectedRecipeType",
                "RecipeTypeSharedSearchSets.isSharedWithAny",
                "原初模块的候选校验必须放行与任一选中类型同组的配方类型");
    }

    @Test
    void patternSlotDeductionHonorsSharedSearchSets() throws IOException {
        String source = Files.readString(PATTERN_BUFFER);

        assertMethodContains(source, "public boolean gtShanhai$slotAllowsRecipe",
                "RecipeTypeSharedSearchSets.isShared(",
                "样板槽扣料匹配必须放行与槽位标记类型同组的配方（宿主原生搜索路径）");
    }

    @Test
    void configScreenExposesSharedSearchSets() throws IOException {
        String source = Files.readString(CONFIG_SCREEN);

        assertTrue(source.contains("星律共享搜索集"), "配置界面必须暴露星律共享搜索集条目");
        assertTrue(source.contains("recipeTypeSharedSearchSets.set"), "配置界面必须写回共享搜索集配置项");
    }

    private static void assertMethodContains(String source, String methodStartText, String requiredText,
            String message) {
        int methodStart = source.indexOf(methodStartText);
        assertTrue(methodStart >= 0, "未找到待校验的方法: " + methodStartText);
        // 粗粒度方法区间：从签名起到下一个方法级修饰符出现为止，足够锚定共享集调用是否在方法体内
        int methodEnd = indexOfNextMethod(source, methodStart + methodStartText.length());
        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains(requiredText), message + ": " + methodStartText);
    }

    private static int indexOfNextMethod(String source, int fromIndex) {
        int candidate = source.length();
        for (String marker : new String[] { "\n    private ", "\n    public ", "\n    protected ",
                "\n    static ", "\n    @Override" }) {
            int index = source.indexOf(marker, fromIndex);
            if (index >= 0 && index < candidate) {
                candidate = index;
            }
        }
        return candidate;
    }
}
