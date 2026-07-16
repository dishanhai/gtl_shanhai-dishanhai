package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTypePatternBufferRenderPerformanceSourceTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");
    private static final Path PAGINATION = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "CachedPatternPaginationUIManager.java");
    private static final Path SLOT = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "CachedPatternSlotWidget.java");

    @Test
    void patternSlotBypassesThirdPartyPerFrameDecodeHooks() throws IOException {
        assertTrue(Files.exists(SLOT), "星律必须使用专用槽位绕开第三方基类绘制注入");

        String machine = Files.readString(MACHINE);
        String pagination = Files.readString(PAGINATION);
        String slot = Files.readString(SLOT);
        int drawStart = slot.indexOf("public void drawInBackground");
        int drawEnd = slot.indexOf("private void refreshPatternCache", drawStart);
        String draw = slot.substring(drawStart, drawEnd);
        int cacheStart = slot.indexOf("private void refreshPatternCache");
        String cache = slot.substring(cacheStart);

        assertTrue(machine.contains("CachedPatternPaginationUIManager paginationUIManager"),
                "星律主界面必须接入缓存分页管理器");
        assertTrue(pagination.contains("new CachedPatternSlotWidget("));
        assertTrue(slot.contains("protected void drawBackgroundTexture"));
        assertTrue(draw.contains("drawBackgroundTexture(graphics, mouseX, mouseY);"));
        assertFalse(draw.contains("super.drawInBackground") || draw.contains("super.drawBackgroundTexture"),
                "调用基类绘制会重新进入上传特供 Jar 的两个 TAIL decode 注入");
        assertTrue(draw.contains("cachedPatternOutputText") && draw.contains("cachedPatternOutputKey"),
                "覆写绘制仍须保留输出数量与样板高亮");
        assertEquals(1, countOccurrences(cache, "PatternDetailsHelper.decodePattern("),
                "单次缓存刷新只能解码一次样板");
        assertTrue(pagination.contains("slot.invalidatePatternCache();"),
                "槽位变化回调必须主动清理专用槽位缓存");
    }

    private static int countOccurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
