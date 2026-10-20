package com.dishanhai.gt_shanhai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 校验旧版平铺布局 → 分组布局的迁移语义：移动、已存在时不覆盖、目录整体迁移、幂等。
 */
class ShanhaiConfigPathsMigrationTest {

    @Test
    void legacyFilesAndDirectoriesMoveIntoGroupedLayout(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("shop.json"), "{\"entries\":[]}");
        Files.writeString(root.resolve("strip_rules.json"), "{}");
        Files.writeString(root.resolve("multiblock_registry.json"), "{}");
        Files.writeString(root.resolve("ae_terminal_favorites.nbt"), "nbt");
        Files.createDirectories(root.resolve("presets"));
        Files.writeString(root.resolve("presets/太空采矿预设.json"), "{}");
        Files.createDirectories(root.resolve("pattern-generator-presets"));
        Files.writeString(root.resolve("pattern-generator-presets/a.json"), "{}");

        invokeMigrate(root);

        assertEquals("{\"entries\":[]}", Files.readString(root.resolve("shop/shop.json")));
        assertEquals("{}", Files.readString(root.resolve("recipe/strip_rules.json")));
        assertEquals("{}", Files.readString(root.resolve("machine/multiblock_registry.json")));
        assertEquals("nbt", Files.readString(root.resolve("client/ae_terminal_favorites.nbt")));
        assertEquals("{}", Files.readString(root.resolve("recipe/presets/太空采矿预设.json")));
        assertEquals("{}", Files.readString(root.resolve("client/pattern-generator-presets/a.json")));

        assertFalse(Files.exists(root.resolve("shop.json")));
        assertFalse(Files.exists(root.resolve("presets")));
        assertFalse(Files.exists(root.resolve("pattern-generator-presets")));
    }

    @Test
    void existingNewFileWinsAndLegacyIsArchivedNotDeleted(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("shop.json"), "{\"legacy\":true}");
        Files.createDirectories(root.resolve("shop"));
        Files.writeString(root.resolve("shop/shop.json"), "{\"entries\":[1]}");

        invokeMigrate(root);

        assertEquals("{\"entries\":[1]}", Files.readString(root.resolve("shop/shop.json")));
        assertFalse(Files.exists(root.resolve("shop.json")));
        assertTrue(Files.exists(root.resolve("shop.json.legacy")),
                "旧文件应改名留档而不是直接删除玩家数据");
    }

    @Test
    void migrationIsIdempotent(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("shop.json"), "{\"entries\":[]}");

        invokeMigrate(root);
        invokeMigrate(root);
        invokeMigrate(root);

        assertTrue(Files.exists(root.resolve("shop/shop.json")));
        assertFalse(Files.exists(root.resolve("shop.json.legacy")));
    }

    @Test
    void legacyListCoversEveryFileThatUsedToSitInTheRoot() throws Exception {
        List<String> legacyNames = legacyField("LEGACY_FILES");
        List<String> legacyDirs = legacyField("LEGACY_DIRS");

        List<String> expectedFiles = List.of(
                "shop.json", "shop_category_order.json", "shop_client_ui.json",
                "shop_quest_links.json", "shop_stage_requirements.json", "exchanges.json",
                "currency_rates.json", "currency_stages.json", "recipe_overrides.json",
                "strip_rules.json", "replace_rules.json", "delete_rules.json",
                "active_presets.json", "multiblock_registry.json", "ae_terminal_favorites.nbt");

        for (String name : expectedFiles) {
            assertTrue(legacyNames.contains(name), "迁移清单缺少旧文件: " + name);
        }
        assertTrue(legacyDirs.contains("presets"));
        assertTrue(legacyDirs.contains("pattern-generator-presets"));
    }

    /** 反射调用私有的静态迁移方法，并把迁移标记复位，便于同一 JVM 内多次运行。 */
    private static void invokeMigrate(Path root) throws Exception {
        Method method = ShanhaiConfigPaths.class.getDeclaredMethod("migrateEntry", Path.class, Class.forName(
                "com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths$LegacyFile"));
        method.setAccessible(true);

        Class<?> legacyType = Class.forName("com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths$LegacyFile");
        for (String fieldName : List.of("LEGACY_FILES", "LEGACY_DIRS")) {
            var field = ShanhaiConfigPaths.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            for (Object entry : (List<?>) field.get(null)) {
                method.invoke(null, root, entry);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> legacyField(String fieldName) throws Exception {
        var field = ShanhaiConfigPaths.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        List<String> names = new java.util.ArrayList<>();
        for (Object entry : (List<?>) field.get(null)) {
            var accessor = entry.getClass().getDeclaredMethod("legacyName");
            accessor.setAccessible(true);
            names.add((String) accessor.invoke(entry));
        }
        return names;
    }
}
