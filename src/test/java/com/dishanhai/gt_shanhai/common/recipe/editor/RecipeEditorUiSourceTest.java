package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RecipeEditorUiSourceTest {

    private static final Path JAVA_ROOT =
            Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");

    @Test
    void layeredEditorContainsFactoryWidgetAndAnimationContract() throws IOException {
        Path factory = JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiRecipeEditorFactory.java"));
        Path widget = JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiRecipeEditorWidget.java"));
        Path animation = JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiRecipeEditorAnimation.java"));

        assertTrue(Files.isRegularFile(factory));
        assertTrue(Files.isRegularFile(widget));
        assertTrue(Files.isRegularFile(animation));

        String factorySource = Files.readString(factory);
        String widgetSource = Files.readString(widget);
        String animationSource = Files.readString(animation);

        assertTrue(factorySource.contains("UIFactory"));
        assertTrue(factorySource.contains("UIFactory.register"));
        assertTrue(factorySource.contains("open(Object ignored, ServerPlayer player)"));
        assertTrue(widgetSource.contains("STAGE_SELECT"));
        assertTrue(widgetSource.contains("STAGE_EDIT"));
        assertTrue(widgetSource.contains("STAGE_REVIEW"));
        assertTrue(widgetSource.contains("RecipeEditorResultPacket"));
        assertTrue(animationSource.contains("DURATION_MS"));
        assertTrue(animationSource.contains("advance"));
        assertTrue(animationSource.contains("reset"));
    }

    @Test
    void holoBridgeDelegatesToLocalLayeredFactory() throws IOException {
        String source = Files.readString(JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "DShanhaiRecipeEditorFactory.java")));
        assertTrue(source.contains(
                "com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorFactory"));
        assertTrue(source.contains("ShanhaiRecipeEditorFactory.open"));
    }
}
