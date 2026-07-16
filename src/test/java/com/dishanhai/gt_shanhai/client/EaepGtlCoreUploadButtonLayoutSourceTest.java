package com.dishanhai.gt_shanhai.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EaepGtlCoreUploadButtonLayoutSourceTest {

    private static final Path MIXIN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "EaepGtlCoreUploadButtonLayoutMixin.java");
    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void movesUndoButtonAndItsCustomHitAreaAfterEaepPositioning() throws IOException {
        assertTrue(Files.exists(MIXIN), "必须新增 EAEP/GTLCore 上传按钮布局兼容 Mixin");

        String source = Files.readString(MIXIN);
        String config = Files.readString(CONFIG);

        assertTrue(config.contains("\"EaepGtlCoreUploadButtonLayoutMixin\""),
                "布局兼容 Mixin 必须注册在客户端列表");
        assertTrue(source.contains("@Mixin(value = PatternEncodingTermScreen.class, priority = 100)")
                        && source.contains("method = \"updateBeforeRender\"")
                        && source.contains("at = @At(\"TAIL\")"),
                "必须在具体编码终端完成 EAEP 按钮定位后再调整布局");
        assertTrue(source.contains("\"eap$uploadBtn\"")
                        && source.contains("\"gtlcore$quickUploadButton\"")
                        && source.contains("\"gtlcore$quickUploadUndoButton\""),
                "必须通过已注入字段取得按钮，不能依赖匿名内部类运行时名称");
        assertFalse(source.contains("gtShanhai$overlaps"),
                "EAEP 按钮存在时必须固定移开回收按钮，不能再被几何误判阻断");
        assertTrue(source.contains("quickUploadButton.getY() + quickUploadButton.getHeight() + 2"),
                "回收按钮必须移动到 GTLCore 快速上传按钮正下方");
        assertTrue(source.contains("gtlcore$quickUploadUndoHitX")
                        && source.contains("gtlcore$quickUploadUndoHitY"),
                "必须同步 GTLCore 的独立回收点击热区");
        assertOrder(source, "gtShanhai$setUndoHitPosition", "undoButton.setX",
                "确认热区可写后才能移动可见按钮，避免按钮与点击位置分离");
        assertTrue(source.contains("[EAEP布局] 已移动 GTLCore 回收按钮"),
                "首次移动必须记录前后坐标，供实机日志验真");
    }

    private static void assertOrder(String source, String first, String second, String message) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex, message);
    }
}
