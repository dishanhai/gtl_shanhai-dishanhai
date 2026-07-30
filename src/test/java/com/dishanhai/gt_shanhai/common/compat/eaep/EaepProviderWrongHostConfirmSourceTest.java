package com.dishanhai.gt_shanhai.common.compat.eaep;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EaepProviderWrongHostConfirmSourceTest {

    private static final Path ROOT = Path.of("src", "main");
    private static final Path SCREEN = javaPath("mixin", "EaepProviderSelectScreenRecipeTypeMixin.java");
    private static final Path ZH_LANG = ROOT.resolve("resources").resolve("assets")
            .resolve("gt_shanhai").resolve("lang").resolve("zh_cn.json");
    private static final Path EN_LANG = ROOT.resolve("resources").resolve("assets")
            .resolve("gt_shanhai").resolve("lang").resolve("en_us.json");

    @Test
    void providerSelectInterceptsWrongStellarHostAndBypassesAfterConfirm() throws IOException {
        String screen = Files.readString(SCREEN);

        assertTrue(screen.contains("consumeIncomingProviderWarningMetadata"));
        assertTrue(screen.contains("StellarPatternWarningPolicy.isWrongHost"));
        assertTrue(screen.contains("RecipeTypeSharedSearchSets::isShared"));
        assertTrue(screen.contains("new ConfirmScreen"));
        assertTrue(screen.contains("@Inject(method = \"onChoose(IZ)V\""));
        assertTrue(screen.contains("cancellable = true"));
        assertTrue(screen.contains("gtShanhai$bypassNextWrongHostWarning"));
        assertTrue(screen.contains("this.gtShanhai$bypassNextWrongHostWarning = true"));
        assertTrue(screen.contains("gtShanhai$onChoose(idx, showStatusMessage)"));
        assertTrue(screen.contains("ci.cancel()"));
    }

    @Test
    void wrongHostConfirmHasTranslatableText() throws IOException {
        String zh = Files.readString(ZH_LANG);
        String en = Files.readString(EN_LANG);

        assertTrue(zh.contains("gui.gt_shanhai.eaep_wrong_host_confirm.title"));
        assertTrue(zh.contains("gui.gt_shanhai.eaep_wrong_host_confirm.message"));
        assertTrue(en.contains("gui.gt_shanhai.eaep_wrong_host_confirm.title"));
        assertTrue(en.contains("gui.gt_shanhai.eaep_wrong_host_confirm.message"));
    }

    private static Path javaPath(String... parts) {
        Path path = ROOT.resolve("java").resolve("com").resolve("dishanhai").resolve("gt_shanhai");
        for (String part : parts) {
            path = path.resolve(part);
        }
        return path;
    }
}
