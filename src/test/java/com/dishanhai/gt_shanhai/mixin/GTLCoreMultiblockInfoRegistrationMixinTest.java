package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GTLCoreMultiblockInfoRegistrationMixinTest {

    private static final Path HELPER_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "MultiblockPreviewRegistrationHelper.java");
    private static final Path MIXIN_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GTLCoreMultiblockInfoRegistrationMixin.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void keepsValidPreviewsWhenOneFactoryFails() throws Exception {
        assertTrue(Files.exists(HELPER_SOURCE), "必須提供逐機器容錯的預覽收集器");

        Class<?> helper = Class.forName("com.dishanhai.gt_shanhai.mixin.MultiblockPreviewRegistrationHelper");
        Method collect = helper.getDeclaredMethod("collect", Iterable.class, Predicate.class,
                Function.class, BiConsumer.class);
        collect.setAccessible(true);

        List<String> failures = new ArrayList<>();
        Predicate<String> selected = value -> true;
        Function<String, String> factory = value -> {
            if ("broken".equals(value)) {
                throw new IllegalStateException("broken preview");
            }
            return value.toUpperCase();
        };
        BiConsumer<String, Throwable> onFailure = (value, error) -> failures.add(value);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) collect.invoke(null,
                List.of("first", "broken", "last"), selected, factory, onFailure);

        assertEquals(List.of("FIRST", "LAST"), result);
        assertEquals(List.of("broken"), failures);
    }

    @Test
    void replacesGtlCoreAllOrNothingRegistrationAtCaller() throws Exception {
        assertTrue(Files.exists(MIXIN_SOURCE), "必須提供 GTLCore 多方塊預覽註冊相容 Mixin");
        String source = Files.readString(MIXIN_SOURCE);
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(source.contains("GTJEIPlugin.class"),
                "必須在 GTCEu JEI 外層註冊入口攔截，避開 GTLCore cancellable HEAD 的順序競爭");
        assertTrue(source.contains("@Redirect(method = \"registerRecipes\""));
        assertTrue(source.contains("MultiblockInfoCategory;registerRecipes"),
                "必須只替換 GTCEu 原有的多方塊資訊註冊呼叫");
        assertTrue(source.contains("Minecraft.getInstance().submit("),
                "非同步 JEI 啟動時仍必須把假世界預覽建立排到 Minecraft 執行緒");
        assertTrue(source.contains(".join()"),
                "JEI 註冊執行緒必須等待全部可用 wrapper 建立完成再返回");
        assertTrue(source.contains("MultiblockPreviewRegistrationHelper.collect"));
        assertTrue(source.contains("registry.addRecipes(MultiblockInfoCategory.RECIPE_TYPE, wrappers)"));
        assertTrue(config.contains("\"GTLCoreMultiblockInfoRegistrationMixin\""));
    }
}
