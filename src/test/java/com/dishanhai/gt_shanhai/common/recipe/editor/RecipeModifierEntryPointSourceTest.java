package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RecipeModifierEntryPointSourceTest {

    private static final Path JAVA_ROOT =
            Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");
    private static final Path RESOURCE_ROOT =
            Path.of("src", "main", "resources", "assets", "gt_shanhai");
    private static final Path PREVIEW =
            Path.of("tools", "recipe_modifier_ui_preview.html");

    @Test
    void chineseCommandOpensLayeredEditor() throws IOException {
        String commands = Files.readString(JAVA_ROOT.resolve(Path.of(
                "command", "DShanhaiCommands.java")));
        assertTrue(commands.contains("Commands.literal(\"修改器\")"));
        assertTrue(commands.contains("ShanhaiRecipeEditorFactory.open"));
        assertTrue(commands.contains("Commands.literal(\"山海\")"));
        assertTrue(commands.contains("Commands.literal(\"配方\")"));
    }

    @Test
    void developerToolIsRegisteredAndUsesTheSameFactory() throws IOException {
        String mod = Files.readString(JAVA_ROOT.resolve("GTDishanhaiMod.java"));
        Path item = JAVA_ROOT.resolve(Path.of(
                "common", "item", "RecipeModifierDevItem.java"));
        Path model = RESOURCE_ROOT.resolve(Path.of(
                "models", "item", "_recipe_modifier_dev.json"));
        Path texture = RESOURCE_ROOT.resolve(Path.of(
                "textures", "item", "_recipe_modifier_dev.png"));
        Path animation = RESOURCE_ROOT.resolve(Path.of(
                "textures", "item", "_recipe_modifier_dev.png.mcmeta"));

        assertTrue(mod.contains("ITEMS.register("));
        assertTrue(mod.contains("\"_recipe_modifier_dev\""));
        assertTrue(mod.contains("RecipeModifierDevItem"));
        assertTrue(Files.isRegularFile(item));
        assertTrue(Files.isRegularFile(model));
        assertTrue(Files.isRegularFile(texture));
        assertTrue(Files.isRegularFile(animation));
        assertTrue(Files.isRegularFile(PREVIEW));

        String itemSource = Files.readString(item);
        assertTrue(itemSource.contains("ShanhaiRecipeEditorFactory.open"));
        assertTrue(itemSource.contains("hasPermissions(2)"));
        assertTrue(Files.readString(animation).contains("\"frames\""));
        String preview = Files.readString(PREVIEW);
        assertTrue(preview.contains("item-frames"));
        assertTrue(preview.contains("data-stage=\"0\""));
        assertTrue(preview.contains("data-stage=\"2\""));
    }
}
