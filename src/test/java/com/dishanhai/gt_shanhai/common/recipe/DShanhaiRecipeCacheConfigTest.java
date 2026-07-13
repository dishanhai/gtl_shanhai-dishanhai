package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.server.packs.PackType;
import net.minecraftforge.fml.loading.FMLPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DShanhaiRecipeCacheConfigTest {

    @BeforeAll
    static void initializeForgeGameDirectory() {
        FMLPaths.loadAbsolutePaths(Path.of("build", "test-game-dir"));
    }

    @AfterEach
    void unloadConfig() {
        DShanhaiConfig.COMMON_SPEC.setConfig(null);
        DShanhaiConfig.COMMON.kjsRecipeLibraryCacheEnabled.clearCache();
    }

    @Test
    void cacheIsDisabledByDefault() {
        assertFalse(DShanhaiConfig.COMMON.kjsRecipeLibraryCacheEnabled.getDefault());
    }

    @Test
    void cacheFailsClosedBeforeCommonConfigLoads() {
        DShanhaiConfig.COMMON_SPEC.setConfig(null);

        assertFalse(DShanhaiRecipeCache.isEnabled());
    }

    @Test
    void loadedConfigControlsCache() {
        DShanhaiConfig.COMMON_SPEC.setConfig(CommentedConfig.inMemory());
        assertFalse(DShanhaiRecipeCache.isEnabled());

        DShanhaiConfig.COMMON.kjsRecipeLibraryCacheEnabled.set(true);
        assertTrue(DShanhaiRecipeCache.isEnabled());
    }

    @Test
    void disabledConfigRejectsAPreviouslyCachedHit() throws Exception {
        DShanhaiConfig.COMMON_SPEC.setConfig(CommentedConfig.inMemory());
        Field cacheValid = DShanhaiRecipeCache.class.getDeclaredField("cacheValid");
        cacheValid.setAccessible(true);
        Object previous = cacheValid.get(null);
        try {
            cacheValid.set(null, Boolean.TRUE);
            assertFalse(DShanhaiRecipeCache.isCacheValid());
        } finally {
            cacheValid.set(null, previous);
        }
    }

    @Test
    void disabledConfigRejectsServerDataPackDiscovery() {
        DShanhaiConfig.COMMON_SPEC.setConfig(CommentedConfig.inMemory());

        assertFalse(DShanhaiRecipePackFinder.shouldHandle(PackType.SERVER_DATA));
    }
}
