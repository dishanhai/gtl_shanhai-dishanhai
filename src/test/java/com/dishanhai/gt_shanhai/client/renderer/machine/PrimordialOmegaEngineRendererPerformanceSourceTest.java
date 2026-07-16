package com.dishanhai.gt_shanhai.client.renderer.machine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialOmegaEngineRendererPerformanceSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "client", "renderer", "machine", "PrimordialOmegaEngineRenderer.java");

    @Test
    void expensiveSpecialEffectsAreSkippedBehindOpenScreens() throws IOException {
        String source = Files.readString(SOURCE);
        int renderStart = source.indexOf("protected void renderSpecialEffects");
        int firstModelRender = source.indexOf("renderStar(", renderStart);
        String setup = source.substring(renderStart, firstModelRender);

        assertTrue(setup.contains("Minecraft.getInstance().screen != null"),
                "打开容器或其他 UI 时不应继续渲染原初恒星、轨道物与宇宙壳");
        assertTrue(setup.indexOf("return;") > setup.indexOf("Minecraft.getInstance().screen != null"),
                "UI 检查必须在任何特殊模型渲染之前直接返回");
    }
}
