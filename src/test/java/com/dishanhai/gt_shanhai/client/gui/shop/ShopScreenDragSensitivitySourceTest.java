package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopScreenDragSensitivitySourceTest {

    @Test
    void cardDragUsesHigherThresholdThanTabDrag() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        double tabThreshold = threshold(source, "DRAG_TAB_THRESHOLD");
        double cardThreshold = threshold(source, "DRAG_CARD_THRESHOLD");

        assertTrue(cardThreshold >= 12.0D, "商品卡片拖拽门槛必须明显高于点击抖动范围");
        assertTrue(cardThreshold > tabThreshold, "商品卡片拖拽门槛必须高于分类页签拖拽门槛");
        assertTrue(source.contains("dragCardStartY) >= DRAG_CARD_THRESHOLD"),
                "商品卡片拖拽判定必须使用独立的 DRAG_CARD_THRESHOLD");
    }

    private static double threshold(String source, String name) {
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*([0-9.]+)").matcher(source);
        assertTrue(matcher.find(), "缺少常量: " + name);
        return Double.parseDouble(matcher.group(1));
    }
}
