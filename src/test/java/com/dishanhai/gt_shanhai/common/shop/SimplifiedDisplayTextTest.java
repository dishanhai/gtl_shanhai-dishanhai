package com.dishanhai.gt_shanhai.common.shop;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimplifiedDisplayTextTest {
    @Test
    void powerGoodsAndGuixuUseSimplifiedDisplayText() throws Exception {
        String editor = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopEntryEditScreen.java"));
        String panels = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/guixu/GuixuControlPanels.java"));
        String lang = Files.readString(Path.of("src/main/resources/assets/gt_shanhai/lang/zh_cn.json"));
        assertTrue(editor.contains("电力商品"));
        assertTrue(panels.contains("归墟工作模式"));
        assertTrue(lang.contains("视界物质解压"));
        assertFalse(lang.contains("視界物質解壓"));
    }
}
