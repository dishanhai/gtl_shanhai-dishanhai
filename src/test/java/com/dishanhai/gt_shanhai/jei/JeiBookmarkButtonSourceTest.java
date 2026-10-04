package com.dishanhai.gt_shanhai.jei;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class JeiBookmarkButtonSourceTest {

    private static final Path BUTTONS = source("integration", "jei", "JeiPatternQuickEncodeButtons.java");
    private static final Path BRIDGE = source("jei", "JeiBookmarkBridge.java");
    private static final Path CONFIG = source("config", "DShanhaiConfig.java");
    private static final Path CONFIG_SCREEN = source("client", "config", "DShanhaiConfigScreen.java");
    private static final Path ZH_CN = lang("zh_cn.json");
    private static final Path EN_US = lang("en_us.json");

    @Test
    void registersBookmarkButtonAndReadsModeForEveryTooltipRender() throws Exception {
        String buttons = Files.readString(BUTTONS);
        String config = Files.readString(CONFIG);
        String screen = Files.readString(CONFIG_SCREEN);

        assertTrue(buttons.contains("new BookmarkFactory(bookmarkIcon)"));
        assertTrue(buttons.contains("getSlotViews(RecipeIngredientRole.INPUT)"));
        assertTrue(buttons.contains("getDisplayedIngredient()"));
        assertTrue(buttons.contains("getFluidIngredientType()"));
        assertTrue(buttons.contains("collectDisplayedInputs"));
        assertTrue(buttons.contains("[JEIBookmarkDiag]"));
        assertTrue(buttons.contains("DShanhaiConfig.COMMON.jeiBookmarkMode.get()"));
        assertTrue(buttons.contains("tooltip.gt_shanhai.jei.bookmark_no_recipe"));
        assertTrue(buttons.contains("tooltip.gt_shanhai.jei.bookmark_missing"));
        assertTrue(buttons.contains("某個第三方配方類型失敗時繼續檢查其他類型"));
        assertTrue(config.contains("enum JeiBookmarkMode"));
        assertTrue(config.contains("defineEnum(\"bookmarkMode\", JeiBookmarkMode.MISSING_ITEMS)"));
        assertTrue(screen.contains("startEnumSelector(Component.literal(\"配方侧边收藏模式\")"));
    }

    @Test
    void bridgeKeepsJeiTypesOutOfPublicSignatureAndUsesBookmarkListAdd() throws Exception {
        String bridge = Files.readString(BRIDGE);

        assertTrue(bridge.contains("public static int addItemStacks(Collection<ItemStack> stacks)"));
        assertTrue(bridge.contains("public static int addFluidStacks(Collection<FluidStack> stacks)"));
        assertTrue(bridge.contains("Class.forName(\"mezz.jei.gui.bookmarks.IngredientBookmark\")"));
        assertTrue(bridge.contains("getMethod(\"add\", bookmarkType)"));
        assertTrue(bridge.contains("createTypedIngredient"));
        assertTrue(bridge.contains("getFluidIngredientType"));
        assertTrue(!bridge.contains("import mezz.jei."));
    }

    @Test
    void languageKeysExistInBothLocales() throws Exception {
        String zhCn = Files.readString(ZH_CN);
        String enUs = Files.readString(EN_US);

        for (String key : new String[]{
                "tooltip.gt_shanhai.jei.bookmark_missing",
                "tooltip.gt_shanhai.jei.bookmark_no_recipe",
                "message.gt_shanhai.jei.bookmark.no_items",
                "message.gt_shanhai.jei.bookmark.added",
                "message.gt_shanhai.jei.bookmark.already_bookmarked"}) {
            assertTrue(zhCn.contains("\"" + key + "\""));
            assertTrue(enUs.contains("\"" + key + "\""));
        }
    }

    private static Path source(String... parts) {
        Path path = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");
        for (String part : parts) path = path.resolve(part);
        return path;
    }

    private static Path lang(String file) {
        return Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang", file);
    }
}
