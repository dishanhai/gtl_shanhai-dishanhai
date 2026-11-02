package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：玻璃版面保持原生字級，窄視窗只換行／分頁，不再壓縮整頁。 */
class ShanhaiLoadingGlassLayoutTest {
    @Test void panelsStayInsideNativeGuiAndKeepReadableRowsAtEveryScale() {
        for (int[] size : new int[][]{{320, 180}, {426, 240}, {640, 360}, {960, 540}}) {
            var panel = ShanhaiLoadingLayout.glassPanel(size[0], size[1]);
            assertTrue(panel.x() >= 0 && panel.y() >= 0);
            assertTrue(panel.x() + panel.width() <= size[0]);
            assertTrue(panel.y() + panel.height() <= size[1]);
            assertTrue(panel.contentWidth() >= 200);
            assertTrue(panel.rowCapacity() >= 2);
            assertEquals(16, panel.lineHeight());
        }
        assertEquals(0, ShanhaiLoadingLayout.glassPanel(320, 180).railWidth());
        assertTrue(ShanhaiLoadingLayout.glassPanel(640, 360).railWidth() > 0);
    }

    @Test void startupAtlasProvidesSharedBaselineAndEnoughSourcePixels() throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/gt_shanhai/textures/gui/loading/shanhai_loading_font.json"))).getAsJsonObject();
        assertTrue(json.has("fontSize"), "啟動字型不能再把 24px 寫死在兩處");
        assertTrue(json.get("fontSize").getAsInt() >= 40);
        assertTrue(json.has("ascent") && json.has("descent"), "字母必須共用基線，不可逐字頂對齊");
        assertTrue(json.get("ascent").getAsInt() + json.get("descent").getAsInt() + 8 <= json.get("cell").getAsInt());
    }
}
