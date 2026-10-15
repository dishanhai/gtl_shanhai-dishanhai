package com.dishanhai.gt_shanhai.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedRecipePatternRendererSourceTest {

    private static final Path ROOT = Path.of("src", "main");
    private static final Path ITEM = ROOT.resolve(Path.of("java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "SelectedRecipePatternItem.java"));
    private static final Path DATA = ROOT.resolve(Path.of("java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "SelectedRecipePattern.java"));
    private static final Path REGISTRATION = ROOT.resolve(Path.of("java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "DShanhaiItems.java"));
    private static final Path RENDERER = ROOT.resolve(Path.of("java", "com", "dishanhai", "gt_shanhai",
            "client", "renderer", "item", "SelectedRecipePatternRenderer.java"));
    private static final Path MODEL_REGISTRATION = ROOT.resolve(Path.of("java", "com", "dishanhai", "gt_shanhai",
            "client", "renderer", "item", "SelectedRecipePatternModelRegistration.java"));
    private static final Path ITEM_MODEL = ROOT.resolve(Path.of("resources", "assets", "gt_shanhai",
            "models", "item", "selected_recipe_pattern.json"));
    private static final Path BASE_MODEL = ROOT.resolve(Path.of("resources", "assets", "gt_shanhai",
            "models", "item", "selected_recipe_pattern_base.json"));

    @Test
    void guiOverlaysUpToFourOutputItemsOnThePattern() throws IOException {
        String item = Files.readString(ITEM);
        String data = Files.readString(DATA);
        String registration = Files.readString(REGISTRATION);
        String renderer = Files.readString(RENDERER);
        String modelRegistration = Files.readString(MODEL_REGISTRATION);
        String itemModel = Files.readString(ITEM_MODEL);
        String baseModel = Files.readString(BASE_MODEL);

        assertTrue(item.contains("void initializeClient(Consumer<IClientItemExtensions> consumer)"));
        assertTrue(item.contains("SelectedRecipePatternRenderer.getInstance()"));
        assertTrue(registration.contains("SelectedRecipePatternItem::new"));

        assertTrue(data.contains("CORNER_ICONS = 4"));
        assertTrue(data.contains("cornerIcons"));
        assertTrue(data.contains("getOutputContents(ItemRecipeCapability.CAP)"));
        assertTrue(data.contains("isSelf"));

        assertTrue(renderer.contains("pDisplayContext == ItemDisplayContext.GUI")
                || renderer.contains("displayContext == ItemDisplayContext.GUI"));
        assertTrue(renderer.contains("SelectedRecipePattern.cornerIcons"));
        assertTrue(renderer.contains("ICON_SCALE"));
        assertTrue(renderer.contains("-0.25F, 0.25F, -0.25F, 0.25F"));
        assertTrue(renderer.contains("0.25F, 0.25F, -0.25F, -0.25F"));
        assertTrue(renderer.contains("renderStatic"));
        assertTrue(renderer.contains("renderBaseOnly"));

        assertTrue(modelRegistration.contains("event.register(SelectedRecipePatternRenderer.BASE_MODEL)"));
        assertTrue(itemModel.contains("\"parent\": \"builtin/entity\""));
        assertTrue(baseModel.contains("\"parent\": \"minecraft:item/generated\""));
        assertTrue(baseModel.contains("\"layer0\": \"gt_shanhai:item/selected_recipe_pattern\""));
    }
}
