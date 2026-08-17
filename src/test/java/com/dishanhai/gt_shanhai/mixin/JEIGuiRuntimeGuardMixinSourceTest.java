package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JEIGuiRuntimeGuardMixinSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "JEIGuiRuntimeGuardMixin.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void skipsJeiOverlayRenderingUntilRuntimeIsPublished() throws IOException {
        assertTrue(Files.exists(SOURCE), "必须提供 JEI Runtime 生命周期守卫");
        String source = Files.readString(SOURCE);
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(source.contains("mezz.jei.gui.events.GuiEventHandler"));
        assertTrue(source.contains("method = \"onDrawScreenPost\""));
        assertTrue(source.contains("Internal.getOptionalJeiRuntime().isEmpty()"),
                "只能在 JEI Runtime 尚未发布或已经销毁时跳过覆盖层");
        assertTrue(source.contains("ci.cancel()"),
                "Runtime 缺失时必须跳过本帧全部 JEI 覆盖层与 tooltip");
        assertTrue(config.contains("\"JEIGuiRuntimeGuardMixin\""),
                "守卫必须注册为 client Mixin");
    }
}
