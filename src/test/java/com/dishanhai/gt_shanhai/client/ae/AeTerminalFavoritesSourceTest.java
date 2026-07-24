package com.dishanhai.gt_shanhai.client.ae;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AeTerminalFavoritesSourceTest {

    private static final Path FAVORITES = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "ae", "AeTerminalFavorites.java");
    private static final Path REPO_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "AeTerminalRepoFavoritesMixin.java");
    private static final Path SCREEN_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "AeTerminalFavoritesScreenMixin.java");
    private static final Path SCREEN_ACCESSOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "AeTerminalFavoritesScreenAccessor.java");
    private static final Path CLIENT_INIT = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "ClientInit.java");
    private static final Path KEY_MAPPINGS = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "ShanhaiKeyMappings.java");
    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void favoritesPersistExactAeKeysInInsertionOrder() throws Exception {
        String source = Files.readString(FAVORITES);

        assertTrue(source.contains("LinkedHashSet<AEKey>"));
        assertTrue(source.contains("key.toTagGeneric()"));
        assertTrue(source.contains("AEKey.fromTagGeneric"));
        assertTrue(source.contains("ae_terminal_favorites.nbt"));
        assertTrue(source.contains("FMLPaths.CONFIGDIR"));
        assertTrue(source.contains("AEItemKey") && source.contains("AEFluidKey"));
    }

    @Test
    void repoMixinPromotesFavoritesAndCreatesMissingZeroAmountEntries() throws Exception {
        String source = Files.readString(REPO_MIXIN);

        assertTrue(source.contains("@Mixin(value = Repo.class"));
        assertTrue(source.contains("@Shadow @Final private BiMap<Long, GridInventoryEntry> entries"));
        assertTrue(source.contains("@Shadow @Final private ArrayList<GridInventoryEntry> view"));
        assertTrue(source.contains("@Shadow @Final private ArrayList<GridInventoryEntry> pinnedRow"));
        assertTrue(source.contains("new GridInventoryEntry(-1L, key, 0L, 0L, false)"));
        assertTrue(source.contains("getOrderedKeys()"));
        assertTrue(source.contains("@At(\"TAIL\")"));
        assertTrue(source.contains("nativePinnedKeys"));
        assertTrue(source.contains("view.addAll(orderedFavorites)"));
        assertFalse(source.contains("pinnedRow.clear()"));
        assertFalse(source.contains("pinnedRow.addAll("));
        assertFalse(source.contains("@Shadow private int rowSize"));
    }

    @Test
    void screenMixinConsumesConfiguredKeyOnlyForAeTerminalSlotsAndRendersStar() throws Exception {
        String source = Files.readString(SCREEN_MIXIN);
        String accessor = Files.readString(SCREEN_ACCESSOR);
        String clientInit = Files.readString(CLIENT_INIT);
        String keyMappings = Files.readString(KEY_MAPPINGS);
        String config = Files.readString(CONFIG);

        assertTrue(config.contains("AeTerminalFavoritesScreenMixin"));
        assertTrue(config.contains("AeTerminalFavoritesScreenAccessor"));
        assertTrue(config.contains("AeTerminalRepoFavoritesMixin"));
        assertTrue(accessor.contains("@Accessor(\"repo\")"));
        assertTrue(clientInit.contains("EventPriority.HIGHEST"));
        assertTrue(clientInit.contains("onAeTerminalFavoriteKey"));
        assertTrue(keyMappings.contains("AE_TERMINAL_FAVORITE"));
        assertTrue(keyMappings.contains("key.\" + GTDishanhaiMod.MOD_ID + \".ae_terminal_favorite"));
        assertTrue(keyMappings.contains("GLFW.GLFW_KEY_Q"));
        assertTrue(clientInit.contains("AE_TERMINAL_FAVORITE.isActiveAndMatches"));
        assertTrue(clientInit.contains("RepoSlot"));
        assertTrue(clientInit.contains("event.setCanceled(true)"));
        assertTrue(source.contains("@At(\"RETURN\")"));
        assertTrue(source.contains("★"));
    }
}
