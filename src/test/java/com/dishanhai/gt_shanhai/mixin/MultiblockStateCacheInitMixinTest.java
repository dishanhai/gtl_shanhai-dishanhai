package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MultiblockState} 的匹配緩衝區必須永遠非空。
 *
 * <p>GTCEu 只在 {@code clean()}（結構匹配第一步）裡建立 {@code cache}/
 * {@code globalCount}/{@code layerCount}。只要一次匹配都沒跑到，三者就是 null；
 * GTLCore 的 JEI 預覽清理會直接對它們 {@code clear()}/{@code trim()}，
 * 拋 NPE 打斷 JEI 布局更新，表現為結構預覽打不開。
 */
class MultiblockStateCacheInitMixinTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "MultiblockStateCacheInitMixin.java");
    private static final Path MB_DIR = Path.of("src", "main", "resources", "assets",
            "gt_shanhai", "multiblock");

    @Test
    void initializesEveryMatchBufferThatPreviewCleanupTouches() throws Exception {
        assertTrue(Files.isRegularFile(SOURCE), "必須提供 MultiblockState 匹配緩衝區初始化 mixin");
        String source = Files.readString(SOURCE);
        String config = MixinConfigs.readAll();

        assertTrue(source.contains("com.gregtechceu.gtceu.api.pattern.MultiblockState"),
                "必須以 GTCEu 的 MultiblockState 為目標");
        assertTrue(source.contains("method = \"<init>\""));
        // releasePreviewMemory 會依序碰這三者，缺一不可
        assertTrue(source.contains("this.cache == null"));
        assertTrue(source.contains("this.cache = new LongOpenHashSet()"));
        assertTrue(source.contains("this.globalCount == null"));
        assertTrue(source.contains("this.globalCount = new HashMap<>()"));
        assertTrue(source.contains("this.layerCount == null"));
        assertTrue(source.contains("this.layerCount = new HashMap<>()"));
        assertTrue(config.contains("\"MultiblockStateCacheInitMixin\""),
                "匹配緩衝區初始化 mixin 必須註冊到 gtceu mixin 配置");
    }

    /**
     * 迴歸鎖：本模組的 .mb 結構層數遠超預覽搜索守衛原本的 128 層上限，
     * 這正是「.mb 結構 JEI 預覽打不開」的觸發條件。層數下降時要重新評估守衛門檻。
     */
    @Test
    void mbStructuresExceedLegacyPreviewAisleDepthLimit() throws Exception {
        for (String name : new String[] { "tianjie_navigation_tower", "black_hole_containment" }) {
            assertTrue(aisleCount(name) > 128,
                    name + " 已不再超過 128 層，請重新確認預覽搜索守衛是否仍需放行固定層");
        }
    }

    private static int aisleCount(String name) throws Exception {
        String file = "/assets/gt_shanhai/multiblock/" + name + ".mb";
        try (InputStream is = MultiblockStateCacheInitMixinTest.class.getResourceAsStream(file)) {
            assertNotNull(is, "missing multiblock resource: " + file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            int layers = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                layers++;
            }
            return layers;
        }
    }

    @Test
    void multiblockResourceDirectoryExists() {
        assertTrue(Files.isDirectory(MB_DIR), MB_DIR + " must exist");
    }
}
