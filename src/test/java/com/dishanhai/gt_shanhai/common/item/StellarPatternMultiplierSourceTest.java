package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class StellarPatternMultiplierSourceTest {

    private static final Path MACHINE = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part",
            "RecipeTypePatternBufferPartMachine.java");
    private static final Path MIXIN = Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin",
            "SuperPatternAutoMatchMixin.java");
    private static final Path HELPER = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/item",
            "VirtualPatternEncodingHelper.java");

    @Test
    void stellarBufferPersistsMultiplierModeAndReadsPrimordialHost() throws IOException {
        String source = Files.readString(MACHINE);

        assertTrue(source.contains("@Persisted\n    private boolean outputMultiplierModeEnabled"));
        assertTrue(source.contains("@Persisted\n    private int patternOutputMultiplier"));
        assertTrue(source.contains("@DescSynced\n    @Persisted\n    private int cachedHostOutputMultiplier = 1"),
                "读宿主结果必须缓存并同步到客户端 UI，不能让显示端重新按客户端控制器状态算 1x");
        assertTrue(source.contains("host.getMountedOutputMultiplier()"));
        assertTrue(source.contains("controller instanceof PrimordialOmegaEngineModuleBase module"),
                "星律在原初模块结构里时，控制器是模块而不是万象主机，必须经模块读取宿主倍率");
        assertTrue(source.contains("module.getHostOutputMultiplier()"));
        assertTrue(source.contains("new OutputMultiplierConfigurator()"));
        assertTrue(source.contains("syncOutputMultiplierFromHost"));
        assertTrue(source.contains("syncOutputMultiplierFromPattern"));
    }

    @Test
    void realPatternReturnIsRewrittenOnlyForStellarBuffer() throws IOException {
        String source = Files.readString(MIXIN);

        assertTrue(source.contains("priority = 900"),
                "山海注入必须晚于 GTLAdd 对 getRealPattern 的默认优先级 Overwrite");
        assertTrue(source.contains("method = \"getRealPattern\""));
        assertTrue(source.contains("at = @At(\"RETURN\")"));
        assertTrue(source.contains("self instanceof RecipeTypePatternBufferPartMachine stellar"));
        assertTrue(source.contains("stellar.gtShanhai$applyOutputMultiplier"));
    }

    @Test
    void rewriteStartsFromBaseRecipeInsteadOfMultiplyingCurrentOutputsAgain() throws IOException {
        String source = Files.readString(HELPER);

        assertTrue(source.contains("rewritePatternOutputMultiplier"));
        assertTrue(source.contains("createScaledRecipeOutputs(recipe, targetMultiplier)"));
        assertTrue(source.contains("rewriteCycleContainerInputs"));
        assertTrue(source.contains("PatternDetailsHelper.encodeProcessingPattern"));
    }
}
