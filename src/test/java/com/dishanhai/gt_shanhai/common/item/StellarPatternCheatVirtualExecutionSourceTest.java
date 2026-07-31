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
        assertTrue(config.contains("未下单槽位不会主动从 AE 网络预填原料"));
        assertTrue(screen.contains("作弊：允许星律虚拟执行链破限"));
        assertTrue(screen.contains("recipeTypePatternAllowCheatVirtualExecution::set"));
    }

    @Test
    void normalVirtualSupplyAndExecutionAreNotCheatGated() throws IOException {
        String source = Files.readString(SEARCH_HELPER);
        String find = Files.readString(NATIVE_FIND_MIXIN);

        assertMethodDoesNotContain(source, "public static Set<GTRecipe> collectNativeVirtualRecipes",
                "if (!allowCheatVirtualExecution()) return result;");
        assertMethodContains(source, "private static void collectPlainPatternRecipesFromPart",
                "if (!allowCheatVirtualExecution()) return;");
        assertMethodContains(source, "private static void collectFirstSparkPatternRecipes",
                "if (!allowCheatVirtualExecution()) return;");
        assertMethodDoesNotContain(source, "private static void topUpVirtualSupply",
                "if (!allowCheatVirtualExecution()) return;");
        assertFalse(find.contains("if (!DShanhaiConfig.COMMON.recipeTypePatternAllowCheatVirtualExecution.get()) return;"),
                "关闭作弊开关时仍必须进入宿主限制内的正常虚拟执行链");
    }

    @Test
    void nativeBeforeWorkingBypassIsCheatGated() throws IOException {
        String find = Files.readString(NATIVE_FIND_MIXIN);
        String beforeWorking = Files.readString(BEFORE_WORKING_MIXIN);

        assertTrue(find.contains("recipeTypePatternAllowCheatVirtualExecution.get()"),
                "只有宿主限制绕过状态必须受作弊开关控制");
        assertTrue(find.contains("if (bypassHostLimits)"),
                "配置开启时才允许激活宿主限制绕过状态");
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

    private static void assertMethodDoesNotContain(String source, String methodStartText, String unexpected) {
        String method = extractMethod(source, methodStartText);
        assertFalse(method.contains(unexpected), "正常执行路径不应受作弊开关阻断: " + methodStartText);
    }

    private static void assertMethodContains(String source, String methodStartText, String expected) {
        String method = extractMethod(source, methodStartText);
        assertTrue(method.contains(expected), "未下单首配路径必须受作弊开关控制: " + methodStartText);
    }

    private static String extractMethod(String source, String methodStartText) {
        int methodStart = source.indexOf(methodStartText);
        assertTrue(methodStart >= 0, "未找到方法: " + methodStartText);
        int methodEnd = source.indexOf("\n    private static", methodStart + methodStartText.length());
        if (methodEnd < 0) {
            methodEnd = source.indexOf("\n    public static", methodStart + methodStartText.length());
        }
        if (methodEnd < 0) {
            methodEnd = source.length();
        }
        return source.substring(methodStart, methodEnd);
    }
}
