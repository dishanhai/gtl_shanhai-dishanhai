package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTypePatternHostTypeGuardSourceTest {

    private static final Path SEARCH_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path CONFIG_SCREEN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "config", "DShanhaiConfigScreen.java");

    @Test
    void unsupportedHostRecipeTypesAreDisabledByDefault() {
        assertFalse(DShanhaiConfig.COMMON.recipeTypePatternAllowUnsupportedHostRecipeTypes.getDefault(),
                "跨宿主配方类型虚拟执行必须默认关闭");
    }

    @Test
    void virtualModeFiltersRecipesAgainstTheHostsActualRecipeTypes() throws IOException {
        String source = Files.readString(SEARCH_HELPER);
        int configGate = source.indexOf("recipeTypePatternAllowUnsupportedHostRecipeTypes.get()");
        int hostTypes = source.indexOf("machine.getRecipeTypes()", configGate);
        int typeCheck = source.indexOf("recipeTypeEquals(hostType, recipe.recipeType)", hostTypes);

        assertTrue(configGate >= 0, "虚拟类型执行必须受独立配置控制");
        assertTrue(hostTypes > configGate, "默认路径必须读取宿主当前实际配方类型集合");
        assertTrue(typeCheck > hostTypes, "每个虚拟配方都必须与宿主类型集合逐项匹配");
    }

    @Test
    void unsupportedRecipesAreRejectedBeforeSupplyOrActivation() throws IOException {
        String source = Files.readString(SEARCH_HELPER);

        assertGuardPrecedesSideEffects(source, "private static void collectPlainPatternRecipesFromPart",
                "private static boolean isSelectedOnMachine");
        assertGuardPrecedesSideEffects(source, "private static void collectMarkedPatternRecipesFromMachine",
                "private static void collectFirstSparkPatternRecipes");
        assertGuardPrecedesSideEffects(source, "private static void collectFirstSparkPatternRecipes",
                "private static void topUpVirtualSupply");
    }

    @Test
    void configScreenExposesTheDangerousCompatibilitySwitch() throws IOException {
        String source = Files.readString(CONFIG_SCREEN);

        assertTrue(source.contains("允许执行宿主不支持的虚拟配方类型"),
                "配置界面必须明确暴露该兼容行为");
        assertTrue(source.contains("recipeTypePatternAllowUnsupportedHostRecipeTypes::set"),
                "配置界面必须写入独立公共配置项");
    }

    private static void assertGuardPrecedesSideEffects(String source, String methodStartText, String methodEndText) {
        int methodStart = source.indexOf(methodStartText);
        int methodEnd = source.indexOf(methodEndText, methodStart + methodStartText.length());
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "未找到待校验的方法区间: " + methodStartText);

        String method = source.substring(methodStart, methodEnd);
        int guard = method.indexOf("hostAllowsVirtualRecipeType(machine, recipe.recipeType)");
        int supply = method.indexOf("topUpVirtualSupply(");
        int activation = method.indexOf("activatePatternRecipe(");
        assertTrue(guard >= 0, "补料路径缺少宿主配方类型前置校验: " + methodStartText);
        if (supply >= 0) {
            assertTrue(guard < supply, "宿主配方类型校验必须早于 AE 原料提取: " + methodStartText);
        }
        assertTrue(activation < 0 || guard < activation,
                "宿主配方类型校验必须早于处理器激活: " + methodStartText);
    }
}
