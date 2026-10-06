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
    void editorUsesMachineMappingAndGraphicalIoWidgets() throws IOException {
        String panel = Files.readString(JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiRecipeEditorPanel.java")));
        String ioWidget = Files.readString(JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiIOWidget.java")));
        String card = Files.readString(JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiRecipeCardWidget.java")));
        assertTrue(panel.contains("IGhostIngredientTarget"));
        assertTrue(panel.contains("ShanhaiQuerySlotWidget"));
        assertTrue(panel.contains("ShanhaiRecipeTabBarWidget"));
        assertTrue(ioWidget.contains("FRAME_FLUID"));
        assertTrue(ioWidget.contains("getPhantomTargets"));
        assertTrue(card.contains("drawInBackground"));
    }

    @Test
    void machineMappingContractUsesMetaMachineRecipeTypes() throws IOException {
        Path mapping = JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiRecipeMachineMapping.java"));
        String source = Files.readString(mapping);
        assertTrue(source.contains("MetaMachineItem"));
        assertTrue(source.contains("getDefinition().getRecipeTypes()"));
        assertTrue(source.contains("GTRegistries.MACHINES"));
    }

    @Test
    void machineRecipeTypesUseOneExpandableSelector() throws IOException {
        String widget = Files.readString(JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiRecipeEditorWidget.java")));
        String preview = Files.readString(Path.of("tools", "recipe_modifier_ui_preview.html"));
        assertTrue(widget.contains("SelectorWidget"));
        assertTrue(widget.contains("setCandidatesSupplier"));
        assertTrue(widget.contains("setOnChanged"));
        assertTrue(preview.contains("<select"));
        assertTrue(preview.contains("recipe-type-selector"));
    }

    @Test
    void machineTypeLabelsResolveChineseNameAndKeepFullId() throws IOException {
        String mapping = Files.readString(JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiRecipeMachineMapping.java")));
        String names = Files.readString(JAVA_ROOT.resolve(Path.of(
                "common", "recipe", "editor", "ShanhaiRecipeTypeNames.java")));
        String preview = Files.readString(Path.of("tools", "recipe_modifier_ui_preview.html"));
        assertTrue(mapping.contains("ShanhaiRecipeTypeNames.display"));
        assertTrue(names.contains("Component.translatable"));
        assertTrue(names.contains("zh_cn.json"));
        assertTrue(names.contains("（"));
        assertTrue(preview.contains("化学反应釜（gtceu:chemical_reactor）"));
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
