package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StellarPatternCheatVirtualExecutionSourceTest {

    private static final Path CONFIG = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "config", "DShanhaiConfig.java");
    private static final Path CONFIG_SCREEN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "config", "DShanhaiConfigScreen.java");
    private static final Path SEARCH_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path MACHINE_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GTLCoreMEPatternBufferVirtualProviderMixin.java");
    private static final Path SLOT_STATE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "VirtualPatternBufferSlotState.java");
    private static final Path NATIVE_FIND_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "NativeVirtualFindHandleRecipeMixin.java");
    private static final Path BEFORE_WORKING_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "NativeVirtualBeforeWorkingBypassMixin.java");

    @Test
    void cheatVirtualExecutionIsDisabledByDefault() {
        assertFalse(DShanhaiConfig.COMMON.recipeTypePatternAllowCheatVirtualExecution.getDefault(),
                "星律破限虚拟执行链必须默认关闭");
    }

    @Test
    void configAndScreenMarkTheSwitchAsCheat() throws IOException {
        String config = Files.readString(CONFIG);
        String screen = Files.readString(CONFIG_SCREEN);

        assertTrue(config.contains("recipeTypePatternAllowCheatVirtualExecution"));
        assertTrue(config.contains(".define(\"allowCheatVirtualExecution\", false)"));
        assertTrue(config.contains("作弊/破限"));
        assertTrue(screen.contains("作弊：允许星律虚拟执行链破限"));
        assertTrue(screen.contains("recipeTypePatternAllowCheatVirtualExecution::set"));
    }

    @Test
    void firstSparkNativeVirtualAndTopUpAreCheatGated() throws IOException {
        String source = Files.readString(SEARCH_HELPER);

        assertMethodContains(source, "public static Set<GTRecipe> collectNativeVirtualRecipes",
                "if (!allowCheatVirtualExecution()) return result;");
        assertMethodContains(source, "private static void collectPlainPatternRecipesFromPart",
                "if (!allowCheatVirtualExecution()) return;");
        assertMethodContains(source, "private static void collectFirstSparkPatternRecipes",
                "if (!allowCheatVirtualExecution()) return;");
        assertMethodContains(source, "private static void topUpVirtualSupply",
                "if (!allowCheatVirtualExecution()) return;");
        assertTrue(source.contains("DShanhaiConfig.COMMON.recipeTypePatternAllowCheatVirtualExecution.get()"));
    }

    @Test
    void nativeBeforeWorkingBypassIsCheatGated() throws IOException {
        String find = Files.readString(NATIVE_FIND_MIXIN);
        String beforeWorking = Files.readString(BEFORE_WORKING_MIXIN);

        assertTrue(find.contains("recipeTypePatternAllowCheatVirtualExecution.get()"),
                "原生多方块虚拟直跑必须受作弊开关控制");
        assertTrue(beforeWorking.contains("recipeTypePatternAllowCheatVirtualExecution.get()"),
                "跳过宿主 beforeWorking/part 检查必须受作弊开关控制");
    }

    @Test
    void restoredVirtualPresenceNoLongerUsesInfiniteAmount() throws IOException {
        String machineMixin = Files.readString(MACHINE_MIXIN);
        String slotState = Files.readString(SLOT_STATE);

        assertFalse(machineMixin.contains("restoreVirtualTarget(key, Long.MAX_VALUE)"),
                "虚拟 presence 不能再用 Long.MAX_VALUE 恢复为近无限库存");
        assertTrue(machineMixin.contains("gtShanhai$presenceAmount(input)"),
                "从样板恢复 presence 时必须使用样板自身 multiplier");
        assertTrue(machineMixin.contains("gtShanhai$presenceAmount(amount)"),
                "运行期添加 presence 时必须使用有限目标数量");
        assertTrue(slotState.contains("previous > registered"),
                "旧存档中的超大虚拟 presence 必须能被后续有限恢复量压回去");
    }

    private static void assertMethodContains(String source, String methodStartText, String expected) {
        int methodStart = source.indexOf(methodStartText);
        assertTrue(methodStart >= 0, "未找到方法: " + methodStartText);
        int methodEnd = source.indexOf("\n    private static", methodStart + methodStartText.length());
        if (methodEnd < 0) {
            methodEnd = source.indexOf("\n    public static", methodStart + methodStartText.length());
        }
        if (methodEnd < 0) {
            methodEnd = source.length();
        }
        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains(expected), "方法缺少预期守卫: " + methodStartText);
    }
}
