package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class WobbleFontPerformanceSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "WobbleFontMixin.java");

    @Test
    void fontRenderAndWidthHotPathsDoNotWriteDiagnosticLogs() throws IOException {
        String source = Files.readString(SOURCE);

        assertFalse(source.contains("LOG.info(\"[Wobble] width"),
                "Font.width 每帧高频调用，不能持续写 INFO 日志");
        assertFalse(source.contains("CURVE_LOG.warn("),
                "Font.drawInBatch 热路径不能保留可能逐帧触发的 WARN 日志");
    }
}
